package fi.csc.chipster.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.PasswordTestConfig;

/**
 * Tests of the startup check that refuses default and blank service passwords
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
}
