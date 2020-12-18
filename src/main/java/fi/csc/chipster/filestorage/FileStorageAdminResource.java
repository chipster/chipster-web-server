package fi.csc.chipster.filestorage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.SecurityContext;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;

public class FileStorageAdminResource extends AdminResource {
	

	private static final String PATH_ORPHAN = "orphan";
	
	private Logger logger = LogManager.getLogger();
	private StorageBackup backup;
	private SessionDbClient sessionDbClient;
	private File storage;

	private java.nio.file.Path orphanRootPath;

	private String storageId;
	
	public FileStorageAdminResource(StatusSource stats, StorageBackup backup, SessionDbClient sessionDbClient, File storage, String storageId) {
		super(stats, backup);
		
		this.backup = backup;
		this.sessionDbClient = sessionDbClient;
		this.storage = storage;
		this.storageId = storageId;
		
		orphanRootPath = storage.toPath().resolve(PATH_ORPHAN);
	}
	
	// unauthenticated but firewalled monitoring tap
	@GET
	@Path("monitoring/backup")
    @Produces(MediaType.APPLICATION_JSON)
	public Response backupMonitoring(@Context SecurityContext sc) {
		
		if (!backup.monitoringCheck()) {
			return Response.status(Status.NOT_FOUND.getStatusCode(), "backup of " + Role.FILE_BROKER).build();		
		}
		return Response.ok().build();
	}

	@GET
	@Path("id")
	@RolesAllowed({Role.ADMIN})
	public Response getStorageId(@Context SecurityContext sc) {		
		HashMap<String, Object> jsonMap = new HashMap<>();
		jsonMap.put("storageId", storageId);
		return Response.ok(RestUtils.asJson(jsonMap)).build();
    }
	
	@GET
	@Path("filestats")
	@RolesAllowed({Role.ADMIN})
	public Response getFileStats(@Context SecurityContext sc) throws IOException {		
		
		HashMap<String, Long> files = getFilesAndSizes(storage.toPath(), orphanRootPath);
		long fileBytes = files.values().stream()
			.mapToLong(l -> l).sum();
		
		HashMap<String, Object> jsonMap = new HashMap<>();
		jsonMap.put("storageId", storageId);
		jsonMap.put("fileCount", files.size());
		jsonMap.put("fileBytes", fileBytes);
		jsonMap.put("status", this.backup.getStatusString());
		
		return Response.ok(RestUtils.asJson(jsonMap)).build();
    }
	
	@POST
	@Path("backup")
	@RolesAllowed({Role.ADMIN})
	public Response startBackup(@Context SecurityContext sc) {
		
		backup.backupNow();
		
		return Response.ok().build();
    }
	
