package fi.csc.chipster.auth.jaas;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.ParsePosition;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Parser of the users file of SimpleFileLoginModule
 *
 * Each account is on a line of its own in the format
 * username:password:expiration:comment, where the expiration (yyyy-MM-dd) and
 * the comment are optional and the comment may contain anything, including
 * more colons. Blank lines are skipped and a line is a comment when its first
 * non-blank character is #.
 *
 * SimpleFileLoginModule parses the file with this class, and auth checks the
 * passwords with this class on startup, so that both agree on the contents of
 * the file.
 */
public class UsersFile {

    public static final String DELIMITER = ":";
    public static final String COMMENT_PREFIX = "#";

    /**
     * Passwords of the accounts in the security/users file of the repository
     *
     * The file is public, so these passwords aren't safe in a deployment whoever
     * the user is. Together with password-equals-username, this covers every
     * account in that file.
     */
    public static final Set<String> DEFAULT_PASSWORDS = Set.of(
            "chipster",
            "admin",
            "example_session_owner",
            "support_session_owner",
            "clientPassword",
            "client2Password",
            "monitoring");

    /**
     * One line of the users file
     *
     * @param username   never null, trimmed
     * @param password   never null, empty when the line has no password
     * @param expiration expiration date yyyy-MM-dd or an empty string, trimmed
     */
    public record Account(String username, String password, String expiration) {
    }

    /**
     * Parse all accounts of a users file
     *
     * Bytes that aren't valid UTF-8 are replaced instead of failing the whole
     * file, like the FileReader of the earlier versions did, so that a stray
     * Latin-1 character in a comment doesn't lock everyone out.
     *
     * @param file
     * @return accounts in the order of the file, possibly with duplicate usernames
     * @throws IOException
     */
    public static List<Account> read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).lines()
                .map(line -> parseLine(line))
                .filter(account -> account != null)
                .collect(Collectors.toList());
    }

    /**
     * Parse one line of the users file
     *
     * @param line
     * @return the account or null for a blank or comment line
     */
    public static Account parseLine(String line) {

        // an indented comment is a comment too, otherwise the startup check would
        // refuse it as an account without a password
        if (line.isBlank() || line.strip().startsWith(COMMENT_PREFIX)) {
            return null;
        }

        // the fourth part is the comment, which may contain more delimiters
        String[] parts = line.split(DELIMITER, 4);

        // the username and the expiration are trimmed, because whitespace around
        // them is a typo that would make the account unusable, the password isn't,
        // because it's compared exactly as it's written
        String username = parts[0].trim();
        String password = parts.length > 1 ? parts[1] : "";
        String expiration = parts.length > 2 ? parts[2].trim() : "";

        return new Account(username, password, expiration);
    }

    /**
     * Find accounts with a blank password
     *
     * SimpleFileLoginModule never accepts a blank password, so these accounts
     * are unusable. Refusing them on startup makes the mistake visible.
     *
     * @param accounts
     * @return usernames in the order of the file
     */
    public static List<String> getBlankPasswordUsernames(List<Account> accounts) {
        return accounts.stream()
                .filter(account -> account.password().isBlank())
                .map(account -> account.username())
                .collect(Collectors.toList());
    }

    /**
     * Find accounts whose password is public
     *
     * The password is public when it's the same as the username, like in the
     * security/users file of the repository and in many examples, or when it's one
     * of the other passwords in that file.
     *
     * @param accounts
     * @return usernames in the order of the file
     */
    public static List<String> getDefaultPasswordUsernames(List<Account> accounts) {
        return accounts.stream()
                .filter(account -> isDefaultPassword(account))
                .map(account -> account.username())
                .collect(Collectors.toList());
    }

    public static boolean isDefaultPassword(Account account) {
        return account.password().equals(account.username())
                || DEFAULT_PASSWORDS.contains(account.password());
    }

    /**
     * Parse an expiration date
     *
     * The format is yyyy-MM-dd, but the month and the day may have one digit too
     * and anything may follow the date, because the lenient SimpleDateFormat of
     * the earlier versions accepted both and existing files may rely on it. An
     * impossible date like 2026-02-30 is an error, whereas SimpleDateFormat rolled
     * it over to March.
     *
     * @param expiration
     * @return the date
     * @throws DateTimeParseException
     */
    public static LocalDate parseExpiration(String expiration) {
        // parse(CharSequence, ParsePosition) accepts text after the date, parse(CharSequence) doesn't
        return LocalDate.from(EXPIRATION_FORMAT.parse(expiration, new ParsePosition(0)));
    }

    private static final DateTimeFormatter EXPIRATION_FORMAT = DateTimeFormatter.ofPattern("uuuu-M-d")
            .withResolverStyle(ResolverStyle.STRICT);

    /**
     * Find accounts whose expiration date can't be parsed
     *
     * SimpleFileLoginModule never accepts such an account, so the mistake is
     * better reported on startup than discovered by a user who can't log in.
     *
     * @param accounts
     * @return "username (expiration)" of each such account, in the order of the
     *         file
     */
    public static List<String> getInvalidExpirationUsernames(List<Account> accounts) {
        return accounts.stream()
                .filter(account -> !account.expiration().isEmpty() && !isValidExpiration(account.expiration()))
                .map(account -> account.username() + " (" + account.expiration() + ")")
                .collect(Collectors.toList());
    }

    public static boolean isValidExpiration(String expiration) {
        try {
            parseExpiration(expiration);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}
