package fi.csc.chipster.filestorage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.StorageAdminClient;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbAdminClient;
import fi.csc.chipster.sessiondb.model.FileState;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.SecurityContext;

/**
 * Rest API resource for admin functions of file-storage component
 */
public class FileStorageAdminResource extends AdminResource {

	private static final String PATH_ORPHAN = "orphan";

	private Logger logger = LogManager.getLogger();
	private FileStorageBackup backup;
	private SessionDbAdminClient sessionDbAdminClient;
	private File storage;

	private java.nio.file.Path orphanRootPath;

	private String storageId;

	public FileStorageAdminResource(StatusSource stats, FileStorageBackup backup,
			SessionDbAdminClient sessionDbAdminClient,
			File storage, String storageId) {
		super(stats, backup);

		this.backup = backup;
		this.sessionDbAdminClient = sessionDbAdminClient;
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
	@RolesAllowed({ Role.ADMIN })
	public Response getStorageId(@Context SecurityContext sc) {
		HashMap<String, Object> jsonMap = new HashMap<>();
		jsonMap.put("storageId", storageId);
		return Response.ok(RestUtils.asJson(jsonMap)).build();
	}

	@GET
	@Path("filestats")
	@RolesAllowed({ Role.ADMIN })
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
	@RolesAllowed({ Role.ADMIN })
	public Response startBackup(@Context SecurityContext sc) {

		backup.backupNow();

		return Response.ok().build();
	}

	@POST
	@Path("check")
	@RolesAllowed({ Role.ADMIN })
	public Response startCheck(@QueryParam("uploadMaxHours") Long uploadMaxHours,
			@QueryParam("deleteDatasetsOfMissingFiles") Boolean deleteDatasetsOfMissingFiles,
			@Context SecurityContext sc) {

		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					check(storage, uploadMaxHours, deleteDatasetsOfMissingFiles);
				} catch (RestException | IOException e) {
					logger.error("storage check error", e);
				}
			}

		}).start();

		return Response.ok().build();
	}

	@POST
	@Path("delete-orphans")
	@RolesAllowed({ Role.ADMIN })
	public Response deleteOldOrphans(@Context SecurityContext sc) throws IOException {

		HashMap<String, Long> oldOrphanFiles = getFilesAndSizes(orphanRootPath, null);
		long oldOrphanFilesTotal = oldOrphanFiles.values().stream().mapToLong(s -> s).sum();
		logger.info("delete " + oldOrphanFiles.size() + " old orphan files ("
				+ FileUtils.byteCountToDisplaySize(oldOrphanFilesTotal) + ")");

		if (Files.exists(orphanRootPath)) {
			FileUtils.deleteDirectory(orphanRootPath.toFile());
		}
		logger.info("delete " + oldOrphanFiles.size() + " old orphan files done");

		return Response.ok().build();
	}

	@DELETE
	@Path("backup/schedule")
	@RolesAllowed({ Role.ADMIN })
	public Response disableBackups(@Context SecurityContext sc) throws IOException {

		this.backup.disable();

		return Response.ok().build();
	}

	@POST
	@Path("backup/schedule")
	@RolesAllowed({ Role.ADMIN })
	public Response enableBackups(@Context SecurityContext sc) throws IOException {

		this.backup.enableSchedule();

		return Response.ok().build();
	}

	private void check(File storage, Long uploadMaxHours, Boolean deleteDatasetsOfMissingFiles)
			throws RestException, IOException {

		logger.info("storage check started");

		Files.createDirectories(orphanRootPath);

		// collect storage files first to make sure we don't delete new files
		Map<String, Long> storageFiles = getFilesAndSizes(storage.toPath(), orphanRootPath);
		Map<String, Long> oldOrphanFiles = getFilesAndSizes(orphanRootPath, null);

		List<fi.csc.chipster.sessiondb.model.File> completeDbFiles = this.sessionDbAdminClient.getFiles(storageId,
				FileState.COMPLETE);
		List<fi.csc.chipster.sessiondb.model.File> uploadingDbFiles = this.sessionDbAdminClient.getFiles(storageId,
				FileState.UPLOADING);

		Set<fi.csc.chipster.sessiondb.model.File> oldUploads = StorageAdminClient.deleteOldUploads(uploadingDbFiles,
				this.sessionDbAdminClient,
				uploadMaxHours);

		uploadingDbFiles.removeAll(oldUploads);

		Map<String, fi.csc.chipster.sessiondb.model.File> completeDbFilesMap = completeDbFiles.stream()
				.collect(Collectors.toMap(f -> f.getFileId().toString(), f -> f));

		Map<String, fi.csc.chipster.sessiondb.model.File> uploadingDbFilesMap = uploadingDbFiles.stream()
				.collect(Collectors.toMap(f -> f.getFileId().toString(), f -> f));

		List<String> orphanFiles = StorageAdminClient.check(storageFiles, oldOrphanFiles, uploadingDbFilesMap,
				completeDbFilesMap, deleteDatasetsOfMissingFiles, sessionDbAdminClient);

		moveOrphanFiles(orphanFiles);

		logger.info(oldOrphanFiles.size() + " old orphan files are in  in " + orphanRootPath);
		logger.info(orphanFiles.size() + " orphan files moved to " + orphanRootPath);
	}

	private void moveOrphanFiles(List<String> orphanFiles) {
		for (String fileName : orphanFiles) {
			try {
				UUID fileId = UUID.fromString(fileName);
				java.nio.file.Path storageFilePath = FileServlet.getStoragePath(storage.toPath(), fileId);
				java.nio.file.Path orphanFilePath = FileServlet.getStoragePath(orphanRootPath, fileId);

				Files.createDirectories(orphanFilePath.getParent());
				Files.move(storageFilePath, orphanFilePath);
			} catch (IllegalArgumentException e) {
				logger.warn("orpah file " + fileName + " in storage is not valid UUID (" + e.getClass().getName() + " "
						+ e.getMessage() + ")");

			} catch (IOException e) {
				logger.info("couldn't move orphan file " + fileName, e);
			}
		}
	}

	private HashMap<String, Long> getFilesAndSizes(java.nio.file.Path root, java.nio.file.Path excludePath)
			throws IOException {

		HashMap<String, Long> result = new HashMap<>();

		if (Files.exists(root)) {
			try (Stream<java.nio.file.Path> rootStream = Files.list(root)) {

				rootStream.filter(path -> excludePath == null || !path.startsWith(excludePath)).forEach(partition -> {

					try (Stream<java.nio.file.Path> partitionStream = Files.list(partition)) {
						partitionStream.forEach(path -> {
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
			}
		} else {
			logger.warn("dir " + root + " not found");
		}

		return result;
	}
}
