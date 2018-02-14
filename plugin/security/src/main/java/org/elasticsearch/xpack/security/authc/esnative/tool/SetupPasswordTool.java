/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.security.authc.esnative.tool;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.bouncycastle.util.io.Streams;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cli.EnvironmentAwareCommand;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.LoggingAwareMultiCommand;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.Terminal.Verbosity;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.Booleans;
import org.elasticsearch.common.CheckedBiConsumer;
import org.elasticsearch.common.CheckedFunction;
import org.elasticsearch.common.settings.KeyStoreWrapper;
import org.elasticsearch.common.settings.SecureString;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.common.xcontent.json.JsonXContent;
import org.elasticsearch.env.Environment;
import org.elasticsearch.xpack.core.security.support.Validation;
import org.elasticsearch.xpack.core.security.user.ElasticUser;
import org.elasticsearch.xpack.core.security.user.KibanaUser;
import org.elasticsearch.xpack.core.security.user.LogstashSystemUser;
import org.elasticsearch.xpack.security.authc.esnative.ReservedRealm;
import org.elasticsearch.xpack.security.authc.esnative.tool.HttpResponse.HttpResponseBuilder;

import javax.net.ssl.SSLException;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A tool to set passwords of reserved users (elastic, kibana and
 * logstash_system). Can run in `interactive` or `auto` mode. In `auto` mode
 * generates random passwords and prints them on the console. In `interactive`
 * mode prompts for each individual user's password. This tool only runs once,
 * if successful. After the elastic user password is set you have to use the
 * `security` API to manipulate passwords.
 */
public class SetupPasswordTool extends LoggingAwareMultiCommand {

    private static final char[] CHARS = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789").toCharArray();
    public static final List<String> USERS = Arrays.asList(ElasticUser.NAME, KibanaUser.NAME, LogstashSystemUser.NAME);

    private final CheckedFunction<Environment, CommandLineHttpClient, Exception> clientFunction;
    private final CheckedFunction<Environment, KeyStoreWrapper, Exception> keyStoreFunction;
    private CommandLineHttpClient client;

    SetupPasswordTool() {
        this((environment) -> new CommandLineHttpClient(environment.settings(), environment), (environment) -> {
            KeyStoreWrapper keyStoreWrapper = KeyStoreWrapper.load(environment.configFile());
            if (keyStoreWrapper == null) {
                throw new UserException(ExitCodes.CONFIG,
                        "Elasticsearch keystore file is missing [" + KeyStoreWrapper.keystorePath(environment.configFile()) + "]");
            }
            return keyStoreWrapper;
        });
    }

    SetupPasswordTool(CheckedFunction<Environment, CommandLineHttpClient, Exception> clientFunction,
            CheckedFunction<Environment, KeyStoreWrapper, Exception> keyStoreFunction) {
        super("Sets the passwords for reserved users");
        subcommands.put("auto", newAutoSetup());
        subcommands.put("interactive", newInteractiveSetup());
        this.clientFunction = clientFunction;
        this.keyStoreFunction = keyStoreFunction;
    }

    protected AutoSetup newAutoSetup() {
        return new AutoSetup();
    }

    protected InteractiveSetup newInteractiveSetup() {
        return new InteractiveSetup();
    }

    public static void main(String[] args) throws Exception {
        exit(new SetupPasswordTool().main(args, Terminal.DEFAULT));
    }

    // Visible for testing
    OptionParser getParser() {
        return this.parser;
    }

    /**
     * This class sets the passwords using automatically generated random passwords.
     * The passwords will be printed to the console.
     */
    class AutoSetup extends SetupCommand {

        AutoSetup() {
            super("Uses randomly generated passwords");
        }

