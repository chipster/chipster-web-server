package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.io.OutputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.persistence.NoResultException;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.JoinType;
import javax.persistence.criteria.Root;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.StreamingOutput;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.ScrollMode;
import org.hibernate.ScrollableResults;
import org.hibernate.query.Query;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Rule;
import fi.csc.chipster.sessiondb.model.Session;

public class RuleTable {	
	
	public static final String EVERYONE = "everyone";

	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	private Config config;

	private Set<String> servicesAccounts;

	private DatasetTokenTable datasetTokenTable;

	private TokenRequestFilter tokenRequestFilter;

	private SessionResource ruleRemovedListener;

	public RuleTable(HibernateUtil hibernate, DatasetTokenTable datasetTokenTable, TokenRequestFilter tokenRequestFilter) {
		this.hibernate = hibernate;
		this.config = new Config();
		this.servicesAccounts = config.getServicePasswords().keySet();
		this.datasetTokenTable = datasetTokenTable;
		this.tokenRequestFilter = tokenRequestFilter;
	}
	
	public Rule getRule(UUID ruleId, org.hibernate.Session hibernateSession) {
		Rule auth =  hibernateSession.get(Rule.class, ruleId);
		return auth;
	}
	
    public void delete(UUID sessionId, Rule rule, org.hibernate.Session hibernateSession) {
    	HibernateUtil.delete(rule, rule.getRuleId(), hibernateSession);
    	
    	if (ruleRemovedListener != null) {
    		ruleRemovedListener.ruleRemoved(sessionId, rule);
    	}
    }
    
    public Session checkAuthorization(AuthPrincipal principal, UUID sessionId, boolean requireReadWrite, boolean allowAdmin) {
    	return checkAuthorization(principal, sessionId, requireReadWrite, hibernate.session(), allowAdmin);
    }
    
    /**
     * Check authorization solely based on the username
     * 
     * A dummy security context is created for the username.
     * 
     * @param sc
     * @param sessionId
     * @param requireReadWrite
     * @param allowAdmin
     * @return
     */
    public Session checkAuthorization(String username, UUID sessionId, boolean requireReadWrite) {
    	return checkAuthorization(new AuthPrincipal(username, new HashSet<>()), sessionId, requireReadWrite, false);
    }
    
	/**
	 * Check if a user is authorized to access or modify the whole session
	 * 
	 * @param sc
	 * @param sessionId
	 * @param requireReadWrite
	 * @param hibernateSession
	 * @return
	 */
	public Session checkAuthorization(AuthPrincipal principal, UUID sessionId, boolean requireReadWrite, org.hibernate.Session hibernateSession, boolean allowAdmin) {
		
		String username = principal.getName();

		if(username == null) {
			throw new ForbiddenException("username is null");
		}
		Session session = getSession(sessionId);
		
		if (session == null) {
			throw new NotFoundException("session not found");
		}
		
		Rule auth = getRule(username, session, hibernateSession);
		
		if (allowAdmin && principal.getRoles().contains(Role.ADMIN)) {
			return session;
		}
		
		if (auth == null) {
			throw new ForbiddenException("access denied");
		}
		
		if (requireReadWrite) {
			if (!auth.isReadWrite()) {
				throw new ForbiddenException("read-write access denied");
			}
		}
		
		return session;
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
	
	@SuppressWarnings("unchecked")
	public List<Rule> getRules(UUID sessionId) {		
		return hibernate.session()
				.createQuery("from Rule where sessionId=:sessionId")
				.setParameter("sessionId", sessionId)
				.list();
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

	    					JsonGenerator jg = RestUtils.getObjectMapper(false).getFactory().createGenerator(output, JsonEncoding.UTF8);
	    					jg.writeStartArray();

	    					// iterate over results
	    					while (results.next()) {
	    						// process row then release reference
	    						Rule row = (Rule) results.get()[0];
	    						jg.writeObject(row);
	    						jg.flush();
	    						// you may need to flush every now and then
    							//hibernate.session().flush();
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
 
        return Response.ok().entity( stream ).type( MediaType.APPLICATION_JSON ).build()    ;
	}
	
	public Rule getRule(String username, Session session, org.hibernate.Session hibernateSession) {
		
		if (servicesAccounts.contains(username)) {
			return new Rule(username, true, null);
		}
		
		List<Rule> auths = session.getRules().stream()
				.filter(r -> username.equals(r.getUsername()) || EVERYONE.equals(r.getUsername()))
				.collect(Collectors.toList());
		
		// return the best (i.e. read-write) Authorization if there are multiple Authorizations 
		if (auths.isEmpty()) { 
			return null;
		} else if (auths.size() == 1) {
			return auths.get(0);
		} else {
			for (Rule auth : auths) {
				if ( auth.isReadWrite()) {
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
		hibernateSession.save(auth);
	}

	/**
	 * Check the token is allowed to access a specific dataset
	 * 
	 * Access is allowed if either
	 * 1. auth-service accepts the token and the corresponding username has an Authorization to access the session
	 * 2. the token is a DatasetToken for the requested Dataset
	 * 
	 * @param userToken
	 * @param sessionId
	 * @param datasetId
	 * @param requireReadWrite
	 * @return
	 */
	public void checkAuthorizationWithToken(String userToken, UUID sessionId, UUID datasetId, boolean requireReadWrite) {
		try {
			// check that the token is valid and get the username
			String username = tokenRequestFilter.tokenAuthentication(userToken).getName();

			checkAuthorization(username, sessionId, datasetId, requireReadWrite);
		} catch (NotFoundException e) {
			if (requireReadWrite) {
				// write access is allowed only with the first method
				throw new ForbiddenException(e);
			} else {
				// read access to a specific dataset is possible with a DatasetToken 
				datasetTokenTable.checkAuthorization(UUID.fromString(userToken), sessionId, datasetId);
			}
		}
	}
	
	/**
	 * Check that the username is authorized to access a specific dataset
	 * 
	 * There must be an Authorization for the user to the session and the dataset must be in that session.
	 * 
	 * @param username
	 * @param sessionId
	 * @param datasetId
	 * @param requireReadWrite
	 * @return
	 */
	public Dataset checkAuthorization(String username, UUID sessionId, UUID datasetId, boolean requireReadWrite) {
		// check that the user has an Authorization to access the session		
		Session session = checkAuthorization(username, sessionId, requireReadWrite);
		Dataset dataset = hibernate.session().get(Dataset.class, datasetId);
				
		// check that the requested dataset is in the session
		// otherwise anyone with a session can access any dataset
		if (dataset == null || !dataset.getSession().getSessionId().equals(session.getSessionId())) {
			throw new NotFoundException("dataset not found");
		}
		
		return dataset;
	}

	public Session getSessionForReading(SecurityContext sc, UUID sessionId) {
		return getSessionForReading(sc, sessionId, false);
	}
	
	public Session getSessionForReading(SecurityContext sc, UUID sessionId, boolean allowAdmin) {
		return checkAuthorization((AuthPrincipal)sc.getUserPrincipal(), sessionId, false, allowAdmin);
	}
	
	public Session getSessionForWriting(SecurityContext sc, UUID sessionId) {
		return checkAuthorization((AuthPrincipal)sc.getUserPrincipal(), sessionId, true, false);
	}
	
	public void setRuleRemovedListener(SessionResource sessionResource) {
		this.ruleRemovedListener = sessionResource;
	}
}
