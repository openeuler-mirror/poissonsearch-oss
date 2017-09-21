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
import org.elasticsearch.cli.EnvironmentAwareCommand;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.MultiCommand;
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
import org.elasticsearch.xpack.security.authc.esnative.ReservedRealm;
import org.elasticsearch.xpack.security.support.Validation;
import org.elasticsearch.xpack.security.user.ElasticUser;
import org.elasticsearch.xpack.security.user.KibanaUser;
import org.elasticsearch.xpack.security.user.LogstashSystemUser;

import java.io.IOException;
import java.net.ConnectException;
import java.net.HttpURLConnection;
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
public class SetupPasswordTool extends MultiCommand {

    private static final char[] CHARS = ("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789~!@#$%^&*-_=+?").toCharArray();
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
            setupOptions(options, env);
            checkElasticKeystorePasswordValid(terminal);

            if (shouldPrompt) {
                terminal.println("Initiating the setup of reserved user " + String.join(",", USERS) + "  passwords.");
                terminal.println("The passwords will be randomly generated and printed to the console.");
                boolean shouldContinue = terminal.promptYesNo("Please confirm that you would like to continue", false);
                terminal.println("\n");
                if (shouldContinue == false) {
                    throw new UserException(ExitCodes.OK, "User cancelled operation");
                }
            }

            SecureRandom secureRandom = new SecureRandom();
            changePasswords((user) -> generatePassword(secureRandom, user),
                    (user, password) -> changedPasswordCallback(terminal, user, password));
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
            setupOptions(options, env);
            checkElasticKeystorePasswordValid(terminal);

            if (shouldPrompt) {
                terminal.println("Initiating the setup of reserved user " + String.join(",", USERS) + "  passwords.");
                terminal.println("You will be prompted to enter passwords as the process progresses.");
                boolean shouldContinue = terminal.promptYesNo("Please confirm that you would like to continue", false);
                terminal.println("\n");
                if (shouldContinue == false) {
                    throw new UserException(ExitCodes.OK, "User cancelled operation");
                }
            }

            changePasswords(user -> promptForPassword(terminal, user),
                    (user, password) -> changedPasswordCallback(terminal, user, password));
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
        private String url;

        SetupCommand(String description) {
            super(description);
            setParser();
        }

        void setupOptions(OptionSet options, Environment env) throws Exception {
            client = clientFunction.apply(env);
            try (KeyStoreWrapper keyStore = keyStoreFunction.apply(env)) {
                String providedUrl = urlOption.value(options);
                url = providedUrl == null ? client.getDefaultURL() : providedUrl;
                setShouldPrompt(options);

                // TODO: We currently do not support keystore passwords
                keyStore.decrypt(new char[0]);
                Settings build = Settings.builder().setSecureSettings(keyStore).build();
                elasticUserPassword = ReservedRealm.BOOTSTRAP_ELASTIC_PASSWORD.get(build);
            }
        }

        private void setParser() {
            urlOption = parser.acceptsAll(Arrays.asList("u", "url"), "The url for the change password request.").withOptionalArg();
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
        void checkElasticKeystorePasswordValid(Terminal terminal) throws Exception {
            URL route = new URL(url + "/_xpack/security/_authenticate?pretty");
            try {
                terminal.println(Verbosity.VERBOSE, "Testing if bootstrap password is valid for " + route.toString());
                int httpCode = client.postURL("GET", route, elasticUser, elasticUserPassword, () -> null, is -> {
                    byte[] bytes = Streams.readAll(is);
                    terminal.println(Verbosity.VERBOSE, new String(bytes, StandardCharsets.UTF_8));
                });
                // keystore password is not valid
                if (httpCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw new UserException(ExitCodes.CONFIG, "Failed to verify bootstrap password.");
                }
            } catch (ConnectException e) {
                throw new UserException(ExitCodes.CONFIG,
                        "Failed to connect to elasticsearch at " + route.toString() + ". Is the URL correct and elasticsearch running?", e);
            }
        }

        /**
         * Sets one user's password using the elastic superUser credentials.
         *
         * @param user
         *            The user who's password will change.
         * @param password
         *            the new password of the user.
         */
        private void changeUserPassword(String user, SecureString password) throws Exception {
            URL route = new URL(url + "/_xpack/security/user/" + user + "/_password");
            try {
                // supplier should own his resources
                SecureString supplierPassword = password.clone();
                client.postURL("PUT", route, elasticUser, elasticUserPassword, () -> {
                    try {
                        XContentBuilder xContentBuilder = JsonXContent.contentBuilder();
                        xContentBuilder.startObject().field("password", supplierPassword.toString()).endObject();
                        return xContentBuilder.string();
                    } finally {
                        supplierPassword.close();
                    }
                }, is -> {
                });
            } catch (IOException e) {
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
                CheckedBiConsumer<String, SecureString, Exception> successCallback) throws Exception {
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
                    changeUserPassword(entry.getKey(), entry.getValue());
                    successCallback.accept(entry.getKey(), entry.getValue());
                }
                // change elastic superuser
                if (superUserEntry != null) {
                    changeUserPassword(superUserEntry.getKey(), superUserEntry.getValue());
                    successCallback.accept(superUserEntry.getKey(), superUserEntry.getValue());
                }
            } finally {
                passwordsMap.forEach((user, pass) -> pass.close());
            }
        }
    }
}
