package fi.csc.chipster.auth.oidc.loginsessions;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import fi.csc.chipster.auth.model.OidcLoginSession;
import fi.csc.chipster.rest.Config;

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
