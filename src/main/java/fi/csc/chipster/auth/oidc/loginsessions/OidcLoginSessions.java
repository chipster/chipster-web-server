package fi.csc.chipster.auth.oidc.loginsessions;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.OidcLoginSession;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;

public abstract class OidcLoginSessions {

    private static Logger logger = LogManager.getLogger();

    private static final String CONFIG_KEY_AUTH_OIDC_CLEAN_UP_AFTER = "auth-oidc-clean-up-after";

    public abstract void add(OidcLoginSession chipsterOidcLogin);

    public abstract OidcLoginSession remove(UUID chipsterOidcLoginId);

    public abstract int cleanUp(Instant deleteBefore);

    public OidcLoginSessions(Config config) {

        try {
            long cleanUpAfter = config.getLong(CONFIG_KEY_AUTH_OIDC_CLEAN_UP_AFTER);

            logger.info("clean-up interval is " + cleanUpAfter + " minutes");

            /*
             * There is only one config item used for both timer and the limit. With bad
             * luck it may take almost twice the time before the session is deleted. But
             * that's fine, because the purpose of this is mostly to prevent the
             * memory/storage usage from growing without limit.
             */
            new Timer(true).schedule(new TimerTask() {
                @Override
                public void run() {

                    logger.debug("deleted old OIDC sessions");

                    Instant deleteBefore = ZonedDateTime.now().minusSeconds(cleanUpAfter).toInstant();
                    int rows = cleanUp(deleteBefore);

                    if (rows > 0) {
                        logger.info("deleted expired incomlete OIDC sessions: " + rows);
                    }
                }
            },
                    cleanUpAfter * 1000,
                    cleanUpAfter * 1000);
        } catch (NumberFormatException e) {
            logger.info("job-history clean-up is not configured");
        }
    }

    public UUID add(String state, String nonce, String oidcName, String sourceIp) {

        UUID chipsterLoginId = RestUtils.createUUID();

        add(new OidcLoginSession(chipsterLoginId, state, nonce, Instant.now(), oidcName, sourceIp));

        return chipsterLoginId;
    }
}
