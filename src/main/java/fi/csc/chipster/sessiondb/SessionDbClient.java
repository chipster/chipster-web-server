package fi.csc.chipster.sessiondb;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import javax.websocket.MessageHandler.Whole;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.ServiceUnavailableException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.WebTarget;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.service.spi.ServiceException;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.websocket.WebSocketClient;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketClosedException;
import fi.csc.chipster.rest.websocket.WebSocketClient.WebSocketErrorException;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;

public class SessionDbClient {
	
	public interface SessionEventListener {
		void onEvent(SessionEvent e);
	}
	
	private static final Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;
	private List<String> sessionDbList;
	private List<String> sessionDbEventsList;
	private AuthenticationClient authService;

	private WebSocketClient client;


	public SessionDbClient(ServiceLocatorClient serviceLocator, AuthenticationClient authService) {
		this.serviceLocator = serviceLocator;
		this.authService = authService;
		
		this.sessionDbList = serviceLocator.get(Role.SESSION_DB);
		this.sessionDbEventsList = serviceLocator.get(Role.SESSION_DB_EVENTS);
	
		if (sessionDbList.isEmpty()) {
			throw new InternalServerErrorException("no session-dbs registered to service-locator");
		}
	}

	public void addJobListener(final SessionEventListener listener) throws InterruptedException {
		
		for (String sessionDbEventsUri : sessionDbEventsList) {
			try {
				this.client = new WebSocketClient(sessionDbEventsUri + SessionDb.EVENTS_PATH + "/" + SessionDb.JOBS_TOPIC + "?token=" + authService.getToken().toString(), new Whole<String>() {

					@Override
					public void onMessage(String message) {
						listener.onEvent(RestUtils.parseJson(SessionEvent.class, message));
					}
					
				}, true, "scheduler-job-listener"); 
				return;
			} catch (WebSocketErrorException | WebSocketClosedException e) {
				logger.warn("session-db-events not available: " + sessionDbEventsUri);
			}
		}
	}

	public Job getJob(UUID sessionId, UUID jobId) {
		for (String sessionDbUri : sessionDbList) {
			WebTarget target = authService.getAuthenticatedClient().target(sessionDbUri).path("sessions/" + sessionId + "/jobs/" + jobId);
			try {
				Job job = target.request().get(Job.class);
				return job;
			} catch (ServiceUnavailableException e) {
				logger.warn("session-db not available: " + sessionDbUri, e);
			} catch (WebApplicationException e) {
				logger.error("failed to get the job " + target.getUri() + " " + e.getClass().getName() + ": " + e.getMessage());
			}
		}	
		throw new ServiceException("there isn't any sessionDbs available");
	}

	public void close() throws IOException {
		if (this.client != null) {
			client.shutdown();
		}
	}
}
