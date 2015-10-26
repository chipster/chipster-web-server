package fi.csc.chipster.sessiondb;

import java.util.List;
import java.util.UUID;

import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.WebTarget;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.sse.EventListener;
import org.glassfish.jersey.media.sse.InboundEvent;
import org.hibernate.service.spi.ServiceException;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AsyncEventInput;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.resource.Events;

public class SessionDbClient {
	
	public interface SessionEventListener {
		public void onEvent(SessionEvent e);
	}
	
	private static final Logger logger = LogManager.getLogger();

	@SuppressWarnings("unused")
	private ServiceLocatorClient serviceLocator;
	private List<String> sessionDbs;
	private AuthenticationClient authService;

	private AsyncEventInput eventInput;

	public SessionDbClient(ServiceLocatorClient serviceLocator, AuthenticationClient authService) {
		this.serviceLocator = serviceLocator;
		this.authService = authService;
		this.sessionDbs = serviceLocator.get(Role.SESSION_DB);
	
		if (sessionDbs.isEmpty()) {
			throw new InternalServerErrorException("no session-dbs registered to service-locator");
		}
	}

	public void addJobListener(final SessionEventListener listener) {
		
		for (String sessionDbUri : sessionDbs) {
			try {
				WebTarget target = authService.getAuthenticatedClient().target(sessionDbUri);
				this.eventInput = new AsyncEventInput(target, "jobs/events",  Events.EVENT_NAME);
				this.eventInput.getEventSource().register(new EventListener() {

					@Override
					public void onEvent(InboundEvent inboundEvent) {
						SessionEvent event = inboundEvent.readData(SessionEvent.class);
						listener.onEvent(event);
					}
					
				});
				return;
			} catch (ServiceException e) {
				logger.warn("sessionDb not available: " + sessionDbUri);
			}
		}
	}

	public Job getJob(UUID sessionId, UUID jobId) {
		for (String sessionDbUri : sessionDbs) {
			WebTarget target = authService.getAuthenticatedClient().target(sessionDbUri).path("sessions/" + sessionId + "/jobs/" + jobId);
			try {
				Job job = target.request().get(Job.class);
				return job;
			} catch (ServiceException e) {
				logger.warn("sessionDb not available: " + sessionDbUri, e);
			} catch (WebApplicationException e) {
				logger.error("failed to get the job " + target.getUri(), e);
			}
		}	
		throw new ServiceException("there isn't any sessionDbs available");
	}

	public void close() {
		//eventInput.close();
	}
}
