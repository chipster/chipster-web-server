package fi.csc.chipster.sessiondb;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.rest.websocket.ChipsterTopicConfig;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.sessiondb.resource.SessionResource;

public class SessionDbTopicConfig extends ChipsterTopicConfig {
	
	private Logger logger = LogManager.getLogger();
	
	private static final String TOPIC_GROUP_CLIENT = ",topicGroup=client";
	private static final String TOPIC_GROUP_SERVER = ",topicGroup=server";
	
	public static final String ALL_JOBS_TOPIC = "jobs";
	public static final String ALL_FILES_TOPIC = "files";
	public static final String ALL_AUTHORIZATIONS_TOPIC = "authorizations";
	public static final String ALL_DATASETS_TOPIC = "datasets";
	public static final String ALL_SESSIONS_TOPIC = "sessions";
	
	public static final String SESSIONS_TOPIC_PREFIX = "sessions/";
    public static final String USERS_TOPIC_PREFIX = "users/";

	private HibernateUtil hibernate;

	private SessionResource sessionResource;

	public SessionDbTopicConfig(AuthenticationClient authService, HibernateUtil hibernate, SessionResource sessionResource) {
		super(authService);
		this.hibernate = hibernate;
		this.sessionResource = sessionResource;		
	}	

	@Override
	public boolean isAuthorized(final AuthPrincipal principal, String topic) {
		logger.debug("check topic authorization for topic " + topic);
		
		if (ALL_JOBS_TOPIC.equals(topic) || ALL_FILES_TOPIC.equals(topic)) {
			return principal.getRoles().contains(Role.SERVER);
			
		} else if (ALL_DATASETS_TOPIC.equals(topic) || ALL_AUTHORIZATIONS_TOPIC.equals(topic) || ALL_SESSIONS_TOPIC.equals(topic)) {
			return principal.getRoles().contains(Role.SESSION_DB);
			
		} else if (topic.startsWith(SESSIONS_TOPIC_PREFIX)) {
			    
			    String sessionIdString = topic.substring(SESSIONS_TOPIC_PREFIX.length());
			    try {
		            final UUID sessionId = UUID.fromString(sessionIdString);
		            return isAuthorizedSessionId(sessionId, principal);
    
		        } catch (IllegalArgumentException e) {
		            logger.error("parsing UUID failed", e);
		            return false;
		        }
				
		} else 	if (topic.startsWith(USERS_TOPIC_PREFIX)) {
		
		    String userIdString = topic.substring(USERS_TOPIC_PREFIX.length());
		    
			UserId userId = new UserId(userIdString);
			return isAuthorizedUserId(userId, principal);
		}
		
		return false;
	}
	
	private boolean isAuthorizedSessionId(UUID sessionId, AuthPrincipal principal) {
		return hibernate.runInTransaction(new HibernateRunnable<Boolean>() {
			@Override
			public Boolean run(org.hibernate.Session hibernateSession) {
				try {
					Session session = sessionResource.getRuleTable().checkSessionAuthorization(principal.getToken(), sessionId, false, hibernateSession, false);
					return session != null;
				} catch (fi.csc.chipster.rest.exception.NotAuthorizedException
						|jakarta.ws.rs.NotFoundException
						|jakarta.ws.rs.ForbiddenException e) {
					return false;
				}		
			}
		});
	}
	
	private boolean isAuthorizedUserId(UserId userId, AuthPrincipal principal) {
		return principal.getName().equals(userId.toUserIdString());
	}

	@Override
	public String getMonitoringTag(String topic) {
		if (ALL_JOBS_TOPIC.equals(topic) || 
				ALL_FILES_TOPIC.equals(topic) || 
				ALL_DATASETS_TOPIC.equals(topic) || 
				ALL_AUTHORIZATIONS_TOPIC.equals(topic) || 
				ALL_SESSIONS_TOPIC.equals(topic)) {			
			
			return TOPIC_GROUP_SERVER;
		} else {
			return TOPIC_GROUP_CLIENT;
		}
	}
	
	@Override
	public List<String> getMonitoringTags() {
		return Arrays.asList(new String[] {TOPIC_GROUP_SERVER, TOPIC_GROUP_CLIENT});
	}

}