        @Override
        protected void execute(Terminal terminal, OptionSet options, Environment env) throws Exception {
            terminal.println(Verbosity.VERBOSE, "Running with configuration path: " + env.configFile());
            setupOptions(options, env);
            checkElasticKeystorePasswordValid(terminal, env);

            if (shouldPrompt) {
                terminal.println("Initiating the setup of passwords for reserved users " + String.join(",", USERS) + ".");
                terminal.println("The passwords will be randomly generated and printed to the console.");
                boolean shouldContinue = terminal.promptYesNo("Please confirm that you would like to continue", false);
                terminal.println("\n");
                if (shouldContinue == false) {
                    throw new UserException(ExitCodes.OK, "User cancelled operation");
                }
            }

            SecureRandom secureRandom = new SecureRandom();
            changePasswords((user) -> generatePassword(secureRandom, user),
                    (user, password) -> changedPasswordCallback(terminal, user, password), terminal);
        }

        private SecureString generatePassword(SecureRandom secureRandom, String user) {
            int passwordLength = 20; // Generate 20 character passwords
            char[] characters = new char[passwordLength];
            for (int i = 0; i < passwordLength; ++i) {
                characters[i] = CHARS[secureRandom.nextInt(CHARS.length)];
            }
            return new SecureString(characters);
        }

        private void changedPasswordCallback(Terminal terminal, String user, SecureString password) {
            terminal.println("Changed password for user " + user + "\n" + "PASSWORD " + user + " = " + password + "\n");
        }

    }

    /**
     * This class sets the passwords using input prompted on the console
     */
    class InteractiveSetup extends SetupCommand {

        InteractiveSetup() {
            super("Uses passwords entered by a user");
        }

        @Override
        protected void execute(Terminal terminal, OptionSet options, Environment env) throws Exception {
            terminal.println(Verbosity.VERBOSE, "Running with configuration path: " + env.configFile());
            setupOptions(options, env);
            checkElasticKeystorePasswordValid(terminal, env);

            if (shouldPrompt) {
                terminal.println("Initiating the setup of passwords for reserved users " + String.join(",", USERS) + ".");
                terminal.println("You will be prompted to enter passwords as the process progresses.");
                boolean shouldContinue = terminal.promptYesNo("Please confirm that you would like to continue", false);
                terminal.println("\n");
                if (shouldContinue == false) {
                    throw new UserException(ExitCodes.OK, "User cancelled operation");
                }
            }

            changePasswords(user -> promptForPassword(terminal, user),
                    (user, password) -> changedPasswordCallback(terminal, user, password), terminal);
        }

        private SecureString promptForPassword(Terminal terminal, String user) throws UserException {
            // loop for two consecutive good passwords
            while (true) {
                SecureString password1 = new SecureString(terminal.readSecret("Enter password for [" + user + "]: "));
                Validation.Error err = Validation.Users.validatePassword(password1.getChars());
                if (err != null) {
                    terminal.println(err.toString());
                    terminal.println("Try again.");
                    password1.close();
                    continue;
                }
                try (SecureString password2 = new SecureString(terminal.readSecret("Reenter password for [" + user + "]: "))) {
                    if (password1.equals(password2) == false) {
                        terminal.println("Passwords do not match.");
                        terminal.println("Try again.");
                        password1.close();
                        continue;
                    }
                }
                return password1;
            }
        }

        private void changedPasswordCallback(Terminal terminal, String user, SecureString password) {
            terminal.println("Changed password for user [" + user + "]");
        }
    }

    /**
     * An abstract class that provides functionality common to both the auto and
     * interactive setup modes.
     */
    private abstract class SetupCommand extends EnvironmentAwareCommand {

        boolean shouldPrompt;

        private OptionSpec<String> urlOption;
        private OptionSpec<String> noPromptOption;

        private String elasticUser = ElasticUser.NAME;
        private SecureString elasticUserPassword;
        private URL url;

        SetupCommand(String description) {
            super(description);
            setParser();
        }

        void setupOptions(OptionSet options, Environment env) throws Exception {
            client = clientFunction.apply(env);
            try (KeyStoreWrapper keyStore = keyStoreFunction.apply(env)) {
                String providedUrl = urlOption.value(options);
                url = new URL(providedUrl == null ? client.getDefaultURL() : providedUrl);
                setShouldPrompt(options);

                // TODO: We currently do not support keystore passwords
                keyStore.decrypt(new char[0]);
                Settings build = Settings.builder().setSecureSettings(keyStore).build();
                elasticUserPassword = ReservedRealm.BOOTSTRAP_ELASTIC_PASSWORD.get(build);
            }
        }

