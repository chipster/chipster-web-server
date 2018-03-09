package fi.csc.chipster.servicelocator.resource;

import java.util.ArrayList;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;

@Path(ServiceResource.PATH_SERVICES)
public class ServiceResource {
	
	public static final String PATH_SERVICES = "services";
	public static final String PATH_INTERNAL = "internal";
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private ArrayList<Service> publicServices;
	private ArrayList<Service> allServices;	
	
	public ServiceResource(ArrayList<Service> publicServices, ArrayList<Service> allServices) {
		this.publicServices = publicServices;
		this.allServices = allServices;
	}

	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.UNAUTHENTICATED)
    public Response getPublic(@Context SecurityContext sc) {
		return Response.ok(publicServices).build();
	}
	
	@GET
	@Path(PATH_INTERNAL)
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed({Role.ADMIN, Role.SERVER, Role.SSO})
    public Response getAll(@Context SecurityContext sc) {
		return Response.ok(allServices).build();
	}
}
