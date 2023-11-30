package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Root;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.query.Query;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;

import fi.csc.chipster.auth.model.ChipsterToken;
import fi.csc.chipster.auth.model.DatasetToken;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.SessionToken;
import fi.csc.chipster.auth.model.SessionToken.Access;
import fi.csc.chipster.auth.model.UserToken;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.StreamingOutput;

public class RuleTable {

	public static final String EVERYONE = "everyone";

	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	private Config config;

	private Set<String> servicesAccounts;

	// map appId (chipster/mylly) to example session owner account for that app
	private Map<String, String> restrictSharingToEveryone;

	public RuleTable(HibernateUtil hibernate) {
		this.hibernate = hibernate;
		this.config = new Config();
		this.servicesAccounts = config.getServicePasswords().keySet();
		this.restrictSharingToEveryone = config
				.getConfigEntries(Config.KEY_SESSION_DB_RESTRICT_SHARING_TO_EVERYONE + "-");
	}

	public Rule getRule(UUID ruleId, org.hibernate.Session hibernateSession) {
		Rule auth = hibernateSession.get(Rule.class, ruleId);
		return auth;
	}

	/**
	 * Delete Rule from the database only
	 * 
	 * Usually you should use this through RuleResource.delete(), because it will
	 * will check if the session can be deleted and publish a WebSocket event.
	 * 
	 * @param sessionId
	 * @param rule
	 * @param hibernateSession
	 */
	public void delete(UUID sessionId, Rule rule, org.hibernate.Session hibernateSession) {
		HibernateUtil.delete(rule, rule.getRuleId(), hibernateSession);
	}

	public List<Rule> getRules(String username) {

		List<Rule> rules = getRulesOwn(username);
		rules.addAll(getRulesOfEveryone());

		return rules;
	}

	public Session getSession(UUID sessionId) {
		try {
			CriteriaBuilder cb = hibernate.session().getCriteriaBuilder();
			CriteriaQuery<Session> c = cb.createQuery(Session.class);
			Root<Session> r = c.from(Session.class);
			r.fetch("rules", JoinType.LEFT);
			c.select(r);
			c.where(cb.equal(r.get("sessionId"), sessionId));
			return hibernate.getEntityManager().createQuery(c).getSingleResult();
		} catch (NoResultException e) {
			return null;
		}
	}

	public List<Rule> getRulesOwn(String username) {

		CriteriaBuilder cb = hibernate.session().getCriteriaBuilder();
		CriteriaQuery<Rule> c = cb.createQuery(Rule.class);
		Root<Rule> r = c.from(Rule.class);
		r.fetch("session", JoinType.LEFT);
		c.select(r);
		c.where(cb.equal(r.get("username"), username));
		List<Rule> rules = hibernate.getEntityManager().createQuery(c).getResultList();

		return rules;
	}

	List<Rule> getRulesOfEveryone() {
		return getRulesOwn(EVERYONE);
	}

