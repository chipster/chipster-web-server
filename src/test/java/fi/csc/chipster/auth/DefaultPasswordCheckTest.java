package fi.csc.chipster.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.PasswordTestConfig;

/**
 * Tests of the startup checks that refuse default and blank passwords, both the
 * service passwords in the configuration and the user passwords in the users
 * files of the JAAS SimpleFileLoginModule
 */
public class DefaultPasswordCheckTest {

    private static final String KEY_ALLOW_DEFAULT_PASSWORDS = AuthenticationService.KEY_ALLOW_DEFAULT_PASSWORDS;
    private static final String KEY_SERVICE_PASSWORD_BACKUP = "service-password-backup";

    @TempDir
    Path tempDir;

    private void check(Config config) {
        new AuthenticationService(config).checkDefaultPasswords();
    }

    @Test
    public void defaultPasswordsRefused() {
        Config config = PasswordTestConfig.defaultsOnly();

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(config));

        assertTrue(e.getMessage().contains("default passwords"), e.getMessage());
        assertTrue(e.getMessage().contains(KEY_SERVICE_PASSWORD_BACKUP), e.getMessage());
        assertTrue(e.getMessage().contains(Config.KEY_MONITORING_PASSWORD), e.getMessage());
        assertTrue(e.getMessage().contains(KEY_ALLOW_DEFAULT_PASSWORDS), e.getMessage());
    }

    @Test
    public void oneDefaultPasswordRefused() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir, KEY_SERVICE_PASSWORD_BACKUP, "backup");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(config));

        assertTrue(e.getMessage().contains(KEY_SERVICE_PASSWORD_BACKUP), e.getMessage());
    }

    @Test
    public void defaultPasswordsAllowedByFlag() throws IOException {
        Config config = PasswordTestConfig.fromYaml(tempDir, KEY_ALLOW_DEFAULT_PASSWORDS + ": true\n");

        assertDoesNotThrow(() -> check(config));
    }

    @Test
    public void blankPasswordRefusedDespiteFlag() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir,
                KEY_SERVICE_PASSWORD_BACKUP, "\"\"",
                KEY_ALLOW_DEFAULT_PASSWORDS, "true");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> check(config));

        assertTrue(e.getMessage().contains("blank passwords"), e.getMessage());
        assertTrue(e.getMessage().contains(KEY_SERVICE_PASSWORD_BACKUP), e.getMessage());
    }

    @Test
    public void allPasswordsSetAccepted() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir);

        assertDoesNotThrow(() -> check(config));
    }

    /*
     * Users files of the JAAS SimpleFileLoginModule
     */

    private int usersFileCounter = 0;

    private File usersFile(String content) throws IOException {
        Path file = tempDir.resolve("users-" + (usersFileCounter++));
        Files.writeString(file, content);
        return file.toFile();
    }

    private void checkUsers(Config config, File... usersFiles) throws IOException {
        new AuthenticationService(config).checkUsersFiles(List.of(usersFiles));
    }

    @Test
    public void defaultUserPasswordsRefused() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir);
        File users = usersFile("chipster:chipster\nalice:s3cret\nbob:clientPassword\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> checkUsers(config, users));

        assertTrue(e.getMessage().contains("default passwords"), e.getMessage());
        assertTrue(e.getMessage().contains(users.toString()), e.getMessage());
        assertTrue(e.getMessage().contains("chipster, bob"), e.getMessage());
        assertFalse(e.getMessage().contains("alice"), e.getMessage());
        assertTrue(e.getMessage().contains(KEY_ALLOW_DEFAULT_PASSWORDS), e.getMessage());
    }

    @Test
    public void defaultUserPasswordsAllowedByFlag() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir, KEY_ALLOW_DEFAULT_PASSWORDS, "true");
        File users = usersFile("chipster:chipster\n");

        assertDoesNotThrow(() -> checkUsers(config, users));
    }

    @Test
    public void blankUserPasswordRefusedDespiteFlag() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir, KEY_ALLOW_DEFAULT_PASSWORDS, "true");
        File users = usersFile("alice:s3cret\nbob:\n");

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> checkUsers(config, users));

        assertTrue(e.getMessage().contains("blank passwords"), e.getMessage());
        assertTrue(e.getMessage().contains("bob"), e.getMessage());
        assertFalse(e.getMessage().contains("alice"), e.getMessage());
    }

    @Test
    public void everyUsersFileChecked() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir);
        File good = usersFile("alice:s3cret\n");
        File bad = usersFile("admin:admin\n");
        File alsoBad = usersFile("carol:s3cret\nbob:clientPassword\n");

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> checkUsers(config, good, bad, alsoBad));

        // the problems of all files in one message
        assertTrue(e.getMessage().contains(bad + ": admin; " + alsoBad + ": bob"), e.getMessage());
        assertFalse(e.getMessage().contains(good.toString()), e.getMessage());
    }

    @Test
    public void missingUsersFileSkipped() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir);
        File missing = tempDir.resolve("missing").toFile();

        assertDoesNotThrow(() -> checkUsers(config, missing));
    }

    @Test
    public void goodUsersFileAccepted() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir);
        File users = usersFile("# accounts\nalice:s3cret::Comment\nbob:pa55word:2999-12-31\n");

        assertDoesNotThrow(() -> checkUsers(config, users));
        assertDoesNotThrow(() -> checkUsers(config));
    }

    @Test
    public void invalidExpirationOnlyLogged() throws IOException {
        Config config = PasswordTestConfig.allPasswordsSet(tempDir);
        File users = usersFile("alice:s3cret:2027-01-05\nbob:pa55word:soon\n");

        // the account can't log in, but the others can, so auth starts
        assertDoesNotThrow(() -> checkUsers(config, users));
    }
}
