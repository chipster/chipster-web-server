package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.resource.AuthPrincipal;
import fi.csc.chipster.filestorage.FileStorageAdminClient;
import fi.csc.chipster.filestorage.FileStorageClient;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.StaticCredentials;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.File;
import fi.csc.chipster.sessiondb.model.Session;

@Path("admin")
public class FileBrokerAdminResource extends AdminResource {
		
	private Logger logger = LogManager.getLogger();

	private StorageDiscovery storageDiscovery;

	private SessionDbClient sessionDbClient;
	
	public FileBrokerAdminResource(StatusSource stats, StorageDiscovery storageDiscovery, SessionDbClient sessionDbClient) {
		super(stats);
		
		this.storageDiscovery = storageDiscovery;
		this.sessionDbClient = sessionDbClient;
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
	
	@GET
	@Path("storages/{storageId}/id")
	@Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed({Role.ADMIN})
	public Response getStorageId(@PathParam("storageId") String storageId, @Context SecurityContext sc) {
		
		String json = getStorageAdminClient(storageId, sc).getStorageId();
		
		return Response.ok(json).build();
    }
	
	@GET
	@Path("storages/{storageId}/filestats")
	@Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed({Role.ADMIN})
	public Response getFileStats(@PathParam("storageId") String storageId, @Context SecurityContext sc) {
		
		String json = getStorageAdminClient(storageId, sc).getFileStats();
		
		return Response.ok(json).build();
    }
	
	@POST
	@Path("storages/copy")
	@RolesAllowed({Role.ADMIN})
	public Response copy(
			@QueryParam("source") String sourceStorageId,
			@QueryParam("target") String targetStorageId,
			@DefaultValue("" + Long.MAX_VALUE) @QueryParam("maxBytes") long maxBytes,
			@Context SecurityContext sc) {
		
		logger.info("copy files from storage '" + sourceStorageId + "' to '" + targetStorageId);
		
		if ("null".equals(sourceStorageId)) {
			sourceStorageId = null;
		}
		
		if (maxBytes != Long.MAX_VALUE) {
			logger.info("copy max " + humanFriendly(maxBytes));
		}
		
		HashMap<UUID, File> fileMap = new HashMap<>();
		HashMap<UUID, UUID> fileSessions = new HashMap<>();
		HashMap<UUID, UUID> fileDatasets = new HashMap<>();
		
		// lot of requests and far from atomic
		try {
			for (String user : sessionDbClient.getUsers()) {
				try {
					for (Session session : sessionDbClient.getSessions(user)) {
						try {
							for (Dataset dataset : sessionDbClient.getDatasets(session.getSessionId()).values()) {
								if (dataset.getFile() != null && sourceStorageId.equals(dataset.getFile().getStorage())) {
									fileMap.put(dataset.getFile().getFileId(), dataset.getFile());
									fileSessions.put(dataset.getFile().getFileId(), session.getSessionId());
									fileDatasets.put(dataset.getFile().getFileId(), dataset.getDatasetId());
								}
							}
						} catch (RestException e) {
							logger.warn("get datasets error", e);
						}
					}
				} catch (RestException e) {
					logger.warn("get sessions error for user " + user, e);
				}
			}
		} catch (RestException e) {
			throw new InternalServerErrorException("get users failed", e);
		}
		
		logger.info("found " + fileDatasets.size() + " files");
		
		long bytesCopied = 0;
		
		for (File file : fileMap.values()) {
			
			if (bytesCopied + file.getSize() >= maxBytes) {
				logger.info("copied " + humanFriendly(bytesCopied) + " bytes, maxBytes reached");
				break;
			}
			
			logger.info("copy from '" + sourceStorageId + "' to '" + targetStorageId + "' fileId: " + file.getFileId() + ", " + humanFriendly(file.getSize())); 
			
			FileStorageClient sourceClient = storageDiscovery.getStorageClientForExistingFile(sourceStorageId);
			FileStorageClient targetClient = storageDiscovery.getStorageClient(targetStorageId);
			
			try {
				InputStream sourceStream = sourceClient.download(file.getFileId(), null);
				
				Map<String, String> queryParams = new HashMap<>() {{ put(FileBrokerResource.FLOW_TOTAL_SIZE, "" + file.getSize()); }};
				long fileLength = targetClient.upload(file.getFileId(), sourceStream, queryParams );
				
				if (fileLength != file.getSize()) {
					logger.warn("file length error. storageId '" + sourceStorageId + "', fileId " + file.getFileId() + ", copied: " + fileLength + " bytes, but size in DB is " + file.getSize() + ". Keeping both.");
					continue;
				}
				
				file.setSize(fileLength);
				file.setStorage(targetStorageId);			
				
				UUID sessionId = fileSessions.get(file.getFileId());
				UUID datasetId = fileDatasets.get(file.getFileId());
			
				Dataset dataset = sessionDbClient.getDataset(sessionId, datasetId);
				dataset.setFile(file);
			
				/* File can be in several sessions, but updating it in one of them is enough.
				 * Other sessions won't get events, but that shouldn't matter, because the file-broker
				 * will get the storage from the session-db anyway.
				 */
				sessionDbClient.updateDataset(sessionId, dataset);			
				storageDiscovery.getStorageClient(sourceStorageId).delete(file.getFileId());
				
				bytesCopied += fileLength;			
			} catch (RestException e) {
				logger.error("file copy failed", e);
			}
		}
		
		logger.info("copy completed");
		
		return Response.ok().build();
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
	
	public static String humanFriendly(Long l) {
		if (l == null) {
			return null;
		}
		
		if (l >= 1024l * 1024 * 1024 * 1024) {			
			return "" + l/1024/1024/1024/1024 + " TB";
		}
		if (l >= 1024l * 1024 * 1024) {			
			return "" + l/1024/1024/1024 + " GB";
		}
		if (l >= 1024l * 1024) {			
			return "" + l/1024/1024 + " MB";
		}
		if (l >= 1024l) {			
			return "" + l/1024 + " kB";
		}
		return "" + l + " B";
	}
}