	List<Rule> getRulesOfEveryone(String exampleSessionOwner) {
		return getRulesOfEveryone().stream().filter(r -> exampleSessionOwner.equals(r.getSharedBy()))
				.collect(Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	public List<Rule> getRules(UUID sessionId) {
		return hibernate.session().createQuery("from Rule where sessionId=:sessionId")
				.setParameter("sessionId", sessionId).list();
	}

	@SuppressWarnings("unchecked")
	public List<Rule> getShares(String userIdString) {
		return hibernate.session().createQuery("from Rule where sharedBy=:sharedBy")
				.setParameter("sharedBy", userIdString).list();
	}

	/**
	 * Stream the whole table as a json array
	 * 
	 * @return
	 */
	public Response getRules() {

		StreamingOutput stream = new StreamingOutput() {
			@Override
			public void write(final OutputStream output) {
				hibernate.runInTransaction(new HibernateRunnable<Void>() {
					@Override
					public Void run(org.hibernate.Session hibernateSession) {
						try {

							Query<Rule> query = hibernateSession.createQuery("from Rule", Rule.class);
							query.setReadOnly(true);

							ScrollableResults results = query.scroll(ScrollMode.FORWARD_ONLY);

							JsonGenerator jg = RestUtils.getObjectMapper(false).getFactory().createGenerator(output,
									JsonEncoding.UTF8);
							jg.writeStartArray();

							// iterate over results
							while (results.next()) {
								// process row then release reference
								Rule row = (Rule) results.get()[0];
								jg.writeObject(row);
								jg.flush();
								// you may need to flush every now and then
								// hibernate.session().flush();
							}
							results.close();

							jg.writeEndArray();
							jg.flush();
							jg.close();
						} catch (IOException e) {
							e.printStackTrace();
							logger.error(e);
							// rollback the transaction
							throw new RuntimeException(e);
						}
						return null;
					}
				});
			}
		};

		return Response.ok().entity(stream).type(MediaType.APPLICATION_JSON).build();
	}

	public Rule getRule(String username, Session session, org.hibernate.Session hibernateSession) {

		/*
		 * Allow access for services
		 * 
		 * This allows all services to access to all sessions. Maybe each resource
		 * method could specify only roles that really need the access? Or does the
		 * RolesAllowed do this in practice already?
		 */
		if (servicesAccounts.contains(username)) {
			return new Rule(username, true, null);
		}

		List<Rule> auths = session.getRules().stream()
				.filter(r -> username.equals(r.getUsername()) || EVERYONE.equals(r.getUsername()))
				.collect(Collectors.toList());

		// return the best (i.e. read-write) Authorization if there are multiple
		// Authorizations
		if (auths.isEmpty()) {
			return null;
		} else if (auths.size() == 1) {
			return auths.get(0);
		} else {
			for (Rule auth : auths) {
				if (auth.isReadWrite()) {
					return auth;
				}
			}
			return auths.get(0);
		}
	}

	/**
	 * Persist the authorization and its session in the DB
	 * 
	 * @param auth
	 * @param hibernateSession
	 */
	public void save(Rule auth, org.hibernate.Session hibernateSession) {
		logger.debug("save rule " + auth.getUsername());
		HibernateUtil.persist(auth, hibernateSession);
	}

	public boolean isAllowedToShareToEveryone(String userId) {
		return restrictSharingToEveryone.values().contains(userId);
	}

	public String getExampleSessionOwner(String appId) {
		return restrictSharingToEveryone.get(appId);
	}

	/**
	 * Check if a user is authorized to access or modify the whole session
	 * 
	 * Access is allowed if either 1. the user was authenticated with a UserToken in
	 * TokenRequestFilter creating a AuthPrincipal and there is a Rule which allows
	 * the authenticated username to access this session 2. the token is UserToken
	 * and user has Role.ADMIN and the allowAdmin parameter is true 3. the token is
	 * a SessionToken for the requested Session
	 * 
	 * 
	 * @param sc
	 * @param sessionId
	 * @param requireReadWrite
	 * @param hibernateSession
	 * @return
	 */
	public Session checkSessionAuthorization(ChipsterToken token, UUID requestSessionId, boolean requireReadWrite,
			org.hibernate.Session hibernateSession, boolean allowAdmin) {

		if (token instanceof UserToken) {

			UserToken userToken = (UserToken) token;

			// authenticated with an auth token

			String username = userToken.getUsername();

			if (username == null) {
				throw new ForbiddenException("username is null");
			}

			Session session = getSession(requestSessionId);

			if (session == null) {
				throw new NotFoundException("session not found");
			}

			Rule rule = getRule(username, session, hibernateSession);

			if (allowAdmin && token.getRoles().contains(Role.ADMIN)) {
				return session;
			}

			if (rule == null) {
				throw new ForbiddenException("access denied");
			}

			if (requireReadWrite) {
				if (!rule.isReadWrite()) {
					throw new ForbiddenException("read-write access denied");
				}
			}

			return session;

		} else if (token instanceof SessionToken) {

			SessionToken sessionToken = (SessionToken) token;

			UUID jwsSessionId = sessionToken.getSessionId();

			Session session = hibernateSession.get(Session.class, requestSessionId);

			if (session == null) {
				throw new NotFoundException("session not found");
			}

			if (!requestSessionId.equals(jwsSessionId)) {
				throw new ForbiddenException("token not valid for this session");
			}

			if (requireReadWrite && Access.READ_WRITE != sessionToken.getAccess()) {
				throw new ForbiddenException("no read-write access with this token");
			}

			return session;

		} else {

			// DatasetTokens shouldn't allow access to the whole session
			throw new ForbiddenException("token is not UserToken or SessionToken");
		}
	}

	/**
	 * Check the token is allowed to access a specific dataset
	 * 
	 * Access is allowed if either 1. the user was authenticated with a UserToken in
	 * TokenRequestFilter creating a AuthPrincipal and there is a Rule which allows
	 * the authenticated username to access this session 2. the token is a
	 * SessionToken for the whole session 3. the token is a DatasetToken for the
	 * requested Dataset
	 * 
	 * @param token
	 * @param token
	 * @param sessionId
	 * @param datasetId
	 * @param requireReadWrite
	 * @return
	 */
	public Dataset checkDatasetAuthorization(ChipsterToken token, UUID requestSessionId, UUID requestDatasetId,
			boolean requireReadWrite, org.hibernate.Session hibernateSession) {

		if (token instanceof UserToken) {

			// check that the user has an Rule which allows access to the session
			checkSessionAuthorization(token, requestSessionId, requireReadWrite, hibernateSession, false);
			Dataset dataset = SessionDbApi.getDataset(requestSessionId, requestDatasetId, hibernateSession);

			if (dataset == null) {
				throw new NotFoundException("dataset not found");
			}

			return dataset;

		} else if (token instanceof SessionToken) {

			// this is a token for the whole session
			checkSessionAuthorization(token, requestSessionId, requireReadWrite, hibernateSession, false);
			Dataset dataset = SessionDbApi.getDataset(requestSessionId, requestDatasetId, hibernateSession);

			if (dataset == null) {
				throw new NotFoundException("dataset not found");
			}

			return dataset;

		} else if (token instanceof DatasetToken) {

			DatasetToken datasetToken = (DatasetToken) token;

			if (requireReadWrite) {
				// no need for the writing with dataset tokens so far
				throw new ForbiddenException("dataset tokens are read-only");
			}

			Session session = hibernateSession.get(Session.class, requestSessionId);

			// sanity check although SessionResoure probably has checked this already
			if (session == null) {
				throw new NotFoundException("session not found");
			}

			if (!requestSessionId.equals(datasetToken.getSessionId())) {
				throw new ForbiddenException("token not valid for this session");
			}

			if (!requestDatasetId.equals(datasetToken.getDatasetId())) {
				throw new ForbiddenException("token not valid for this dataset");
			}

			Dataset dataset = SessionDbApi.getDataset(requestSessionId, requestDatasetId, hibernateSession);

			if (dataset == null) {
				throw new NotFoundException("dataset not found");
			}

			return dataset;

		} else {

			throw new NotAuthorizedException("uknown token type: " + token.getClass().getSimpleName());
		}
	}

	private ChipsterToken getChipsterToken(SecurityContext sc) {

		Principal principal = sc.getUserPrincipal();

		if (principal == null) {

			throw new NotAuthorizedException("principal is null");
		}

		if (!(principal instanceof AuthPrincipal)) {

			throw new ForbiddenException("principal is not AuthPrincipal");
		}

		AuthPrincipal authPrincipal = (AuthPrincipal) principal;

		if (authPrincipal.getToken() == null) {
			throw new NotAuthorizedException("token is null");
		}

		return authPrincipal.getToken();
	}

	public Dataset checkDatasetReadAuthorization(SecurityContext sc, UUID sessionId, UUID datasetId) {
		ChipsterToken token = getChipsterToken(sc);
		org.hibernate.Session hibernateSession = hibernate.session();
		return checkDatasetAuthorization(token, sessionId, datasetId, false, hibernateSession);
	}

	public Dataset checkDatasetAuthorization(SecurityContext sc, UUID sessionId, UUID datasetId,
			boolean requireReadWrite) {
		ChipsterToken token = getChipsterToken(sc);
		org.hibernate.Session hibernateSession = hibernate.session();
		return checkDatasetAuthorization(token, sessionId, datasetId, requireReadWrite, hibernateSession);
	}

	public Session checkSessionReadAuthorization(SecurityContext sc, UUID sessionId) {
		ChipsterToken token = getChipsterToken(sc);
		org.hibernate.Session hibernateSession = hibernate.session();
		return checkSessionAuthorization(token, sessionId, false, hibernateSession, false);
	}

	public Session checkSessionReadAuthorization(SecurityContext sc, UUID sessionId, boolean allowAdmin) {
		ChipsterToken token = getChipsterToken(sc);
		org.hibernate.Session hibernateSession = hibernate.session();
		return checkSessionAuthorization(token, sessionId, false, hibernateSession, allowAdmin);
	}

	public Session checkSessionReadWriteAuthorization(SecurityContext sc, UUID sessionId) {
		ChipsterToken token = getChipsterToken(sc);
		org.hibernate.Session hibernateSession = hibernate.session();
		return checkSessionAuthorization(token, sessionId, true, hibernateSession, false);
	}

	public Session checkSessionReadWriteAuthorization(SecurityContext sc, UUID sessionId, boolean allowAdmin) {
		ChipsterToken token = getChipsterToken(sc);
		org.hibernate.Session hibernateSession = hibernate.session();
		return checkSessionAuthorization(token, sessionId, true, hibernateSession, allowAdmin);
	}

	public Session checkSessionAuthorization(SecurityContext sc, UUID sessionId, boolean requireReadWrite,
			boolean allowAdmin) {
		ChipsterToken token = getChipsterToken(sc);
		org.hibernate.Session hibernateSession = hibernate.session();
		return checkSessionAuthorization(token, sessionId, requireReadWrite, hibernateSession, allowAdmin);
	}

	public Session checkSessionAuthorization(SecurityContext sc, UUID sessionId, boolean requireReadWrite,
			org.hibernate.Session hibernateSession, boolean allowAdmin) {
		ChipsterToken token = getChipsterToken(sc);
		return checkSessionAuthorization(token, sessionId, requireReadWrite, hibernateSession, allowAdmin);
	}
	
	public long getTotalSize(String username) {
	       // use native query, because Hibernate 5 doesn't support subqueries in from or join clauses
        BigDecimal size = (BigDecimal) hibernate.session().createNativeQuery(
                "select sum(size) from file inner join ("
                + "    select distinct dataset.fileid from rule "
                + "        inner join dataset on rule.sessionid=dataset.sessionid "
                + "    where rule.username=:username and readWrite=true) as dataset_fileid on dataset_fileid.fileid=file.fileid")
                .setParameter("username", username).getSingleResult();
        
        if (size == null) {
            // no sessions or datasets 
            return 0;
        }
        return size.longValue();
	}
}
