package fi.csc.chipster.auth.jaas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of the users file lookup in the JAAS configuration
 *
 * Each provider sets the java.security.auth.login.config system property of
 * the JVM. The tests write it back afterwards, because the tests that run a
 * HibernateUtil in this JVM make the JDK load the global JAAS configuration
 * from it, and the files of these tests are gone by then.
 */
public class JaasAuthenticationProviderTest {

    private static final String SIMPLE_FILE_MODULE = SimpleFileLoginModule.class.getName();
    private static final String PROPERTY_LOGIN_CONFIG = "java.security.auth.login.config";

    private static String originalLoginConfig;

    @TempDir
    Path tempDir;

    private int fileCounter = 0;

    @BeforeAll
    public static void saveLoginConfig() {
        originalLoginConfig = System.getProperty(PROPERTY_LOGIN_CONFIG);
    }

    @AfterAll
    public static void restoreLoginConfig() {
        if (originalLoginConfig == null) {
            System.clearProperty(PROPERTY_LOGIN_CONFIG);
        } else {
            System.setProperty(PROPERTY_LOGIN_CONFIG, originalLoginConfig);
        }
    }

    /**
     * A site-specific subclass, which the check must cover like the original
     */
    public static class SubclassLoginModule extends SimpleFileLoginModule {
    }

    private Path write(String jaasConfig) throws IOException {
        Path file = tempDir.resolve("jaas-" + (fileCounter++) + ".config");
        Files.writeString(file, jaasConfig);
        return file;
    }

    private Path writeTwoModules() throws IOException {
        return write("""
                Chipster {
                    %s sufficient passwdFile="security/users";
                    com.sun.security.auth.module.LdapLoginModule sufficient userProvider="ldap://localhost/";
                    %s sufficient passwdFile="/etc/chipster/users";
                };
                """.formatted(SIMPLE_FILE_MODULE, SIMPLE_FILE_MODULE));
    }

    private static final List<File> TWO_MODULES_FILES = List.of(new File("security/users"),
            new File("/etc/chipster/users"));

    @Test
    public void passwordFilesOfSimpleFileLoginModules() throws IOException {
        Path file = writeTwoModules();

        // as a file path, like in auth-jaas-conf-path
        assertEquals(TWO_MODULES_FILES, new JaasAuthenticationProvider(file.toString()).getPasswordFiles());

        // as a URL, like the default configuration in the jar
        assertEquals(TWO_MODULES_FILES, new JaasAuthenticationProvider(file.toUri().toString()).getPasswordFiles());
    }

    /**
     * The JDK expands ${property} in the system property, so a path like
     * ${user.home}/chipster/jaas.config works for the logins and must work for
     * this check too
     */
    @Test
    public void systemPropertyInPath() throws IOException {
        Path file = writeTwoModules();

        String property = "chipster.test.jaas.dir";
        System.setProperty(property, tempDir.toString());
        try {
            String confPath = "${" + property + "}/" + file.getFileName();

            assertEquals(TWO_MODULES_FILES, new JaasAuthenticationProvider(confPath).getPasswordFiles());
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    public void defaultConfiguration() throws IOException {
        String confPath = ClassLoader.getSystemClassLoader().getResource("jaas.config").toString();

        assertEquals(List.of(new File("security/users")), new JaasAuthenticationProvider(confPath).getPasswordFiles());
    }

    @Test
    public void subclassOfSimpleFileLoginModule() throws IOException {
        Path file = write("""
                Chipster {
                    %s sufficient passwdFile="site/users";
                };
                """.formatted(SubclassLoginModule.class.getName()));

        assertEquals(List.of(new File("site/users")),
                new JaasAuthenticationProvider(file.toString()).getPasswordFiles());
    }

    @Test
    public void otherLoginModulesOnly() throws IOException {
        Path file = write("""
                Chipster {
                    com.sun.security.auth.module.LdapLoginModule sufficient userProvider="ldap://localhost/";
                    fi.csc.chipster.auth.jaas.NoSuchLoginModule sufficient passwdFile="security/users";
                };
                """);

        assertEquals(List.of(), new JaasAuthenticationProvider(file.toString()).getPasswordFiles());
    }

    @Test
    public void loginContextMissing() throws IOException {
        Path file = write("""
                Unrelated {
                    %s sufficient passwdFile="security/users";
                };
                """.formatted(SIMPLE_FILE_MODULE));

        assertEquals(List.of(), new JaasAuthenticationProvider(file.toString()).getPasswordFiles());
    }

    /**
     * The JDK uses the "other" login context when the named one isn't
     * configured, so its users files are checked when there is no Chipster context
     */
    @Test
    public void otherLoginContextAsFallback() throws IOException {
        Path fallback = write("""
                other {
                    %s sufficient passwdFile="fallback/users";
                };
                """.formatted(SIMPLE_FILE_MODULE));

        assertEquals(List.of(new File("fallback/users")),
                new JaasAuthenticationProvider(fallback.toString()).getPasswordFiles());

        // but not when the Chipster context exists
        Path both = write("""
                Chipster {
                    %s sufficient passwdFile="security/users";
                };
                other {
                    %s sufficient passwdFile="fallback/users";
                };
                """.formatted(SIMPLE_FILE_MODULE, SIMPLE_FILE_MODULE));

        assertEquals(List.of(new File("security/users")),
                new JaasAuthenticationProvider(both.toString()).getPasswordFiles());
    }

    @Test
    public void passwordFileOptionMissing() throws IOException {
        Path file = write("""
                Chipster {
                    %s sufficient;
                };
                """.formatted(SIMPLE_FILE_MODULE));

        JaasAuthenticationProvider provider = new JaasAuthenticationProvider(file.toString());

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> provider.getPasswordFiles());

        assertTrue(e.getMessage().contains("passwdFile"), e.getMessage());
    }

    @Test
    public void configurationFileMissing() {
        String confPath = tempDir.resolve("missing.config").toString();

        assertThrows(IOException.class, () -> new JaasAuthenticationProvider(confPath));
    }

    @Test
    public void configurationFileInvalid() throws IOException {
        Path file = write("Chipster {\n");

        assertThrows(IOException.class, () -> new JaasAuthenticationProvider(file.toString()));
    }

    /**
     * The logins use the Configuration that the provider parsed, not the global
     * one of the JVM, so they see the same file as getPasswordFiles()
     */
    @Test
    public void loginUsesTheParsedConfiguration() throws IOException {
        Path users = tempDir.resolve("users");
        Files.writeString(users, "alice:s3cret\n");
        Path file = write("""
                Chipster {
                    %s required passwdFile="%s";
                };
                """.formatted(SIMPLE_FILE_MODULE, users));

        // a different, later configuration becomes the global one
        Path other = write("""
                Chipster {
                    %s required passwdFile="%s";
                };
                """.formatted(SIMPLE_FILE_MODULE, tempDir.resolve("no-such-users")));
        JaasAuthenticationProvider provider = new JaasAuthenticationProvider(file.toString());
        new JaasAuthenticationProvider(other.toString());

        assertEquals(List.of(users.toFile()), provider.getPasswordFiles());
        assertTrue(provider.authenticate("alice", "s3cret".toCharArray()));
        assertFalse(provider.authenticate("alice", "wrong1".toCharArray()));
    }
}