        private void setParser() {
            urlOption = parser.acceptsAll(Arrays.asList("u", "url"), "The url for the change password request.").withRequiredArg();
            noPromptOption = parser.acceptsAll(Arrays.asList("b", "batch"),
                    "If enabled, run the change password process without prompting the user.").withOptionalArg();
        }

        private void setShouldPrompt(OptionSet options) {
            String optionalNoPrompt = noPromptOption.value(options);
            if (options.has(noPromptOption)) {
                shouldPrompt = optionalNoPrompt != null && Booleans.parseBoolean(optionalNoPrompt) == false;
            } else {
                shouldPrompt = true;
            }
        }

        /**
         * Validates the bootstrap password from the local keystore by making an
         * '_authenticate' call. Returns silently if server is reachable and password is
         * valid. Throws {@link UserException} otherwise.
         *
         * @param terminal
         *            where to write verbose info.
         */
        void checkElasticKeystorePasswordValid(Terminal terminal, Environment env) throws Exception {
            URL route = createURL(url, "/_xpack/security/_authenticate", "?pretty");
            terminal.println(Verbosity.VERBOSE, "");
            terminal.println(Verbosity.VERBOSE, "Testing if bootstrap password is valid for " + route.toString());
            try {
                final HttpResponse httpResponse = client.execute("GET", route, elasticUser, elasticUserPassword, () -> null,
                        is -> responseBuilder(is, terminal));
                final int httpCode = httpResponse.getHttpStatus();

                // keystore password is not valid
                if (httpCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    terminal.println("");
                    terminal.println("Failed to authenticate user '" + elasticUser + "' against " + route.toString());
                    terminal.println("Possible causes include:");
                    terminal.println(" * The password for the '" + elasticUser + "' user has already been changed on this cluster");
                    terminal.println(" * Your elasticsearch node is running against a different keystore");
                    terminal.println("   This tool used the keystore at " + KeyStoreWrapper.keystorePath(env.configFile()));
                    terminal.println("");
                    throw new UserException(ExitCodes.CONFIG, "Failed to verify bootstrap password");
                } else if (httpCode != HttpURLConnection.HTTP_OK) {
                    terminal.println("");
                    terminal.println("Unexpected response code [" + httpCode + "] from calling GET " + route.toString());
                    XPackSecurityFeatureConfig xPackSecurityFeatureConfig = getXPackSecurityConfig(terminal);
                    if (xPackSecurityFeatureConfig.isAvailable == false) {
                        terminal.println("It doesn't look like the X-Pack security feature is available on this Elasticsearch node.");
                        terminal.println("Please check if you have installed a license that allows access to X-Pack Security feature.");
                        terminal.println("");
                        throw new UserException(ExitCodes.CONFIG, "X-Pack Security is not available.");
                    }
                    if (xPackSecurityFeatureConfig.isEnabled == false) {
                        terminal.println("It doesn't look like the X-Pack security feature is enabled on this Elasticsearch node.");
                        terminal.println("Please check if you have enabled X-Pack security in your elasticsearch.yml configuration file.");
                        terminal.println("");
                        throw new UserException(ExitCodes.CONFIG, "X-Pack Security is disabled by configuration.");
                    }
                    terminal.println("X-Pack security feature is available and enabled on this Elasticsearch node.");
                    terminal.println("Possible causes include:");
                    terminal.println(" * The relative path of the URL is incorrect. Is there a proxy in-between?");
                    terminal.println(" * The protocol (http/https) does not match the port.");
                    terminal.println(" * Is this really an Elasticsearch server?");
                    terminal.println("");
                    throw new UserException(ExitCodes.CONFIG, "Unknown error");
                }
            } catch (SSLException e) {
                terminal.println("");
                terminal.println("SSL connection to " + route.toString() + " failed: " + e.getMessage());
                terminal.println("Please check the elasticsearch SSL settings under " + CommandLineHttpClient.HTTP_SSL_SETTING);
                terminal.println(Verbosity.VERBOSE, "");
                terminal.println(Verbosity.VERBOSE, ExceptionsHelper.stackTrace(e));
                terminal.println("");
                throw new UserException(ExitCodes.CONFIG,
                        "Failed to establish SSL connection to elasticsearch at " + route.toString() + ". ", e);
            } catch (IOException e) {
                terminal.println("");
                terminal.println("Connection failure to: " + route.toString() + " failed: " + e.getMessage());
                terminal.println(Verbosity.VERBOSE, "");
                terminal.println(Verbosity.VERBOSE, ExceptionsHelper.stackTrace(e));
                terminal.println("");
                throw new UserException(ExitCodes.CONFIG, "Failed to connect to elasticsearch at " +
                        route.toString() + ". Is the URL correct and elasticsearch running?", e);
            }
        }

