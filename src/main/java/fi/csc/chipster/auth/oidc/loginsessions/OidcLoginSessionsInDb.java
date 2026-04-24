package fi.csc.chipster.auth.oidc.loginsessions;

import java.time.Instant;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;

import fi.csc.chipster.auth.model.OidcLoginSession;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaDelete;
import jakarta.persistence.criteria.Root;

/**
 * Save OidcLoginSession in database
 * 
 * This allows multiple auth replicas to handle the login requests.
 */
public class OidcLoginSessionsInDb extends OidcLoginSessions {

    @SuppressWarnings("unused")
    private static Logger logger = LogManager.getLogger();

    private HibernateUtil hibernate;

    public OidcLoginSessionsInDb(Config config, HibernateUtil hibernate) {
        super(config);
        this.hibernate = hibernate;
    }

    @Override
    public void add(OidcLoginSession chipsterOidcLogin) {
        HibernateUtil.persist(chipsterOidcLogin, this.hibernate.session());
    }

    @Override
    public OidcLoginSession get(UUID chipsterOidcLoginId) {
        @SuppressWarnings("null")
        OidcLoginSession loginSession = this.hibernate.session().find(OidcLoginSession.class, chipsterOidcLoginId);

        if (loginSession != null) {
            // detach the session, so that it can be updated and saved again in the same
            // transaction
            this.hibernate.session().detach(loginSession);
        }

        return loginSession;
    }

    @Override
    public void update(OidcLoginSession chipsterOidcLogin) {
        HibernateUtil.update(chipsterOidcLogin, chipsterOidcLogin.getOidcLoginId(), this.hibernate.session());
    }

    @Override
    public OidcLoginSession remove(UUID chipsterOidcLoginId) {

        @SuppressWarnings("null")
        OidcLoginSession loginSession = get(chipsterOidcLoginId);

        if (loginSession != null) {
            HibernateUtil.delete(loginSession, chipsterOidcLoginId, this.hibernate.session());
        }

        return loginSession;
    }

    @Override
    public int cleanUp(Instant deleteBefore) {

        Integer rows = hibernate.runInTransaction(new HibernateRunnable<Integer>() {
            @Override
            public Integer run(Session hibernateSession) {

                // there is no index for this query, but the number of in progress and recently
                // interrupted OidcLoginSessions should be easy to manage
                CriteriaBuilder criteriaBuilder = hibernateSession.getCriteriaBuilder();
                @SuppressWarnings("null")
                CriteriaDelete<OidcLoginSession> query = criteriaBuilder.createCriteriaDelete(OidcLoginSession.class);
                @SuppressWarnings("null")
                Root<OidcLoginSession> root = query.from(OidcLoginSession.class);
                query.where(criteriaBuilder.lessThan(root.get("created"), deleteBefore));

                int rows = hibernate.getEntityManager().createQuery(query).executeUpdate();

                return Integer.valueOf(rows);
            }
        });

        return rows;
    }
}
