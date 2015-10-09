package fi.csc.chipster.servicelocator.resource;

import java.net.URI;
import java.util.Collection;
import java.util.logging.Logger;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.sessiondb.resource.Events;

@Path("services")
public class ServiceResource {
	
	public static final String SERVICES = "services";
	
	@SuppressWarnings("unused")
	private static Logger logger = Logger.getLogger(ServiceResource.class.getName());
//	private Events events;
	private ServiceCatalog serviceCatalog;
	
	public ServiceResource(ServiceCatalog serviceCatalog, Events events) {
		this.serviceCatalog = serviceCatalog;
//		this.events = events;
	}

//	// notifications
//    @GET
//    @Path("{id}/events")
//    @Produces(SseFeature.SERVER_SENT_EVENTS)
//    @Transaction
//    public EventOutput listenToBroadcast(@PathParam("id") String sessionId, @Context SecurityContext sc) {
//    	// checks authorization
//    	getReadAuthorization(sc, sessionId);
//        return events.getEventOutput(sessionId);
//    }
	
    // CRUD
        
	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.UNAUTHENTICATED)
    public Response getAll(@Context SecurityContext sc) {

		Collection<Service> services = serviceCatalog.getAll();
		
		// if nothing is found, just return 200 (OK) and an empty list
		return Response.ok(toJaxbList(services)).build();
    }	

	@POST
	@RolesAllowed(Role.SESSION_STORAGE)
    @Consumes(MediaType.APPLICATION_JSON)
    public Response post(Service service, @Context UriInfo uriInfo, @Context SecurityContext sc) {
		
		// curl -i -H "Content-Type: application/json" -X POST http://localhost:8082/servicelocator/services -d '{"serviceId":"67a2ae8a-8e87-4a87-89f7-d8649d219fcc","role":"SESSION_STORAGE","uri":"http://localhost:8080/sessionstorage"}'
		
		//service = RestUtils.getRandomService();
		
		String serviceId = serviceCatalog.add(Role.SESSION_STORAGE, service);
		
		URI uri = uriInfo.getAbsolutePathBuilder().path(serviceId).build();
		
		return Response.created(uri).build();
    }

	@DELETE
	@RolesAllowed(Role.SESSION_STORAGE)
    @Path("{id}")
    public Response delete(@PathParam("id") String id, @Context SecurityContext sc) {

		serviceCatalog.remove(id);
		
		return Response.noContent().build();
    }
    
    /**
	 * Make a list compatible with JSON conversion
	 * 
	 * Default Java collections don't define the XmlRootElement annotation and 
	 * thus can't be converted directly to JSON. 
	 * @param <T>
	 * 
	 * @param result
	 * @return
	 */
	private GenericEntity<Collection<Service>> toJaxbList(Collection<Service> result) {
		return new GenericEntity<Collection<Service>>(result) {};
	}

	public ServiceCatalog getServiceCatalog() {
		return serviceCatalog;
	}
}