        @SuppressWarnings("unchecked")
        private XPackSecurityFeatureConfig getXPackSecurityConfig(Terminal terminal) throws Exception {
            // Get x-pack security info.
            URL route = createURL(url, "/_xpack", "?categories=features&human=false&pretty");
            final HttpResponse httpResponse = client.execute("GET", route, elasticUser, elasticUserPassword, () -> null,
                    is -> responseBuilder(is, terminal));
            if (httpResponse.getHttpStatus() != HttpURLConnection.HTTP_OK) {
                terminal.println("");
                terminal.println("Unexpected response code [" + httpResponse.getHttpStatus() + "] from calling GET " + route.toString());
                if (httpResponse.getHttpStatus() == HttpURLConnection.HTTP_BAD_REQUEST) {
                    terminal.println("It doesn't look like the X-Pack is available on this Elasticsearch node.");
                    terminal.println("Please check that you have followed all installation instructions and that this tool");
                    terminal.println("   is pointing to the correct Elasticsearch server.");
                    terminal.println("");
                    throw new UserException(ExitCodes.CONFIG, "X-Pack is not available on this Elasticsearch node.");
                } else {
                    terminal.println("* Try running this tool again.");
                    terminal.println("* Verify that the tool is pointing to the correct Elasticsearch server.");
                    terminal.println("* Check the elasticsearch logs for additional error details.");
                    terminal.println("");
                    throw new UserException(ExitCodes.TEMP_FAILURE, "Failed to determine x-pack security feature configuration.");
                }
            }
            final XPackSecurityFeatureConfig xPackSecurityFeatureConfig;
            if (httpResponse.getHttpStatus() == HttpURLConnection.HTTP_OK && httpResponse.getResponseBody() != null) {
                Map<String, Object> features = (Map<String, Object>) httpResponse.getResponseBody().get("features");
                if (features != null) {
                    Map<String, Object> featureInfo = (Map<String, Object>) features.get("security");
                    if (featureInfo != null) {
                        xPackSecurityFeatureConfig =
                                new XPackSecurityFeatureConfig(Boolean.parseBoolean(featureInfo.get("available").toString()),
                                        Boolean.parseBoolean(featureInfo.get("enabled").toString()));
                        return xPackSecurityFeatureConfig;
                    }
                }
            }
            terminal.println("");
            terminal.println("Unexpected response from calling GET " + route.toString());
            terminal.println("* Try running this tool again.");
            terminal.println("* Verify that the tool is pointing to the correct Elasticsearch server.");
            terminal.println("* Check the elasticsearch logs for additional error details.");
            terminal.println("");
            throw new UserException(ExitCodes.TEMP_FAILURE, "Failed to determine x-pack security feature configuration.");
        }

