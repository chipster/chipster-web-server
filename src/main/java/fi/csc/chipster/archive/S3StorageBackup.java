package fi.csc.chipster.archive;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyManagementException;
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

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.S3Util;
import fi.csc.chipster.s3storage.client.S3StorageClient;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * Backup s3-storage
 * 
 * This aims to get the similar end result, what FileStorageBackup and
 * BackupArchive do together, i.e. create a copy of the files in Chipster and
 * keep deleted files available for 60 days or so.
 * 
 * With s3-storage this is much more simpler, because the files are already
 * encrypted in S3, so we don't need to encrypt them with GPG. Because he files
 * are stored already in S3, we don't have to upload them. FileStorageBackup
 * and BackupArchive need file listings to achieve incremental copies, but here
 * we can get the file listing directly from S3.
 * 
 * Basically we only have to do the part of BackupArchive, i.e. download files
 * and organize them in folders. Backup copies are stored on a
 * filesystem.
 * 
 * The encryption is different from GPG. Use Decrypt.java to decrypt the files
 * if necessary.
 */
public class S3StorageBackup {

	public static final String KEY_BACKUP_DONE = "backup-done.json";

	private Logger logger = LogManager.getLogger();

	private Config config;
	private S3StorageClient s3StorageClient;

	public S3StorageBackup(String role) throws NoSuchAlgorithmException, KeyManagementException {

		this.config = new Config();
		this.s3StorageClient = new S3StorageClient(config, role);
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

		String s3Name = s3StorageClient.storageIdToS3Name(storageId);
		String bucket = s3StorageClient.storageIdToBucket(storageId);

		List<S3Object> s3Objects = S3Util.getObjects(s3StorageClient.getS3AsyncClient(s3Name), bucket);

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

		for (S3Object s3Object : s3Objects) {
			String objectKey = s3Object.key();

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

			try (InputStream is = this.s3StorageClient.download(s3Name, bucket, file, null, null);
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
			s3StorageClient.upload(s3Name, bucket, reportStream, KEY_BACKUP_DONE, (long) reportJson.length());
		}

		Files.delete(downloadDir);

		logger.info("s3 backup done: " + storageId);
	}

	public static void main(String[] args) throws NoSuchAlgorithmException, KeyManagementException {
		S3StorageBackup backup = new S3StorageBackup(Role.S3_STORAGE);

		backup.archiveAndCleanUp(Role.S3_STORAGE);

		backup.s3StorageClient.close();
	}
}
