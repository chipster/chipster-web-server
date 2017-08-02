package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Authorization;
import fi.csc.chipster.sessiondb.model.Session;

@Path("authorizations")
public class AuthorizationResource {
	private UUID sessionId;
	private AuthorizationTable authorizationTable;
	private HibernateUtil hibernate;

	public AuthorizationResource(SessionResource sessionResource, UUID id, AuthorizationTable authorizationTable) {
		this.sessionId = id;
		this.authorizationTable = authorizationTable;
		this.hibernate = sessionResource.getHibernate();
	}

	@GET
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.SESSION_DB)
    @Transaction
    public Response get(@PathParam("id") UUID authorizationId, @Context SecurityContext sc) throws IOException {
    	    
		Authorization result = authorizationTable.getAuthorization(authorizationId, hibernate.session());
    	if (result == null) {
    		throw new NotFoundException();
    	}	
    	return Response.ok(result).build();    	
    }

    
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Transaction
    public Response getBySession(@Context SecurityContext sc) {
    	    	
		authorizationTable.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, false);    
    	List<Authorization> authorizations = authorizationTable.getAuthorizations(sessionId);    	
    	return Response.ok(authorizations).build();	       
    }
    
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Transaction
    public Response post(Authorization newAuthorization, @Context UriInfo uriInfo, @Context SecurityContext sc) {
    	
		if (newAuthorization.getAuthorizationId() != null) {
			throw new BadRequestException("authorization already has an id, post not allowed");
		}
    	
		Session session = authorizationTable.getSessionForWriting(sc, sessionId);

		newAuthorization.setAuthorizationId(RestUtils.createUUID());    	    	
		
		// make sure a hostile client doesn't set the session
    	newAuthorization.setSession(session);
    	newAuthorization.setAuthorizedBy(sc.getUserPrincipal().getName());
    	
    	authorizationTable.save(newAuthorization, hibernate.session());
    
    	URI uri = uriInfo.getAbsolutePathBuilder().path(newAuthorization.getAuthorizationId().toString()).build();
    	
    	return Response.created(uri).build();
    }
    
    @DELETE
    @Path("{id}")
    @Transaction
    public Response delete(@PathParam("id") UUID authorizationId, @Context SecurityContext sc) {
    	
    	Authorization authorizationToDelete = authorizationTable.getAuthorization(authorizationId, hibernate.session());
    	
    	authorizationTable.checkAuthorization(sc.getUserPrincipal().getName(), sessionId, true);
    	    	
    	authorizationTable.delete(sessionId, authorizationToDelete, hibernate.session());    	
    
    	return Response.noContent().build();
    }
}
