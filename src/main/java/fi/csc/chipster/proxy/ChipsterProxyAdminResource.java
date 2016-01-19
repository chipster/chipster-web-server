package fi.csc.chipster.proxy;

import java.net.URISyntaxException;
import java.util.List;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.proxy.model.Connection;
import fi.csc.chipster.proxy.model.Route;
import fi.csc.chipster.proxy.model.RouteStats;

/**
 * Management Rest API for the Chipster's proxy
 * 
 * @author klemela
 *
 */
@Path("admin")
public class ChipsterProxyAdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	private ProxyServer proxy;
	    
	public ChipsterProxyAdminResource(ProxyServer proxy) {
		this.proxy = proxy;
	}

	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.ADMIN)
	@Path("connections")
    public Response getConnections(@Context SecurityContext sc) {

		List<Connection> connections = proxy.getConnections();
		
		return Response.ok(connections).build();
    }
	
	@GET
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.ADMIN)
	@Path("routes")
    public Response getRoutes(@Context SecurityContext sc) {

		List<RouteStats> routes = proxy.getRouteStats();
		
		return Response.ok(routes).build();
    }

	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.ADMIN)
	@Path("routes")
    public Response addRoute(Route route, @Context SecurityContext sc) {
		try {
			proxy.addRoute(route.getProxyPath(), route.getProxyTo());
		} catch (URISyntaxException e) {
			throw new BadRequestException("invalid URI: " + e.getMessage());
		}
		
		return Response.ok().build();
	}
	
	@DELETE
	@RolesAllowed(Role.ADMIN)
	@Path("routes/{proxyPath}")
    public Response removeRoute(@PathParam("proxyPath") String proxyPath, @Context SecurityContext sc) {

		proxy.removeRoute(proxyPath);
		
		return Response.ok().build();
    }
}
