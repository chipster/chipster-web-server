package fi.csc.chipster.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of the service password checks in Config
 */
public class ConfigTest {

    private static final String KEY_SERVICE_PASSWORD_AUTH = "service-password-auth";
    private static final String KEY_SERVICE_PASSWORD_BACKUP = "service-password-backup";

    @TempDir
    Path tempDir;

    @Test
    public void testConfigIgnoresEnvironment() {
        // PATH is set in every environment, but isn't a configuration key, so only
        // the environment lookup can find it
        assertNull(PasswordTestConfig.defaultsOnly().getString("PATH", true, false, false));

        // a normal Config still reads the environment
        assertNotNull(new Config().getString("PATH", true, false, false));
    }

    @Test
    public void serviceNames() {
        List<String> services = PasswordTestConfig.defaultsOnly().getServiceNames();

        assertTrue(services.containsAll(
                List.of("auth", "session-db", "service-locator", "file-broker", "type-service", "s3-storage")),
                services.toString());

        // the monitoring account isn't a service
        assertFalse(services.contains("monitoring"), services.toString());
    }

    @Test
    public void defaultsOnly() {
        Config config = PasswordTestConfig.defaultsOnly();

        // every service account in chipster-defaults.yaml and the monitoring account
        assertEquals(PasswordTestConfig.PASSWORD_KEYS, config.getDefaultPasswordKeys());
        assertEquals(List.of(), config.getBlankPasswordKeys());
    }

    @Test
    public void allPasswordsSet() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir);

        assertEquals(List.of(), config.getDefaultPasswordKeys());
        assertEquals(List.of(), config.getBlankPasswordKeys());

        // the passwords are read from the file
        assertEquals(PasswordTestConfig.nonDefaultPassword(KEY_SERVICE_PASSWORD_AUTH),
                config.getServicePasswords().get("auth"));
    }

    @Test
    public void somePasswordsSet() throws IOException {
        Config config = PasswordTestConfig.fromEntries(tempDir, Map.of(
                KEY_SERVICE_PASSWORD_AUTH, PasswordTestConfig.nonDefaultPassword(KEY_SERVICE_PASSWORD_AUTH),
                Config.KEY_MONITORING_PASSWORD, PasswordTestConfig.nonDefaultPassword(Config.KEY_MONITORING_PASSWORD)));

        List<String> expected = new ArrayList<>(PasswordTestConfig.PASSWORD_KEYS);
        expected.remove(KEY_SERVICE_PASSWORD_AUTH);
        expected.remove(Config.KEY_MONITORING_PASSWORD);

        assertEquals(expected, config.getDefaultPasswordKeys());
        assertEquals(List.of(), config.getBlankPasswordKeys());
    }

    @Test
    public void passwordSetToItsDefaultValue() throws IOException {
        // explicitly configured, but still the public default value
        Config config = PasswordTestConfig.allPasswordsSet(tempDir, KEY_SERVICE_PASSWORD_AUTH, "auth");

        assertEquals(List.of(KEY_SERVICE_PASSWORD_AUTH), config.getDefaultPasswordKeys());
        assertEquals(List.of(), config.getBlankPasswordKeys());
    }

    @Test
    public void blankPassword() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir,
                KEY_SERVICE_PASSWORD_BACKUP, "\"\"",
                Config.KEY_MONITORING_PASSWORD, "\"  \"");

        assertEquals(List.of(Config.KEY_MONITORING_PASSWORD, KEY_SERVICE_PASSWORD_BACKUP),
                config.getBlankPasswordKeys());

        // a blank password is not reported as a default password too
        assertEquals(List.of(), config.getDefaultPasswordKeys());
    }

    @Test
    public void keyWithoutValueKeepsDefault() throws IOException {
        // yaml parses "key:" to null, which doesn't override the default
        Config config = PasswordTestConfig.allPasswordsSet(tempDir, KEY_SERVICE_PASSWORD_BACKUP, "");

        assertEquals(List.of(KEY_SERVICE_PASSWORD_BACKUP), config.getDefaultPasswordKeys());
        assertEquals(List.of(), config.getBlankPasswordKeys());
    }
}
