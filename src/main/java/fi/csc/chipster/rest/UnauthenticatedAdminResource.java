
package fi.csc.chipster.rest;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Path("admin")
public class UnauthenticatedAdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
		
	@GET
	@Path("alive")
    @Produces(MediaType.APPLICATION_JSON)
	public Response getAlive(@Context SecurityContext sc) {
		return Response.ok().build();
	}
}
