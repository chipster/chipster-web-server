package fi.csc.chipster.auth.oidc.loginsessions;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fi.csc.chipster.auth.model.OidcLoginSession;
import fi.csc.chipster.rest.Config;

/**
 * Store OidLoginSessions in memory
 * 
 * When using this implementation, the admin must ensure that the two OIDC
 * requests from one user go to the same auth replica. The easiest way to ensure
 * this is to have only one replica. The login session is also lost, if auth is
 * restarted between those two requests.
 */
public class OidcLoginSessionsInMemory extends OidcLoginSessions {

    public OidcLoginSessionsInMemory(Config config) {
        super(config);
    }

    private ConcurrentHashMap<UUID, OidcLoginSession> chipsterOidcLogins = new ConcurrentHashMap<>();

    @Override
    public void add(OidcLoginSession chipsterOidcLogin) {
        chipsterOidcLogins.put(chipsterOidcLogin.getOidcLoginId(), chipsterOidcLogin);
    }

    @Override
    public OidcLoginSession get(UUID chipsterOidcLogin) {
        return chipsterOidcLogins.get(chipsterOidcLogin);
    }

    @Override
    public void update(OidcLoginSession chipsterOidcLogin) {
        chipsterOidcLogins.put(chipsterOidcLogin.getOidcLoginId(), chipsterOidcLogin);
    }

    @Override
    public OidcLoginSession remove(UUID chipsterOidcLoginId) {
        return chipsterOidcLogins.remove(chipsterOidcLoginId);
    }

    @Override
    public int cleanUp(Instant deleteBefore) {

        int deleted = 0;

        for (UUID key : chipsterOidcLogins.keySet()) {
            OidcLoginSession login = chipsterOidcLogins.get(key);

            // unless other thread removed already
            if (login != null) {
                if (login.getCreated().isBefore(deleteBefore)) {
                    chipsterOidcLogins.remove(key);
                    deleted++;
                }
            }
        }

        return deleted;
    }
}
