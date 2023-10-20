package fi.csc.chipster.sessiondb;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestMethods;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.sessiondb.model.News;
import fi.csc.chipster.sessiondb.resource.NewsResource;
import fi.csc.chipster.sessiondb.resource.SessionDbAdminResource;
import jakarta.ws.rs.client.WebTarget;

public class SessionDbAdminClient {
	
	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;
	private CredentialsProvider credentials;

	private String sessionDbAdminUri;

	/**
	 * @param serviceLocator
	 * @param credentials
	 * @param role set to Role.CLIENT to use public addresses, anything else, e.g. Role.SERVER to use internal addresses 
	 */
	public SessionDbAdminClient(ServiceLocatorClient serviceLocator, CredentialsProvider credentials) {
		this.serviceLocator = serviceLocator;
		this.credentials = credentials;
		
		// get session-db, remove session-db-events
		List<Service> internalServices = serviceLocator.getInternalServices(Role.SESSION_DB).stream()
				.filter(s -> Role.SESSION_DB.equals(s.getRole()))
				.collect(Collectors.toList());
				
		sessionDbAdminUri = internalServices.get(0).getAdminUri();
	}	
	
	private WebTarget getSessionDbAdminTarget() {
		
		WebTarget target = null;
		if (credentials == null) {
			target = AuthenticationClient.getClient().target(sessionDbAdminUri); // for testing
		} else {
			target = AuthenticationClient.getClient(credentials.getUsername(), credentials.getPassword(), true).target(sessionDbAdminUri);
		}
		
		return target.path("admin");
	}
		
	// targets
	
	private WebTarget getNewsTarget() {
		return getSessionDbAdminTarget().path(NewsResource.PATH_NEWS);
	}
	
	private WebTarget getNewsTarget(UUID id) {
		return getNewsTarget().path(id.toString());
	}

	private WebTarget getUsersSessionsTarget() {
		return getSessionDbAdminTarget().path(SessionDbAdminResource.PATH_USERS_SESSIONS);
	}

	private WebTarget getUsersSessionsTarget(String username) {
		return getUsersSessionsTarget().queryParam("userId", username);
	}


	// sessions for user
	public String getSessionsForUser(String username) throws RestException {
		return RestMethods.getJson(getUsersSessionsTarget(username));
	}

	public void deleteSessionsForUser(String username) throws RestException {
		RestMethods.delete(getUsersSessionsTarget(username));
	}

	
	

	// news
	public UUID createNews(News news) throws RestException {
		UUID id =  RestMethods.post(getNewsTarget(), news);
		news.setNewsId(id);
		return id;
	}

	public void updateNews(News news) throws RestException {
		RestMethods.put(getNewsTarget(news.getNewsId()), news);
	}

	public void deleteNews(UUID newsId) throws RestException {
		RestMethods.delete(getNewsTarget(newsId));
	}	
}
