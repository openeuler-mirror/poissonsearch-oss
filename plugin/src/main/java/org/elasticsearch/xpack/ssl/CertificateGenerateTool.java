/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License;
 * you may not use this file except in compliance with the Elastic License.
 */
package org.elasticsearch.xpack.ssl;

import javax.security.auth.x500.X500Principal;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import org.bouncycastle.asn1.DERIA5String;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.openssl.PEMEncryptor;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcePEMEncryptorBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.elasticsearch.ExceptionsHelper;
import org.elasticsearch.cli.EnvironmentAwareCommand;
import org.elasticsearch.cli.ExitCodes;
import org.elasticsearch.cli.Terminal;
import org.elasticsearch.cli.UserException;
import org.elasticsearch.common.ParseField;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.SuppressForbidden;
import org.elasticsearch.common.io.PathUtils;
import org.elasticsearch.common.network.InetAddresses;
import org.elasticsearch.common.util.set.Sets;
import org.elasticsearch.common.xcontent.ConstructingObjectParser;
import org.elasticsearch.common.xcontent.NamedXContentRegistry;
import org.elasticsearch.common.xcontent.ObjectParser;
import org.elasticsearch.common.xcontent.XContentParser;
import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.env.Environment;

/**
 * CLI tool to make generation of certificates or certificate requests easier for users
 * @deprecated Replaced by {@link CertificateTool}
 */
@Deprecated
public class CertificateGenerateTool extends EnvironmentAwareCommand {

    private static final String AUTO_GEN_CA_DN = "CN=Elastic Certificate Tool Autogenerated CA";
    private static final String DESCRIPTION = "Simplifies certificate creation for use with the Elastic Stack";
    private static final String DEFAULT_CSR_FILE = "csr-bundle.zip";
    private static final String DEFAULT_CERT_FILE = "certificate-bundle.zip";
    private static final int DEFAULT_DAYS = 3 * 365;
    private static final int FILE_EXTENSION_LENGTH = 4;
    static final int MAX_FILENAME_LENGTH = 255 - FILE_EXTENSION_LENGTH;
    private static final Pattern ALLOWED_FILENAME_CHAR_PATTERN =
            Pattern.compile("[a-zA-Z0-9!@#$%^&{}\\[\\]()_+\\-=,.~'` ]{1," + MAX_FILENAME_LENGTH + "}");
    private static final int DEFAULT_KEY_SIZE = 2048;

    /**
     * Wraps the certgen object parser.
     */
    private static class InputFileParser {
        private static final ObjectParser<List<CertificateInformation>, Void> PARSER = new ObjectParser<>("certgen");

        // if the class initializer here runs before the main method, logging will not have been configured; this will lead to status logger
        // error messages from the class initializer for ParseField since it creates Logger instances; therefore, we bury the initialization
        // of the parser in this class so that we can defer initialization until after logging has been initialized
        static {
            @SuppressWarnings("unchecked") final ConstructingObjectParser<CertificateInformation, Void> instanceParser =
                    new ConstructingObjectParser<>(
                            "instances",
                            a -> new CertificateInformation(
                                    (String) a[0], (String) (a[1] == null ? a[0] : a[1]),
                                    (List<String>) a[2], (List<String>) a[3], (List<String>) a[4]));
            instanceParser.declareString(ConstructingObjectParser.constructorArg(), new ParseField("name"));
            instanceParser.declareString(ConstructingObjectParser.optionalConstructorArg(), new ParseField("filename"));
            instanceParser.declareStringArray(ConstructingObjectParser.optionalConstructorArg(), new ParseField("ip"));
            instanceParser.declareStringArray(ConstructingObjectParser.optionalConstructorArg(), new ParseField("dns"));
            instanceParser.declareStringArray(ConstructingObjectParser.optionalConstructorArg(), new ParseField("cn"));

            PARSER.declareObjectArray(List::addAll, instanceParser, new ParseField("instances"));
        }
    }

    private final OptionSpec<String> outputPathSpec;
    private final OptionSpec<Void> csrSpec;
    private final OptionSpec<String> caCertPathSpec;
    private final OptionSpec<String> caKeyPathSpec;
    private final OptionSpec<String> caPasswordSpec;
    private final OptionSpec<String> caDnSpec;
    private final OptionSpec<Integer> keysizeSpec;
    private final OptionSpec<String> inputFileSpec;
    private final OptionSpec<Integer> daysSpec;
    private final ArgumentAcceptingOptionSpec<String> p12Spec;

