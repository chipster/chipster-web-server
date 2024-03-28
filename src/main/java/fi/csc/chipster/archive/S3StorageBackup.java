package fi.csc.chipster.archive;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.s3.model.S3ObjectSummary;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.S3Util;
import fi.csc.chipster.s3storage.client.S3StorageClient;

public class S3StorageBackup {

	private static final String KEY_BACKUP_DONE = "backup-done.json";

	private Logger logger = LogManager.getLogger();

	private Config config;
	private S3StorageClient s3StorageClient;

	public S3StorageBackup() throws NoSuchAlgorithmException {

		this.config = new Config();
		this.s3StorageClient = new S3StorageClient(config);
	}

	private void archiveAndCleanUp(String role) {

		int dayCount = 60;

		List<String> storageIds = Arrays.asList(s3StorageClient.getStorages()).stream()
				.map(fs -> fs.getStorageId())
				.collect(Collectors.toList());

		Path archiveRootPath = Paths.get("s3-backup");
		archiveRootPath.toFile().mkdir();

		for (String storageId : storageIds) {
			try {
				logger.info("backup " + storageId);
				archive(archiveRootPath, storageId, role);
				BackupArchive.removeOldIncrementalArchives(archiveRootPath, storageId + "_", dayCount);

			} catch (Exception e) {
				logger.error("s3 backup failed: " + storageId, e);
			}

		}
		logger.info("s3 backups done");
	}

	private void archive(Path archiveRootPath, String storageId, String role) throws IOException, InterruptedException {

		logger.info("list files of " + storageId + " from S3");

		String bucket = s3StorageClient.storageIdToBucket(storageId);
		List<S3ObjectSummary> s3Objects = S3Util.getObjects(s3StorageClient.getTransferManager(), bucket);

		List<Path> dirs = Files.list(archiveRootPath)
				.filter(path -> path.getFileName().toString().startsWith(storageId))
				.collect(Collectors.toList());

		Map<String, Path> filesMap = new HashMap<>();

		for (Path dir : dirs) {
			Files.list(dir)
					.forEach(path -> filesMap.put(path.getFileName().toString(), path));
		}

		Map<String, String> report = new LinkedHashMap<>();

		Instant startTime = Instant.now();

		Path currentBackup = archiveRootPath.resolve(storageId + "_" + startTime);
		Path downloadDir = currentBackup.resolve("download");
		currentBackup.toFile().mkdir();
		downloadDir.toFile().mkdir();

		Set<String> filesToMove = new HashSet<>();
		Set<String> filesToDownload = new HashSet<>();

		for (S3ObjectSummary s3Object : s3Objects) {
			String objectKey = s3Object.getKey();

			if (filesMap.containsKey(objectKey)) {
				filesToMove.add(objectKey);
			} else {
				filesToDownload.add(objectKey);
			}
		}

		report.put("startTime", "" + startTime);
		report.put("filesInS3", "" + s3Objects.size());
		report.put("filesInBackup", "" + filesMap.size());
		report.put("filesToDownload", "" + filesToDownload.size());
		report.put("filesToMove", "" + filesToMove.size());

		// write to log
		for (String key : report.keySet()) {
			logger.info(key + ": " + report.get(key));
		}

		for (String file : filesToDownload) {

			// download to separate path so that it's easier to clean-up partial downloads
			Path downloadPath = downloadDir.resolve(file);
			Path target = currentBackup.resolve(file);

			logger.info("download " + bucket + "/" + file);

			try (InputStream is = this.s3StorageClient.download(bucket, file, null, null);
					FileOutputStream fos = new FileOutputStream(downloadPath.toFile())) {

				IOUtils.copy(is, fos);
				Files.move(downloadPath, target);
			}
		}

		for (String file : filesToMove) {

			Path source = filesMap.get(file);
			Path target = currentBackup.resolve(file);

			logger.info("move from " + source);

			Files.move(source, target);
		}

		report.put("endTime", "" + Instant.now());

		String reportJson = RestUtils.asJson(report);
		try (InputStream reportStream = new ByteArrayInputStream(reportJson.getBytes())) {
			s3StorageClient.upload(bucket, reportStream, KEY_BACKUP_DONE, reportJson.length());
		}

		Files.delete(downloadDir);

		logger.info("s3 backup done: " + storageId);
	}

	public static void main(String[] args) throws NoSuchAlgorithmException {
		S3StorageBackup backup = new S3StorageBackup();

		backup.archiveAndCleanUp(Role.S3_STORAGE);

		backup.s3StorageClient.getTransferManager().shutdownNow();
	}
}
