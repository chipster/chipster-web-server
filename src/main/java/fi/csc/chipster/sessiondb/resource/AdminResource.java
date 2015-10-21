package fi.csc.chipster.sessiondb.resource;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.jersey.media.sse.EventOutput;
import org.glassfish.jersey.media.sse.SseFeature;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

@Path("/")
public class AdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private Events events;
	
	public AdminResource(Events events) {
		this.events = events;
	}
	
	// notifications
    @GET
    @Path("jobs/events")
    @RolesAllowed(Role.SERVER)
    @Produces(SseFeature.SERVER_SENT_EVENTS)
    @Transaction
    public EventOutput listenToBroadcast(@Context SecurityContext sc) {
        return events.getEventOutput(ResourceType.JOB);
    }
}