    CertificateGenerateTool() {
        super(DESCRIPTION);
        outputPathSpec = parser.accepts("out", "path of the zip file that the output should be written to")
                .withRequiredArg();
        csrSpec = parser.accepts("csr", "only generate certificate signing requests");
        caCertPathSpec = parser.accepts("cert", "path to an existing ca certificate").availableUnless(csrSpec).withRequiredArg();
        caKeyPathSpec = parser.accepts("key", "path to an existing ca private key")
                .availableIf(caCertPathSpec)
                .requiredIf(caCertPathSpec)
                .withRequiredArg();
        caPasswordSpec = parser.accepts("pass", "password for an existing ca private key or the generated ca private key")
                .availableUnless(csrSpec)
                .withOptionalArg();
        caDnSpec = parser.accepts("dn", "distinguished name to use for the generated ca. defaults to " + AUTO_GEN_CA_DN)
                .availableUnless(caCertPathSpec)
                .availableUnless(csrSpec)
                .withRequiredArg();
        keysizeSpec = parser.accepts("keysize", "size in bits of RSA keys").withRequiredArg().ofType(Integer.class);
        inputFileSpec = parser.accepts("in", "file containing details of the instances in yaml format").withRequiredArg();
        daysSpec = parser.accepts("days", "number of days that the generated certificates are valid")
                .availableUnless(csrSpec)
                .withRequiredArg()
                .ofType(Integer.class);
        p12Spec = parser.accepts("p12", "output a p12 (PKCS#12) version for each certificate/key pair, with optional password")
                .availableUnless(csrSpec)
                .withOptionalArg();
    }

    public static void main(String[] args) throws Exception {
        new CertificateGenerateTool().main(args, Terminal.DEFAULT);
    }

    @Override
    protected void execute(Terminal terminal, OptionSet options, Environment env) throws Exception {
        final boolean csrOnly = options.has(csrSpec);
        printIntro(terminal, csrOnly);
        final Path outputFile = getOutputFile(terminal, outputPathSpec.value(options), csrOnly ? DEFAULT_CSR_FILE : DEFAULT_CERT_FILE);
        final String inputFile = inputFileSpec.value(options);
        final int keysize = options.has(keysizeSpec) ? keysizeSpec.value(options) : DEFAULT_KEY_SIZE;
        if (csrOnly) {
            Collection<CertificateInformation> certificateInformations = getCertificateInformationList(terminal, inputFile);
            generateAndWriteCsrs(outputFile, certificateInformations, keysize);
        } else {
            final String dn = options.has(caDnSpec) ? caDnSpec.value(options) : AUTO_GEN_CA_DN;
            final boolean prompt = options.has(caPasswordSpec);
            final char[] keyPass = options.hasArgument(caPasswordSpec) ? caPasswordSpec.value(options).toCharArray() : null;
            final int days = options.hasArgument(daysSpec) ? daysSpec.value(options) : DEFAULT_DAYS;
            final char[] p12Password;
            if (options.hasArgument(p12Spec)) {
                p12Password = p12Spec.value(options).toCharArray();
            } else if (options.has(p12Spec)) {
                p12Password = new char[0];
            } else {
                p12Password = null;
            }
            CAInfo caInfo = getCAInfo(terminal, dn, caCertPathSpec.value(options), caKeyPathSpec.value(options), keyPass, prompt, env,
                    keysize, days);
            Collection<CertificateInformation> certificateInformations = getCertificateInformationList(terminal, inputFile);
            generateAndWriteSignedCertificates(outputFile, certificateInformations, caInfo, keysize, days, p12Password);
        }
        printConclusion(terminal, csrOnly, outputFile);
    }

    @Override
    protected void printAdditionalHelp(Terminal terminal) {
        terminal.println("Simplifies the generation of certificate signing requests and signed");
        terminal.println("certificates. The tool runs interactively unless the 'in' and 'out' parameters");
        terminal.println("are specified. In the interactive mode, the tool will prompt for required");
        terminal.println("values that have not been provided through the use of command line options.");
        terminal.println("");
    }

