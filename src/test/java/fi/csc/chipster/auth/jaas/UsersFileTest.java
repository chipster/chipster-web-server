package fi.csc.chipster.auth.jaas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fi.csc.chipster.auth.jaas.UsersFile.Account;

/**
 * Tests of the users file parser and its password checks
 */
public class UsersFileTest {

    @TempDir
    Path tempDir;

    @Test
    public void parseLine() {
        assertEquals(new Account("alice", "s3cret", ""), UsersFile.parseLine("alice:s3cret"));
        assertEquals(new Account("alice", "s3cret", ""), UsersFile.parseLine("alice:s3cret::Default user"));
        assertEquals(new Account("alice", "s3cret", "2030-01-01"), UsersFile.parseLine("alice:s3cret:2030-01-01"));

        // the comment may contain more delimiters
        assertEquals(new Account("alice", "s3cret", "2030-01-01"),
                UsersFile.parseLine("alice:s3cret:2030-01-01:note: with: colons"));

        // the username and the expiration are trimmed, the password isn't
        assertEquals(new Account("alice", " s3cret ", "2030-01-01"),
                UsersFile.parseLine(" alice : s3cret : 2030-01-01 "));
    }

    @Test
    public void parseLineWithoutPassword() {
        assertEquals(new Account("alice", "", ""), UsersFile.parseLine("alice"));
        assertEquals(new Account("alice", "", ""), UsersFile.parseLine("alice:"));
        assertEquals(new Account("alice", "", ""), UsersFile.parseLine("alice::"));
    }

    @Test
    public void parseLineSkipsCommentsAndBlankLines() {
        assertNull(UsersFile.parseLine(""));
        assertNull(UsersFile.parseLine("   "));
        assertNull(UsersFile.parseLine("# alice:s3cret"));
        assertNull(UsersFile.parseLine("#"));
        // an indented comment
        assertNull(UsersFile.parseLine("  # alice:s3cret"));
        assertNull(UsersFile.parseLine("\t#"));
    }

    @Test
    public void read() throws IOException {
        File file = write("""
                # User accounts
                #
                alice:s3cret::Comment

                bob:pa55word:2030-01-01
                alice:other
                """);

        assertEquals(List.of(
                new Account("alice", "s3cret", ""),
                new Account("bob", "pa55word", "2030-01-01"),
                new Account("alice", "other", "")), UsersFile.read(file));
    }

    @Test
    public void readInvalidUtf8() throws IOException {
        // a Latin-1 ä in the comment
        byte[] content = "alice:s3cret::Näkyy\nbob:pa55word\n".getBytes(StandardCharsets.ISO_8859_1);
        Path file = tempDir.resolve("users-latin1");
        Files.write(file, content);

        assertEquals(List.of(
                new Account("alice", "s3cret", ""),
                new Account("bob", "pa55word", "")), UsersFile.read(file.toFile()));
    }

    @Test
    public void parseExpiration() {
        assertEquals(LocalDate.of(2027, 1, 5), UsersFile.parseExpiration("2027-01-05"));
        // the earlier lenient parser accepted one digit and text after the date too
        assertEquals(LocalDate.of(2027, 1, 5), UsersFile.parseExpiration("2027-1-5"));
        assertEquals(LocalDate.of(2027, 1, 5), UsersFile.parseExpiration("2027-01-05T00:00"));
        assertEquals(LocalDate.of(2027, 1, 5), UsersFile.parseExpiration("2027-01-05 (grant ends)"));

        assertThrows(DateTimeParseException.class, () -> UsersFile.parseExpiration("soon"));
        assertThrows(DateTimeParseException.class, () -> UsersFile.parseExpiration("2027-02-30"));
        assertThrows(DateTimeParseException.class, () -> UsersFile.parseExpiration("2027-13-01"));
        assertThrows(DateTimeParseException.class, () -> UsersFile.parseExpiration("05.01.2027"));
        assertThrows(DateTimeParseException.class, () -> UsersFile.parseExpiration(" 2027-01-05"));
    }

    @Test
    public void invalidExpirations() {
        List<Account> accounts = List.of(
                new Account("alice", "s3cret", ""),
                new Account("bob", "s3cret", "2027-01-05"),
                new Account("carol", "s3cret", "soon"),
                new Account("dave", "s3cret", "2027-02-30"));

        assertEquals(List.of("carol (soon)", "dave (2027-02-30)"), UsersFile.getInvalidExpirationUsernames(accounts));
    }

    @Test
    public void blankPasswords() {
        List<Account> accounts = List.of(
                new Account("alice", "", ""),
                new Account("bob", "  ", ""),
                new Account("carol", "s3cret", ""));

        assertEquals(List.of("alice", "bob"), UsersFile.getBlankPasswordUsernames(accounts));
    }

    @Test
    public void defaultPasswords() {
        assertTrue(UsersFile.isDefaultPassword(new Account("chipster", "chipster", "")));
        assertTrue(UsersFile.isDefaultPassword(new Account("admin", "admin", "")));
        // the same as the username
        assertTrue(UsersFile.isDefaultPassword(new Account("bob", "bob", "")));
        // a password of the repository's security/users
        assertTrue(UsersFile.isDefaultPassword(new Account("bob", "clientPassword", "")));
        assertTrue(UsersFile.isDefaultPassword(new Account("bob", "admin", "")));

        assertFalse(UsersFile.isDefaultPassword(new Account("bob", "Bob", "")));
        assertFalse(UsersFile.isDefaultPassword(new Account("bob", "s3cret", "")));
        assertFalse(UsersFile.isDefaultPassword(new Account("bob", "", "")));

        List<Account> accounts = List.of(
                new Account("chipster", "chipster", ""),
                new Account("alice", "s3cret", ""),
                new Account("bob", "client2Password", ""));

        assertEquals(List.of("chipster", "bob"), UsersFile.getDefaultPasswordUsernames(accounts));
    }

    /**
     * Every password in the repository's security/users is public, so the checks
     * must flag all of them. Add a new password to UsersFile.DEFAULT_PASSWORDS if
     * this fails.
     */
    @Test
    public void repositoryUsersFileHasOnlyDefaultPasswords() throws IOException {
        File file = new File("security/users");
        // Gradle runs the tests in the project directory, like the other tests that
        // read conf/chipster.yaml expect too
        assertTrue(file.exists(), file.getAbsolutePath() + " not found, run the tests in the project directory");

        List<Account> accounts = UsersFile.read(file);

        assertFalse(accounts.isEmpty());
        assertEquals(List.of(), UsersFile.getBlankPasswordUsernames(accounts));
        assertEquals(accounts.stream().map(Account::username).toList(),
                UsersFile.getDefaultPasswordUsernames(accounts));
    }

    private File write(String content) throws IOException {
        Path file = tempDir.resolve("users");
        Files.writeString(file, content);
        return file.toFile();
    }
}
