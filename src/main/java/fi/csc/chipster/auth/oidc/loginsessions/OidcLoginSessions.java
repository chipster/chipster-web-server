package fi.csc.chipster.auth.oidc.loginsessions;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.OidcLoginSession;
import fi.csc.chipster.auth.resource.OidcResource;
import fi.csc.chipster.rest.Config;
import jakarta.ws.rs.BadRequestException;

/**
 * Interface for storing and retrieving OidcLoginSessions.
 * 
 * Subcclasses implement the protected abstract methods, which only take care of
 * the actual storage and retrieval.
 * 
 * Callers use only the public methods, which check that the session is in the
 * correct state.
 * 
 * Valid login session states follow the 4 requests in OidcResource:
 * after 1st request: state, nonce and code are null
 * after 2nd request: state and nonce set, code is null
 * after 3rd request: state, nonce and code set
 * after 4th request: login session is removed
 * 
 * Methods checkAndUpdate and checkAndRemove check that the login session is in
 * the
 * correct state. If not, they throw an exception to fail the login.
 */
public abstract class OidcLoginSessions {

    private static Logger logger = LogManager.getLogger();

    protected abstract void add(OidcLoginSession session);

    protected abstract OidcLoginSession get(UUID chipsterOidcLoginId);

    protected abstract void update(OidcLoginSession chipsterOidcLoginSession);

    protected abstract OidcLoginSession remove(UUID chipsterOidcLoginId);

    protected abstract int cleanUp(Instant deleteBefore);

    public OidcLoginSessions(Config config) {

        try {
            int cleanUpAfter = config.getInt(OidcResource.CONF_MAX_LOGIN_DURATION);

            logger.info("clean-up interval is " + cleanUpAfter + " seconds");

            /*
             * There is only one config item used for both timer and the limit. With bad
             * luck it may take almost twice the time before the session is deleted. But
             * that's fine, because the purpose of this is mostly to prevent the
             * memory/storage usage from growing without limit.
             */
            ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

            Runnable cleanUpTask = new Runnable() {
                @Override
                public void run() {

                    logger.debug("delete old OIDC login sessions");

                    Instant deleteBefore = ZonedDateTime.now().minusSeconds(cleanUpAfter).toInstant();
                    int rows = cleanUp(deleteBefore);

                    if (rows > 0) {
                        logger.info("deleted expired incomlete OIDC login sessions: " + rows);
                    }
                }
            };

            scheduler.scheduleAtFixedRate(cleanUpTask, cleanUpAfter, cleanUpAfter, TimeUnit.SECONDS);

        } catch (NumberFormatException e) {
            logger.warn("OIDC login session clean-up is not configured");
        }
    }

    /**
     * Add a new OidcLoginSession.
     * 
     * New session are always in correct state.
     *
     * @param loginSessionId
     * @param oidcName
     * @param sourceIp
     * @return
     */
    public UUID add(UUID loginSessionId, String oidcName, String sourceIp) {

        add(new OidcLoginSession(loginSessionId, Instant.now(), oidcName, sourceIp));

        return loginSessionId;
    }

    /**
     * Return the OidcLoginSession for this ID.
     * 
     * throw an exception if the session is not found.
     * 
     * @param loginSessionId
     * @return
     */
    public OidcLoginSession getAndCheckExistence(UUID loginSessionId) {
        OidcLoginSession session = get(loginSessionId);

        if (session == null) {
            throw new BadRequestException("oidc session not found");
        }

        return session;
    }

    /**
     * Save the state and nonce
     * 
     * Check that the session is in correct state. If not, throw an exception.
     * 
     * @param loginSession must be not null
     * @param state
     * @param nonce
     * @return
     */
    public void validateAndUpdate(OidcLoginSession loginSession, String state, String nonce) {

        if (loginSession.getState() != null) {
            throw new BadRequestException("oidc login session already has state");
        }

        if (loginSession.getNonce() != null) {
            throw new BadRequestException("oidc login session already has nonce");
        }

        if (loginSession.getCode() != null) {
            throw new BadRequestException("oidc login session already has code");
        }

        loginSession.setState(state);
        loginSession.setNonce(nonce);

        this.update(loginSession);
    }

    /**
     * Save the code
     * 
     * Check that the session is in correct state. If not, throw an exception.
     * 
     * @param loginSession must be not null
     * @param code
     */
    public void validateAndUpdate(OidcLoginSession loginSession, String code) {

        if (loginSession.getState() == null) {
            throw new BadRequestException("oidc login session has no state");
        }

        if (loginSession.getNonce() == null) {
            throw new BadRequestException("oidc login session has no nonce");
        }

        if (loginSession.getCode() != null) {
            throw new BadRequestException("oidc login session already has code");
        }

        loginSession.setCode(code);

        this.update(loginSession);
    }

    /**
     * Return the OidcLoginSession for this ID and remove it.
     * 
     * Return null if the session is not found.
     */
    public void validateAndRemove(UUID loginSessionId) {
        OidcLoginSession session = remove(loginSessionId);

        if (session == null) {
            throw new BadRequestException("oidc session not found");
        }

        // Check that the login session is in correct state. If not, throw an exception
        // to fail the login.
        // Timer will remove the session eventually.
        if (session.getState() == null) {
            throw new BadRequestException("oidc session has no state");
        }

        if (session.getNonce() == null) {
            throw new BadRequestException("oidc session has no nonce");
        }

        if (session.getCode() == null) {
            throw new BadRequestException("oidc session has no code");
        }
    }
}