    /**
     * Checks for output file in the user specified options or prompts the user for the output file
     *
     * @param terminal terminal to communicate with a user
     * @param outputPath user specified output file, may be {@code null}
     * @return a {@link Path} to the output file
     */
    static Path getOutputFile(Terminal terminal, String outputPath, String defaultFilename) throws IOException {
        Path file;
        if (outputPath != null) {
            file = resolvePath(outputPath);
        } else {
            file = resolvePath(defaultFilename);
            String input = terminal.readText("Please enter the desired output file [" + file + "]: ");
            if (input.isEmpty() == false) {
                file = resolvePath(input);
            }
        }
        return file.toAbsolutePath();
    }

    @SuppressForbidden(reason = "resolve paths against CWD for a CLI tool")
    private static Path resolvePath(String pathStr) {
        return PathUtils.get(pathStr).normalize();
    }

    /**
     * This method handles the collection of information about each instance that is necessary to generate a certificate. The user may
     * be prompted or the information can be gathered from a file
     * @param terminal the terminal to use for user interaction
     * @param inputFile an optional file that will be used to load the instance information
     * @return a {@link Collection} of {@link CertificateInformation} that represents each instance
     */
    static Collection<CertificateInformation> getCertificateInformationList(Terminal terminal, String inputFile)
            throws Exception {
        if (inputFile != null) {
            return parseAndValidateFile(terminal, resolvePath(inputFile).toAbsolutePath());
        }
        Map<String, CertificateInformation> map = new HashMap<>();
        boolean done = false;
        while (done == false) {
            String name = terminal.readText("Enter instance name: ");
            if (name.isEmpty() == false) {
                final boolean isNameValidFilename = Name.isValidFilename(name);
                String filename = terminal.readText("Enter name for directories and files " + (isNameValidFilename ? "[" + name + "]" : "")
                        + ": " );
                if (filename.isEmpty() && isNameValidFilename) {
                    filename = name;
                }
                String ipAddresses = terminal.readText("Enter IP Addresses for instance (comma-separated if more than one) []: ");
                String dnsNames = terminal.readText("Enter DNS names for instance (comma-separated if more than one) []: ");
                List<String> ipList = Arrays.asList(Strings.splitStringByCommaToArray(ipAddresses));
                List<String> dnsList = Arrays.asList(Strings.splitStringByCommaToArray(dnsNames));
                List<String> commonNames = null;

                CertificateInformation information = new CertificateInformation(name, filename, ipList, dnsList, commonNames);
                List<String> validationErrors = information.validate();
                if (validationErrors.isEmpty()) {
                    if (map.containsKey(name)) {
                        terminal.println("Overwriting previously defined instance information [" + name + "]");
                    }
                    map.put(name, information);
                } else {
                    for (String validationError : validationErrors) {
                        terminal.println(validationError);
                    }
                    terminal.println("Skipping entry as invalid values were found");
                }
            } else {
                terminal.println("A name must be provided");
            }

            String exit = terminal.readText("Would you like to specify another instance? Press 'y' to continue entering instance " +
                    "information: ");
            if ("y".equals(exit) == false) {
                done = true;
            }
        }
        return map.values();
    }

    static Collection<CertificateInformation> parseAndValidateFile(Terminal terminal, Path file) throws Exception {
        final Collection<CertificateInformation> config = parseFile(file);
        boolean hasError = false;
        for (CertificateInformation certInfo : config) {
            final List<String> errors = certInfo.validate();
            if (errors.size() > 0) {
                hasError = true;
                terminal.println(Terminal.Verbosity.SILENT, "Configuration for instance " + certInfo.name.originalName
                        + " has invalid details");
                for (String message : errors) {
                    terminal.println(Terminal.Verbosity.SILENT, " * " + message);
                }
                terminal.println("");
            }
        }
        if (hasError) {
            throw new UserException(ExitCodes.CONFIG, "File " + file + " contains invalid configuration details (see messages above)");
        }
        return config;
    }

    /**
     * Parses the input file to retrieve the certificate information
     * @param file the file to parse
     * @return a collection of certificate information
     */
    static Collection<CertificateInformation> parseFile(Path file) throws Exception {
        try (Reader reader = Files.newBufferedReader(file)) {
            // EMPTY is safe here because we never use namedObject
            XContentParser xContentParser = XContentType.YAML.xContent().createParser(NamedXContentRegistry.EMPTY, reader);
            return InputFileParser.PARSER.parse(xContentParser, new ArrayList<>(), null);
        }
    }

