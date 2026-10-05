package fi.csc.chipster.auth.jaas;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Map;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Login module for Chipster type user lists. They have format
 * username:password:expiration, where expiration is optional and there can by
 * anything after the actual content of the line. Also comments (#) can be used.
 * See UsersFile for the parser.
 *
 * @author Taavi Hupponen, Aleksi Kallio
 *
 */
public class SimpleFileLoginModule extends LoginModuleBase {

    /**
     * Option for the path of the users file in the JAAS configuration
     */
    public static final String OPTION_PASSWD_FILE = "passwdFile";

    private static Logger logger = LogManager.getLogger();

    // configurable options
    protected File passwdFile;

    public void initialize(Subject subject, CallbackHandler callbackHandler, Map<String, ?> sharedState,
            Map<String, ?> options) {
        super.initialize(subject, callbackHandler, sharedState, options);

        // check password file
        String passwdFileName = (String) options.get(OPTION_PASSWD_FILE);
        this.passwdFile = new File(passwdFileName);

        if (!passwdFile.exists()) {
            logger.error("Password file " + passwdFile.getAbsolutePath()
                    + " not found, simple file login module not started.");
            throw new RuntimeException(passwdFile.getAbsolutePath() + " not found");
        }
    }

    /**
     * An IOException of the users file propagates to LoginModuleBase, which
     * reports it as a LoginException, so that it isn't mistaken for a wrong
     * password.
     */
    protected boolean authenticate(String username, char[] password) throws IOException {

        logger.debug(this.getClass().getName() + " authenticating " + username);

        for (UsersFile.Account account : UsersFile.read(this.passwdFile)) {

            if (!account.username().equals(username)) {
                // did not match
                continue;
            }

            if (!passwordMatches(account.password(), password)) {
                // did not match, but the same username may be on a later line
                continue;
            }

            if (!account.expiration().isEmpty()) {
                LocalDate expiration;
                try {
                    expiration = UsersFile.parseExpiration(account.expiration());
                } catch (DateTimeParseException e) {
                    logger.error("when authenticating " + username + " failed to parse exp. date: "
                            + account.expiration());
                    continue;
                }
                if (isExpired(expiration, LocalDate.now())) {
                    // authentication successful, but account has expired
                    continue;
                }
            }

            return true;
        }

        // matching line was not found
        return false;
    }

    /**
     * Compare the password of the users file with the given password
     *
     * A blank password in the file never matches, not even a blank login
     * password, because a blank password would be a typo rather than a decision
     * to let anyone in.
     *
     * MessageDigest.isEqual() takes the same time whatever the lengths and
     * contents of the passwords, so that the response time doesn't tell how many
     * characters of a guess were right. It also handles an empty password in the
     * file correctly, which the earlier character loop didn't: it had no
     * characters to compare and matched everything.
     *
     * The login password is encoded without a String copy and the bytes are
     * cleared afterwards, like LoginModuleBase clears its char[]. The passwords of
     * the file are Strings, as they have always been, because the file is read as
     * text.
     */
    static boolean passwordMatches(String filePassword, char[] password) {

        if (filePassword.isBlank()) {
            return false;
        }

        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] passwordBytes = new byte[encoded.remaining()];
        encoded.get(passwordBytes);

        try {
            return MessageDigest.isEqual(filePassword.getBytes(StandardCharsets.UTF_8), passwordBytes);
        } finally {
            Arrays.fill(passwordBytes, (byte) 0);
            if (encoded.hasArray()) {
                Arrays.fill(encoded.array(), (byte) 0);
            }
        }
    }

    /**
     * The account expires at the start of its expiration date
     *
     * @param expiration
     * @param today
     * @return true when today is the expiration date or later
     */
    static boolean isExpired(LocalDate expiration, LocalDate today) {
        return !today.isBefore(expiration);
    }
}
