package fi.csc.chipster.servicelocator.resource;

import java.util.ArrayList;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

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
	@RolesAllowed({Role.ADMIN, Role.SERVER})
    public Response getAll(@Context SecurityContext sc) {
		return Response.ok(allServices).build();
	}
}