    /**
     * Generates certificate signing requests and writes them out to the specified file in zip format
     * @param outputFile the file to write the output to. This file must not already exist
     * @param certInfo the details to use in the certificate signing requests
     */
    static void generateAndWriteCsrs(Path outputFile, Collection<CertificateInformation> certInfo, int keysize) throws Exception {
        fullyWriteFile(outputFile, (outputStream, pemWriter) -> {
            for (CertificateInformation certificateInformation : certInfo) {
                KeyPair keyPair = CertUtils.generateKeyPair(keysize);
                GeneralNames sanList = getSubjectAlternativeNamesValue(certificateInformation.ipAddresses, certificateInformation.dnsNames,
                        certificateInformation.commonNames);
                PKCS10CertificationRequest csr = CertUtils.generateCSR(keyPair, certificateInformation.name.x500Principal, sanList);

                final String dirName = certificateInformation.name.filename + "/";
                ZipEntry zipEntry = new ZipEntry(dirName);
                assert zipEntry.isDirectory();
                outputStream.putNextEntry(zipEntry);

                // write csr
                outputStream.putNextEntry(new ZipEntry(dirName + certificateInformation.name.filename + ".csr"));
                pemWriter.writeObject(csr);
                pemWriter.flush();
                outputStream.closeEntry();

                // write private key
                outputStream.putNextEntry(new ZipEntry(dirName + certificateInformation.name.filename + ".key"));
                pemWriter.writeObject(keyPair.getPrivate());
                pemWriter.flush();
                outputStream.closeEntry();
            }
        });
    }

    /**
     * Returns the CA certificate and private key that will be used to sign certificates. These may be specified by the user or
     * automatically generated
     *
     * @param terminal the terminal to use for prompting the user
     * @param dn the distinguished name to use for the CA
     * @param caCertPath the path to the CA certificate or {@code null} if not provided
     * @param caKeyPath the path to the CA private key or {@code null} if not provided
     * @param prompt whether we should prompt the user for a password
     * @param keyPass the password to the private key. If not present and the key is encrypted the user will be prompted
     * @param env the environment for this tool to resolve files with
     * @param keysize the size of the key in bits
     * @param days the number of days that the certificate should be valid for
     * @return CA cert and private key
     */
    static CAInfo getCAInfo(Terminal terminal, String dn, String caCertPath, String caKeyPath, char[] keyPass, boolean prompt,
                            Environment env, int keysize, int days) throws Exception {
        if (caCertPath != null) {
            assert caKeyPath != null;
            final String resolvedCaCertPath = resolvePath(caCertPath).toAbsolutePath().toString();
            Certificate[] certificates = CertUtils.readCertificates(Collections.singletonList(resolvedCaCertPath), env);
            if (certificates.length != 1) {
                throw new IllegalArgumentException("expected a single certificate in file [" + caCertPath + "] but found [" +
                        certificates.length + "]");
            }
            Certificate caCert = certificates[0];
            PrivateKey privateKey = readPrivateKey(caKeyPath, keyPass, terminal, prompt);
            return new CAInfo((X509Certificate) caCert, privateKey);
        }

        // generate the CA keys and cert
        X500Principal x500Principal = new X500Principal(dn);
        KeyPair keyPair = CertUtils.generateKeyPair(keysize);
        Certificate caCert = CertUtils.generateCACertificate(x500Principal, keyPair, days);
        final char[] password;
        if (prompt) {
            password = terminal.readSecret("Enter password for CA private key: ");
        } else {
            password = keyPass;
        }
        return new CAInfo((X509Certificate) caCert, keyPair.getPrivate(), true, password);
    }

