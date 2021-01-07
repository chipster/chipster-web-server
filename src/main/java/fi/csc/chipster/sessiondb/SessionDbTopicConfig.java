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
	
	public static final String JOBS_TOPIC = "jobs";
	public static final String FILES_TOPIC = "files";
	public static final String AUTHORIZATIONS_TOPIC = "authorizations";
	public static final String DATASETS_TOPIC = "datasets";
	public static final String SESSIONS_TOPIC = "sessions";

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
		
		if (JOBS_TOPIC.equals(topic) || FILES_TOPIC.equals(topic)) {
			return principal.getRoles().contains(Role.SERVER);
			
		} else if (DATASETS_TOPIC.equals(topic) || AUTHORIZATIONS_TOPIC.equals(topic) || SESSIONS_TOPIC.equals(topic)) {
			return principal.getRoles().contains(Role.SESSION_DB);
			
		} else {  
			if (isUUID(topic)) {
				final UUID sessionId = UUID.fromString(topic);
				return isAuthorizedSessionId(sessionId, principal);
			} else {
				UserId userId = new UserId(topic);
				return isAuthorizedUserId(userId, principal);
			}
		} 
	}
	
	private boolean isUUID(String uuid) {
		try {
			UUID.fromString(uuid);
			return true;	
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
	
	private boolean isAuthorizedSessionId(UUID sessionId, AuthPrincipal principal) {
		return hibernate.runInTransaction(new HibernateRunnable<Boolean>() {
			@Override
			public Boolean run(org.hibernate.Session hibernateSession) {
				try {
					Session session = sessionResource.getRuleTable().checkAuthorization(principal, sessionId, false, hibernateSession, false);
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
		if (JOBS_TOPIC.equals(topic) || 
				FILES_TOPIC.equals(topic) || 
				DATASETS_TOPIC.equals(topic) || 
				AUTHORIZATIONS_TOPIC.equals(topic) || 
				SESSIONS_TOPIC.equals(topic)) {			
			
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
