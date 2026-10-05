package fi.csc.chipster.auth.jaas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests of the password comparison of SimpleFileLoginModule
 *
 * The module is called directly, without a LoginContext, so the tests don't
 * touch the JAAS configuration of the JVM.
 */
public class SimpleFileLoginModuleTest {

    @TempDir
    Path tempDir;

    private int fileCounter = 0;

    private SimpleFileLoginModule module(String usersFileContent) throws IOException {
        Path file = tempDir.resolve("users-" + (fileCounter++));
        Files.writeString(file, usersFileContent);

        SimpleFileLoginModule module = new SimpleFileLoginModule();
        module.initialize(null, null, Map.of(), Map.of("passwdFile", file.toString()));
        return module;
    }

    private boolean authenticate(String usersFileContent, String username, String password) throws IOException {
        return module(usersFileContent).authenticate(username, password.toCharArray());
    }

    @Test
    public void correctPassword() throws IOException {
        assertTrue(authenticate("alice:s3cret\n", "alice", "s3cret"));
        assertTrue(authenticate("alice:s3cret::Comment: with: colons\n", "alice", "s3cret"));
        // whitespace around the username is ignored, in the password it isn't
        assertTrue(authenticate(" alice :s3cret\n", "alice", "s3cret"));
        assertFalse(authenticate("alice: s3cret\n", "alice", "s3cret"));
        assertTrue(authenticate("alice: s3cret\n", "alice", " s3cret"));
    }

    @Test
    public void wrongPassword() throws IOException {
        assertFalse(authenticate("alice:s3cret\n", "alice", "wrong1"));
        // a prefix or a longer password
        assertFalse(authenticate("alice:s3cret\n", "alice", "s3cre"));
        assertFalse(authenticate("alice:s3cret\n", "alice", "s3crets"));
        assertFalse(authenticate("alice:s3cret\n", "alice", ""));
    }

    @Test
    public void unknownUser() throws IOException {
        assertFalse(authenticate("alice:s3cret\n", "bob", "s3cret"));
        // a comment line isn't an account
        assertFalse(authenticate("#alice:s3cret\n", "alice", "s3cret"));
    }

    /**
     * An empty password in the file used to match any login password, because
     * the comparison looped over the characters of the file password and there
     * were none
     */
    @Test
    public void emptyPasswordInFileNeverMatches() throws IOException {
        assertFalse(authenticate("alice:\n", "alice", ""));
        assertFalse(authenticate("alice:\n", "alice", "anything"));
        assertFalse(authenticate("alice::\n", "alice", ""));
        assertFalse(authenticate("alice\n", "alice", ""));
    }

    @Test
    public void blankPasswordInFileNeverMatches() throws IOException {
        assertFalse(authenticate("alice:   \n", "alice", "   "));
        assertFalse(authenticate("alice:   \n", "alice", ""));
    }

    @Test
    public void expiration() throws IOException {
        assertTrue(authenticate("alice:s3cret:2999-12-31\n", "alice", "s3cret"));
        assertFalse(authenticate("alice:s3cret:2000-01-01\n", "alice", "s3cret"));
        // one digit month and day and text after the date, which the earlier lenient
        // parser accepted
        assertTrue(authenticate("alice:s3cret:2999-1-5\n", "alice", "s3cret"));
        assertTrue(authenticate("alice:s3cret:2999-12-31 (grant ends)\n", "alice", "s3cret"));
        assertFalse(authenticate("alice:s3cret:2000-12-31T00:00\n", "alice", "s3cret"));
        // unparseable expiration date
        assertFalse(authenticate("alice:s3cret:soon\n", "alice", "s3cret"));
        assertFalse(authenticate("alice:s3cret:2999-02-30\n", "alice", "s3cret"));
    }

    /**
     * The account expires at the start of its expiration date, like it did with
     * the earlier Date comparison
     */
    @Test
    public void isExpired() {
        LocalDate today = LocalDate.of(2026, 10, 2);

        assertFalse(SimpleFileLoginModule.isExpired(LocalDate.of(2026, 10, 3), today));
        assertTrue(SimpleFileLoginModule.isExpired(LocalDate.of(2026, 10, 2), today));
        assertTrue(SimpleFileLoginModule.isExpired(LocalDate.of(2026, 10, 1), today));
    }

    /**
     * A users file that disappears after the module was initialized is an
     * IOException, which LoginModuleBase reports as a LoginException instead of a
     * failed login
     */
    @Test
    public void fileRemoved() throws IOException {
        SimpleFileLoginModule module = module("alice:s3cret\n");
        Files.delete(module.passwdFile.toPath());

        assertThrows(IOException.class, () -> module.authenticate("alice", "s3cret".toCharArray()));
    }

    @Test
    public void sameUsernameOnSeveralLines() throws IOException {
        String users = "alice:first\nalice:second\n";

        assertTrue(authenticate(users, "alice", "first"));
        assertTrue(authenticate(users, "alice", "second"));
        assertFalse(authenticate(users, "alice", "third"));
    }

    @Test
    public void missingFile() {
        SimpleFileLoginModule module = new SimpleFileLoginModule();

        assertThrows(RuntimeException.class, () -> module.initialize(null, null, Map.of(),
                Map.of("passwdFile", tempDir.resolve("missing").toString())));
    }

    @Test
    public void passwordMatches() {
        assertTrue(SimpleFileLoginModule.passwordMatches("s3cret", "s3cret".toCharArray()));
        assertFalse(SimpleFileLoginModule.passwordMatches("s3cret", "s3cre".toCharArray()));
        assertFalse(SimpleFileLoginModule.passwordMatches("s3cret", "s3crets".toCharArray()));
        assertFalse(SimpleFileLoginModule.passwordMatches("s3cret", "S3cret".toCharArray()));
        assertFalse(SimpleFileLoginModule.passwordMatches("", "".toCharArray()));
        assertFalse(SimpleFileLoginModule.passwordMatches("", "x".toCharArray()));
        assertFalse(SimpleFileLoginModule.passwordMatches(" ", " ".toCharArray()));
    }
}