    /**
     * Generates signed certificates in PEM format stored in a zip file
     * @param outputFile the file that the certificates will be written to. This file must not exist
     * @param certificateInformations details for creation of the certificates
     * @param caInfo the CA information to sign the certificates with
     * @param keysize the size of the key in bits
     * @param days the number of days that the certificate should be valid for
     */
    static void generateAndWriteSignedCertificates(Path outputFile, Collection<CertificateInformation> certificateInformations,
                                                   CAInfo caInfo, int keysize, int days, char[] pkcs12Password) throws Exception {
        fullyWriteFile(outputFile, (outputStream, pemWriter) -> {
            // write out the CA info first if it was generated
            writeCAInfoIfGenerated(outputStream, pemWriter, caInfo);

            for (CertificateInformation certificateInformation : certificateInformations) {
                KeyPair keyPair = CertUtils.generateKeyPair(keysize);
                Certificate certificate = CertUtils.generateSignedCertificate(certificateInformation.name.x500Principal,
                        getSubjectAlternativeNamesValue(certificateInformation.ipAddresses, certificateInformation.dnsNames,
                                certificateInformation.commonNames),
                        keyPair, caInfo.caCert, caInfo.privateKey, days);

                final String dirName = certificateInformation.name.filename + "/";
                ZipEntry zipEntry = new ZipEntry(dirName);
                assert zipEntry.isDirectory();
                outputStream.putNextEntry(zipEntry);

                // write cert
                final String entryBase = dirName + certificateInformation.name.filename;
                outputStream.putNextEntry(new ZipEntry(entryBase + ".crt"));
                pemWriter.writeObject(certificate);
                pemWriter.flush();
                outputStream.closeEntry();

                // write private key
                outputStream.putNextEntry(new ZipEntry(entryBase + ".key"));
                pemWriter.writeObject(keyPair.getPrivate());
                pemWriter.flush();
                outputStream.closeEntry();

                if (pkcs12Password != null) {
                    final KeyStore pkcs12 = KeyStore.getInstance("PKCS12");
                    pkcs12.load(null);
                    pkcs12.setKeyEntry(certificateInformation.name.originalName, keyPair.getPrivate(), pkcs12Password,
                            new Certificate[]{certificate});

                    outputStream.putNextEntry(new ZipEntry(entryBase + ".p12"));
                    pkcs12.store(outputStream, pkcs12Password);
                    outputStream.closeEntry();
                }
            }
        });
    }

