package fi.csc.chipster.auth.resource;

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
    public OidcLoginSession remove(UUID chipsterOidcLoginId) {

        return this.hibernate.session().find(OidcLoginSession.class, chipsterOidcLoginId);
    }

    @Override
    public int cleanUp(Instant deleteBefore) {

        Integer rows = hibernate.runInTransaction(new HibernateRunnable<Integer>() {
            @Override
            public Integer run(Session hibernateSession) {

                CriteriaBuilder criteriaBuilder = hibernateSession.getCriteriaBuilder();
                CriteriaDelete<OidcLoginSession> query = criteriaBuilder.createCriteriaDelete(OidcLoginSession.class);
                Root<OidcLoginSession> root = query.from(OidcLoginSession.class);
                query.where(criteriaBuilder.lessThan(root.get("created"), deleteBefore));

                int rows = hibernate.getEntityManager().createQuery(query).executeUpdate();

                return Integer.valueOf(rows);
            }
        });

        return rows;
    }
}