        /**
         * Sets one user's password using the elastic superUser credentials.
         *
         * @param user
         *            The user who's password will change.
         * @param password
         *            the new password of the user.
         */
        private void changeUserPassword(String user, SecureString password, Terminal terminal) throws Exception {
            URL route = createURL(url, "/_xpack/security/user/" + user + "/_password", "?pretty");
            terminal.println(Verbosity.VERBOSE, "");
            terminal.println(Verbosity.VERBOSE, "Trying user password change call " + route.toString());
            try {
                // supplier should own his resources
                SecureString supplierPassword = password.clone();
                final HttpResponse httpResponse = client.execute("PUT", route, elasticUser, elasticUserPassword, () -> {
                    try {
                        XContentBuilder xContentBuilder = JsonXContent.contentBuilder();
                        xContentBuilder.startObject().field("password", supplierPassword.toString()).endObject();
                        return xContentBuilder.string();
                    } finally {
                        supplierPassword.close();
                    }
                }, is -> responseBuilder(is, terminal));
                if (httpResponse.getHttpStatus() != HttpURLConnection.HTTP_OK) {
                    terminal.println("");
                    terminal.println(
                            "Unexpected response code [" + httpResponse.getHttpStatus() + "] from calling PUT " + route.toString());
                    terminal.println("Possible next steps:");
                    terminal.println("* Try running this tool again.");
                    terminal.println("* Check the elasticsearch logs for additional error details.");
                    terminal.println("* Use the change password API manually. ");
                    terminal.println("");
                    throw new UserException(ExitCodes.TEMP_FAILURE,
                            "Failed to set password for user [" + user + "].");
                }
            } catch (IOException e) {
                terminal.println("");
                terminal.println("Connection failure to: " + route.toString() + " failed: " + e.getMessage());
                terminal.println(Verbosity.VERBOSE, "");
                terminal.println(Verbosity.VERBOSE, ExceptionsHelper.stackTrace(e));
                terminal.println("");
                throw new UserException(ExitCodes.TEMP_FAILURE, "Failed to set password for user [" + user + "].", e);
            }
        }

        /**
         * Collects passwords for all the users, then issues set requests. Fails on the
         * first failed request. In this case rerun the tool to redo all the operations.
         *
         * @param passwordFn
         *            Function to generate or prompt for each user's password.
         * @param successCallback
         *            Callback for each successful operation
         */
        void changePasswords(CheckedFunction<String, SecureString, UserException> passwordFn,
                CheckedBiConsumer<String, SecureString, Exception> successCallback, Terminal terminal) throws Exception {
            Map<String, SecureString> passwordsMap = new HashMap<>(USERS.size());
            try {
                for (String user : USERS) {
                    passwordsMap.put(user, passwordFn.apply(user));
                }
                /*
                 * Change elastic user last. This tool will not run after the elastic user
                 * password is changed even if changing password for any subsequent user fails.
                 * Stay safe and change elastic last.
                 */
                Map.Entry<String, SecureString> superUserEntry = null;
                for (Map.Entry<String, SecureString> entry : passwordsMap.entrySet()) {
                    if (entry.getKey().equals(elasticUser)) {
                        superUserEntry = entry;
                        continue;
                    }
                    changeUserPassword(entry.getKey(), entry.getValue(), terminal);
                    successCallback.accept(entry.getKey(), entry.getValue());
                }
                // change elastic superuser
                if (superUserEntry != null) {
                    changeUserPassword(superUserEntry.getKey(), superUserEntry.getValue(), terminal);
                    successCallback.accept(superUserEntry.getKey(), superUserEntry.getValue());
                }
            } finally {
                passwordsMap.forEach((user, pass) -> pass.close());
            }
        }

        private HttpResponseBuilder responseBuilder(InputStream is, Terminal terminal) throws IOException {
            HttpResponseBuilder httpResponseBuilder = new HttpResponseBuilder();
            if (is != null) {
                byte[] bytes = Streams.readAll(is);
                String responseBody = new String(bytes, StandardCharsets.UTF_8);
                terminal.println(Verbosity.VERBOSE, responseBody);
                httpResponseBuilder.withResponseBody(responseBody);
            } else {
                terminal.println(Verbosity.VERBOSE, "<Empty response>");
            }
            return httpResponseBuilder;
        }
    }

    private static URL createURL(URL url, String path, String query) throws MalformedURLException, URISyntaxException {
        URL route = new URL(url, (url.toURI().getPath() + path).replaceAll("/+", "/") + query);
        return route;
    }

    /**
     * This class is used to capture x-pack security feature configuration.
     */
    static class XPackSecurityFeatureConfig {
        final boolean isAvailable;
        final boolean isEnabled;
        XPackSecurityFeatureConfig(boolean isAvailable, boolean isEnabled) {
            this.isAvailable = isAvailable;
            this.isEnabled = isEnabled;
        }
    }

}