	@POST
	@Path("check")
	@RolesAllowed({Role.ADMIN})
	public Response startCheck(@Context SecurityContext sc) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					check(sessionDbClient, storage);
				} catch (RestException | IOException e) {
					logger.error("storage check error", e);
				}
			}

		}).start();
		
		return Response.ok().build();
    }
	
	@POST
	@Path("delete-orphans")
	@RolesAllowed({Role.ADMIN})
	public Response deleteOldOrphans(@Context SecurityContext sc) throws IOException {
		
			HashMap<String, Long> oldOrphanFiles = getFilesAndSizes(orphanRootPath, null);
			long oldOrphanFilesTotal = oldOrphanFiles.values().stream().mapToLong(s -> s).sum();
			logger.info("delete " + oldOrphanFiles.size() + " old orphan files (" + FileUtils.byteCountToDisplaySize(oldOrphanFilesTotal) + ")");
			
			if (Files.exists(orphanRootPath)) {
				FileUtils.deleteDirectory(orphanRootPath.toFile());
			}
			logger.info("delete " + oldOrphanFiles.size() + " old orphan files done");
		
		return Response.ok().build();
    }
	
	@DELETE
	@Path("backup/schedule")
	@RolesAllowed({Role.ADMIN})
	public Response disableBackups(@Context SecurityContext sc) throws IOException {
		
		this.backup.disable();
				
		return Response.ok().build();
    }
	
	@POST
	@Path("backup/schedule")
	@RolesAllowed({Role.ADMIN})
	public Response enableBackups(@Context SecurityContext sc) throws IOException {
		
		this.backup.enableSchedule();
				
		return Response.ok().build();
    }
	
	private void check(SessionDbClient sessionDbClient, File storage) throws RestException, IOException {
		
		logger.info("storage check started");
		
		HashMap<String, Long> dbFiles = new HashMap<>();
				
		Files.createDirectories(orphanRootPath);		
		
		// collect storage files first to make sure we don't delete new files
		HashMap<String, Long> storageFiles = getFilesAndSizes(storage.toPath(), orphanRootPath);
		HashMap<String, Long> oldOrphanFiles = getFilesAndSizes(orphanRootPath, null);		
		
		// lot of requests and far from atomic
		for (String user : sessionDbClient.getUsers()) {
			if (user == null) {
				logger.info("skip user 'null'");
				continue;
			}
			for (Session session : sessionDbClient.getSessions(user)) {
				for (Dataset dataset : sessionDbClient.getDatasets(session.getSessionId()).values()) {
					if (dataset.getFile() != null) {
						if (
								("null".equals(storageId) && dataset.getFile().getStorage() == null) ||
								(storageId.equals(dataset.getFile().getStorage()))) {
							
							dbFiles.put(dataset.getFile().getFileId().toString(), dataset.getFile().getSize());
						}
					}
				}
			}
		}
		
		List<String> correctNameFiles = new HashSet<>(dbFiles.keySet()).stream()
		.filter(fileName -> storageFiles.containsKey(fileName))
		.collect(Collectors.toList());
		
		List<String> correctSizeFiles = correctNameFiles.stream()
		.filter(fileName -> (long)storageFiles.get(fileName) == (long)dbFiles.get(fileName))
		.collect(Collectors.toList());
				
		List<String> wrongSizeFiles = correctNameFiles.stream()
		.filter(fileName -> (long)storageFiles.get(fileName) != (long)dbFiles.get(fileName))
		.collect(Collectors.toList());
						
		// why the second iteration without the new HashSet throws a call site initialization exception?
		List<String> missingFiles = new HashSet<>(dbFiles.keySet()).stream()
		.filter(fileName -> !storageFiles.containsKey(fileName))
		.collect(Collectors.toList());
				
		List<String> orphanFiles = new HashSet<>(storageFiles.keySet()).stream()
		.filter(fileName -> !dbFiles.containsKey(fileName))
		.collect(Collectors.toList());
		
		for (String fileName : wrongSizeFiles) {
			logger.info("wrong size " + fileName + ", db: " + dbFiles.get(fileName) + ", file: " + dbFiles.get(fileName));
		}
		
		for (String fileName : missingFiles) {
			logger.info("missing file " + fileName + ", db: " + dbFiles.get(fileName));
		}
		
		for (String fileName : orphanFiles) {
			try {				
				UUID fileId = UUID.fromString(fileName);
				java.nio.file.Path storageFilePath = FileServlet.getStoragePath(storage.toPath(), fileId);
				java.nio.file.Path orphanFilePath = FileServlet.getStoragePath(orphanRootPath, fileId);
								
				Files.createDirectories(orphanFilePath.getParent());
				Files.move(storageFilePath, orphanFilePath);
			} catch (IllegalArgumentException e) {
				logger.warn("orpah file " + fileName + " in storage is not valid UUID (" + e.getClass().getName() + " " + e.getMessage() + ")");
			
			} catch (IOException e) {				
				logger.info("couldn't move orphan file " + fileName, e);
			}
		}
		
		long correctSizeTotal = correctSizeFiles.stream().mapToLong(dbFiles::get).sum();
		long wrongSizeTotal = wrongSizeFiles.stream().mapToLong(dbFiles::get).sum();
		long missingFilesTotal = missingFiles.stream().mapToLong(dbFiles::get).sum();
		long orphanFilesTotal = orphanFiles.stream().mapToLong(storageFiles::get).sum();
		long oldOrphanFilesTotal = oldOrphanFiles.values().stream().mapToLong(s -> s).sum();
		
		logger.info(correctSizeFiles.size() + " files (" + FileUtils.byteCountToDisplaySize(correctSizeTotal) + ") are fine");
		logger.info(wrongSizeFiles.size() + " files (" + FileUtils.byteCountToDisplaySize(wrongSizeTotal) + ") have wrong size");
		logger.info(missingFiles.size() + " missing files or created during this check (" + FileUtils.byteCountToDisplaySize(missingFilesTotal) + ")");
		logger.info(oldOrphanFiles.size() + " old orphan files (" + FileUtils.byteCountToDisplaySize(oldOrphanFilesTotal) + ") in " + orphanRootPath);
		logger.info(orphanFiles.size() + " orphan files (" + FileUtils.byteCountToDisplaySize(orphanFilesTotal) + ") moved to " + orphanRootPath);
	}

	private HashMap<String, Long> getFilesAndSizes(java.nio.file.Path root, java.nio.file.Path excludePath) throws IOException {
		
		HashMap<String, Long> result = new HashMap<>();
		
		if (Files.exists(root)) {
			Files.list(root)
			.filter(path -> excludePath == null || !path.startsWith(excludePath))
			.forEach(partition -> {
				try {
					Files.list(partition)
					.forEach(path -> {
						String fileName = path.getFileName().toString();
						Long size;
						try {
							size = Files.size(path);
						} catch (IOException e) {
							throw new RuntimeException(e);		
						}
						result.put(fileName, size);
					});
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
			});
		} else {
			logger.warn("dir " + root +  " not found");
		}
		
		return result;
	}			
}