    /**
     * This method handles the deletion of a file in the case of a partial write
     * @param file the file that is being written to
     * @param writer writes the contents of the file
     */
    private static void fullyWriteFile(Path file, Writer writer) throws Exception {
        boolean success = false;
        try (OutputStream outputStream = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW);
             ZipOutputStream zipOutputStream = new ZipOutputStream(outputStream, StandardCharsets.UTF_8);
             JcaPEMWriter pemWriter = new JcaPEMWriter(new OutputStreamWriter(zipOutputStream, StandardCharsets.UTF_8))) {
            writer.write(zipOutputStream, pemWriter);

            // set permissions to 600
            PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (view != null) {
                view.setPermissions(Sets.newHashSet(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
            }

            success = true;
        } finally {
            if (success == false) {
                Files.deleteIfExists(file);
            }
        }
    }

    /**
     * This method handles writing out the certificate authority cert and private key if the certificate authority was generated by
     * this invocation of the tool
     * @param outputStream the output stream to write to
     * @param pemWriter the writer for PEM objects
     * @param info the certificate authority information
     */
    private static void writeCAInfoIfGenerated(ZipOutputStream outputStream, JcaPEMWriter pemWriter, CAInfo info) throws Exception {
        if (info.generated) {
            final String caDirName = "ca/";
            ZipEntry zipEntry = new ZipEntry(caDirName);
            assert zipEntry.isDirectory();
            outputStream.putNextEntry(zipEntry);
            outputStream.putNextEntry(new ZipEntry(caDirName + "ca.crt"));
            pemWriter.writeObject(info.caCert);
            pemWriter.flush();
            outputStream.closeEntry();
            outputStream.putNextEntry(new ZipEntry(caDirName + "ca.key"));
            if (info.password != null && info.password.length > 0) {
                try {
                    PEMEncryptor encryptor = new JcePEMEncryptorBuilder("DES-EDE3-CBC").setProvider(CertUtils.BC_PROV).build(info.password);
                    pemWriter.writeObject(info.privateKey, encryptor);
                } finally {
                    // we can safely nuke the password chars now
                    Arrays.fill(info.password, (char) 0);
                }
            } else {
                pemWriter.writeObject(info.privateKey);
            }
            pemWriter.flush();
            outputStream.closeEntry();
        }
    }

    private static void printIntro(Terminal terminal, boolean csr) {
        terminal.println("******************************************************************************");
        terminal.println("Note: The 'certgen' tool has been deprecated in favour of the 'certutil' tool.");
        terminal.println("      This command will be removed in a future release of X-Pack.");
        terminal.println("******************************************************************************");
        terminal.println("");

        terminal.println("This tool assists you in the generation of X.509 certificates and certificate");
        terminal.println("signing requests for use with SSL in the Elastic stack. Depending on the command");
        terminal.println("line option specified, you may be prompted for the following:");
        terminal.println("");
        terminal.println("* The path to the output file");
        if (csr) {
            terminal.println("    * The output file is a zip file containing the certificate signing requests");
            terminal.println("      and private keys for each instance.");
        } else {
            terminal.println("    * The output file is a zip file containing the signed certificates and");
            terminal.println("      private keys for each instance. If a Certificate Authority was generated,");
            terminal.println("      the certificate and private key will also be included in the output file.");
        }
        terminal.println("* Information about each instance");
        terminal.println("    * An instance is any piece of the Elastic Stack that requires a SSL certificate.");
        terminal.println("      Depending on your configuration, Elasticsearch, Logstash, Kibana, and Beats");
        terminal.println("      may all require a certificate and private key.");
        terminal.println("    * The minimum required value for each instance is a name. This can simply be the");
        terminal.println("      hostname, which will be used as the Common Name of the certificate. A full");
        terminal.println("      distinguished name may also be used.");
        terminal.println("    * A filename value may be required for each instance. This is necessary when the");
        terminal.println("      name would result in an invalid file or directory name. The name provided here");
        terminal.println("      is used as the directory name (within the zip) and the prefix for the key and");
        terminal.println("      certificate files. The filename is required if you are prompted and the name");
        terminal.println("      is not displayed in the prompt.");
        terminal.println("    * IP addresses and DNS names are optional. Multiple values can be specified as a");
        terminal.println("      comma separated string. If no IP addresses or DNS names are provided, you may");
        terminal.println("      disable hostname verification in your SSL configuration.");

        if (csr == false) {
            terminal.println("* Certificate Authority private key password");
            terminal.println("    * The password may be left empty if desired.");
        }
        terminal.println("");
        terminal.println("Let's get started...");
        terminal.println("");
    }

    private static void printConclusion(Terminal terminal, boolean csr, Path outputFile) {
        if (csr) {
            terminal.println("Certificate signing requests written to " + outputFile);
            terminal.println("");
            terminal.println("This file should be properly secured as it contains the private keys for all");
            terminal.println("instances.");
            terminal.println("");
            terminal.println("After unzipping the file, there will be a directory for each instance containing");
            terminal.println("the certificate signing request and the private key. Provide the certificate");
            terminal.println("signing requests to your certificate authority. Once you have received the");
            terminal.println("signed certificate, copy the signed certificate, key, and CA certificate to the");
            terminal.println("configuration directory of the Elastic product that they will be used for and");
            terminal.println("follow the SSL configuration instructions in the product guide.");
        } else {
            terminal.println("Certificates written to " + outputFile);
            terminal.println("");
            terminal.println("This file should be properly secured as it contains the private keys for all");
            terminal.println("instances and the certificate authority.");
            terminal.println("");
            terminal.println("After unzipping the file, there will be a directory for each instance containing");
            terminal.println("the certificate and private key. Copy the certificate, key, and CA certificate");
            terminal.println("to the configuration directory of the Elastic product that they will be used for");
            terminal.println("and follow the SSL configuration instructions in the product guide.");
            terminal.println("");
            terminal.println("For client applications, you may only need to copy the CA certificate and");
            terminal.println("configure the client to trust this certificate.");
        }
    }

    /**
     * Helper method to read a private key and support prompting of user for a key. To avoid passwords being placed as an argument we
     * can prompt the user for their password if we encounter an encrypted key.
     * @param path the path to the private key
     * @param password the password provided by the user or {@code null}
     * @param terminal the terminal to use for user interaction
     * @param prompt whether to prompt the user or not
     * @return the {@link PrivateKey} that was read from the file
     */
    private static PrivateKey readPrivateKey(String path, char[] password, Terminal terminal, boolean prompt)
                                            throws Exception {
        AtomicReference<char[]> passwordReference = new AtomicReference<>(password);
        try (Reader reader = Files.newBufferedReader(resolvePath(path), StandardCharsets.UTF_8)) {
            return CertUtils.readPrivateKey(reader, () -> {
                if (password != null || prompt == false) {
                    return password;
                }
                char[] promptedValue = terminal.readSecret("Enter password for CA private key: ");
                passwordReference.set(promptedValue);
                return promptedValue;
            });
        } finally {
            if (passwordReference.get() != null) {
                Arrays.fill(passwordReference.get(), (char) 0);
            }
        }
    }

    private static GeneralNames getSubjectAlternativeNamesValue(List<String> ipAddresses, List<String> dnsNames, List<String> commonNames) {
        Set<GeneralName> generalNameList = new HashSet<>();
        for (String ip : ipAddresses) {
            generalNameList.add(new GeneralName(GeneralName.iPAddress, ip));
        }

        for (String dns : dnsNames) {
            generalNameList.add(new GeneralName(GeneralName.dNSName, dns));
        }

        for (String cn : commonNames) {
            generalNameList.add(CertUtils.createCommonName(cn));
        }

        if (generalNameList.isEmpty()) {
            return null;
        }
        return new GeneralNames(generalNameList.toArray(new GeneralName[0]));
    }

    static class CertificateInformation {
        final Name name;
        final List<String> ipAddresses;
        final List<String> dnsNames;
        final List<String> commonNames;

        CertificateInformation(String name, String filename, List<String> ipAddresses, List<String> dnsNames, List<String> commonNames) {
            this.name = Name.fromUserProvidedName(name, filename);
            this.ipAddresses = ipAddresses == null ? Collections.emptyList() : ipAddresses;
            this.dnsNames = dnsNames == null ? Collections.emptyList() : dnsNames;
            this.commonNames = commonNames == null ? Collections.emptyList() : commonNames;
        }

        List<String> validate() {
            List<String> errors = new ArrayList<>();
            if (name.error != null) {
                errors.add(name.error);
            }
            for (String ip : ipAddresses) {
                if (InetAddresses.isInetAddress(ip) == false) {
                    errors.add("[" + ip + "] is not a valid IP address");
                }
            }
            for (String dnsName : dnsNames) {
                if (DERIA5String.isIA5String(dnsName) == false) {
                    errors.add("[" + dnsName + "] is not a valid DNS name");
                }
            }
            return errors;
        }
    }

    static class Name {

        final String originalName;
        final X500Principal x500Principal;
        final String filename;
        final String error;

        private Name(String name, X500Principal x500Principal, String filename, String error) {
            this.originalName = name;
            this.x500Principal = x500Principal;
            this.filename = filename;
            this.error = error;
        }

        static Name fromUserProvidedName(String name, String filename) {
            if ("ca".equals(name)) {
                return new Name(name, null, null, "[ca] may not be used as an instance name");
            }

            final X500Principal principal;
            try {
                if (name.contains("=")) {
                    principal = new X500Principal(name);
                } else {
                    principal = new X500Principal("CN=" + name);
                }
            } catch (IllegalArgumentException e) {
                String error = "[" + name + "] could not be converted to a valid DN\n" + e.getMessage() + "\n"
                        + ExceptionsHelper.stackTrace(e);
                return new Name(name, null, null, error);
            }

            boolean validFilename = isValidFilename(filename);
            if (validFilename == false) {
                return new Name(name, principal, null, "[" + filename + "] is not a valid filename");
            }
            return new Name(name, principal, resolvePath(filename).toString(), null);
        }

        static boolean isValidFilename(String name) {
            return ALLOWED_FILENAME_CHAR_PATTERN.matcher(name).matches()
                    && ALLOWED_FILENAME_CHAR_PATTERN.matcher(resolvePath(name).toString()).matches()
                    && name.startsWith(".") == false;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName()
                    + "{original=[" + originalName + "] principal=[" + x500Principal
                    + "] file=[" + filename + "] err=[" + error + "]}";
        }
    }

    static class CAInfo {
        final X509Certificate caCert;
        final PrivateKey privateKey;
        final boolean generated;
        final char[] password;

        CAInfo(X509Certificate caCert, PrivateKey privateKey) {
            this(caCert, privateKey, false, null);
        }

        CAInfo(X509Certificate caCert, PrivateKey privateKey, boolean generated, char[] password) {
            this.caCert = caCert;
            this.privateKey = privateKey;
            this.generated = generated;
            this.password = password;
        }
    }

    private interface Writer {
        void write(ZipOutputStream zipOutputStream, JcaPEMWriter pemWriter) throws Exception;
    }
}
