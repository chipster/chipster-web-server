package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.net.URI;

import javax.annotation.security.RolesAllowed;
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

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.filestorage.FileStorageAdminClient;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("admin")
public class FileBrokerAdminResource extends AdminResource {
	
	
	@SuppressWarnings("unused")
	private Logger logger = LogManager.getLogger();

	private StorageDiscovery storageDiscovery;
	
	public FileBrokerAdminResource(StatusSource stats, StorageDiscovery storageDiscovery) {
		super(stats);
		
		this.storageDiscovery = storageDiscovery;
	}
	
	@GET
	@Path("storages")
	@RolesAllowed({Role.ADMIN})
	@Produces(MediaType.APPLICATION_JSON)
	public Response getStorages(@Context SecurityContext sc) {

		Storage[] storages = storageDiscovery.getStorages().values().toArray(new Storage[0]);
		
		return Response.ok(storages).build();
    }
	
	private FileStorageAdminClient getStorageAdminClient(String id, SecurityContext sc) {
		
		Storage storage = storageDiscovery.getStorages().get(id);
		
		if (storage == null) {
			throw new NotFoundException("storage " + id + " not found");
		}
		
		URI url = storageDiscovery.getStorages().get(id).getAdminUri();
		
		if (url == null) {
			throw new NotFoundException("storage " + id + " has no admin address");
		}
		
		String tokenKey = ((AuthPrincipal)sc.getUserPrincipal()).getTokenKey();
		
		return new FileStorageAdminClient(url, new StaticCredentials("token", tokenKey));
	}
	
	// unauthenticated but firewalled monitoring tap
	@GET
	@Path("storages/{storageId}/monitoring/backup")
    @Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response backupMonitoring(@PathParam("storageId") String storageId, @Context SecurityContext sc) {
		
		getStorageAdminClient(storageId, sc).checkBackup();
		
		return Response.ok().build();
	}
	
	@GET
	@Path("storages/{storageId}/status")
	@Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed({Role.ADMIN})
	public Response getBackup(@PathParam("storageId") String storageId, @Context SecurityContext sc) {
		
		String json = getStorageAdminClient(storageId, sc).getStatus();
		
		return Response.ok(json).build();
    }

	@POST
	@Path("storages/{storageId}/backup")
	@RolesAllowed({Role.ADMIN})
	public Response startBackup(@PathParam("storageId") String storageId, @Context SecurityContext sc) {
		
		getStorageAdminClient(storageId, sc).startBackup();
		
		return Response.ok().build();
    }
	
	@POST
	@Path("storages/{storageId}/check")
	@RolesAllowed({Role.ADMIN})
	public Response startCheck(@PathParam("storageId") String storageId, @Context SecurityContext sc) {
		
		getStorageAdminClient(storageId, sc).startCheck();
		
		return Response.ok().build();
    }
	
	@POST
	@Path("storages/{storageId}/delete-orphans")
	@RolesAllowed({Role.ADMIN})
	public Response deleteOldOrphans(@PathParam("storageId") String storageId, @Context SecurityContext sc) throws IOException {
		
		getStorageAdminClient(storageId, sc).deleteOldOrphans();
		
		return Response.ok().build();
    }			
}
