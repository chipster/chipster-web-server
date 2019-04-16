package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.Download;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ProcessUtils;
import fi.csc.chipster.rest.hibernate.DbBackup;
import fi.csc.chipster.rest.hibernate.S3Util;

public class StorageBackupArchiver {
	
	public static final String ARCHIVE_INFO = "archive-info";	

	private Logger logger = LogManager.getLogger();
	
	private Config config;
	private String role;
	private String bucket;

	private Timer timer;

	public StorageBackupArchiver(boolean scheduleTimer) {	
				
		this.role = Role.FILE_BROKER;
		this.bucket = "petri-backup-test";
		this.config = new Config();
				
		if (scheduleTimer) {			
			timer = new Timer();
			timer.scheduleAtFixedRate(new TimerTask() {			
				@Override
				public void run() {
					runNow();
				}
			}, new Date(), 24 * 60 * 60 * 1000);
		} else {
			runNow();
		}
	}
	
	private void runNow() {
		TransferManager transferManager = DbBackup.getTransferManager(config, role);
		try {
			archive(transferManager);
			
			cleanUpS3(transferManager);
		} catch (IOException | InterruptedException e) {
			logger.error("backup error", e);
		} finally {			
			transferManager.shutdownNow();
		}
	}
	
	private void cleanUpS3(TransferManager transferManager) {
		
		logger.info("clean up archived backups in S3");
		
		List<S3ObjectSummary> objects = S3Util.getObjects(transferManager, bucket);
		
		List<String> archivedBackups = objects.stream()
				.map(o -> o.getKey())
				.filter(name -> name.startsWith(StorageBackup.FILE_BROKER_BACKUP_NAME_PREFIX))
				// only archived backups (the archive info is uploaded in the end)
				.filter(name -> name.endsWith("/" + StorageBackupArchiver.ARCHIVE_INFO))
				.map(key -> key.substring(0, key.indexOf("/")))
				// this compares strings, but luckily it works with this timestamp format from Instant.toString()
				.sorted()
				.collect(Collectors.toList());				
		
		// delete all but the latest
		
		if (archivedBackups.size() > 1) {
			for (String backupName : archivedBackups.subList(0, archivedBackups.size() - 1)) {
				logger.info("delete backup " + backupName + " from S3");
				objects.stream()
				.map(o -> o.getKey())
				.filter(key -> key.startsWith(backupName + "/"))
				.forEach(key -> transferManager.getAmazonS3Client().deleteObject(bucket, key));			
			}
		}
		
		logger.info("clean up done");
	}

	private void archive(TransferManager transferManager) throws IOException, InterruptedException {
				
		logger.info("find latest backup");
				
		List<S3ObjectSummary> objects = S3Util.getObjects(transferManager, bucket);
		String fileInfoKey = findLatest(objects, StorageBackup.BACKUP_INFO);
		
		if (fileInfoKey == null) {
			logger.error("no backups found");
			return;
		}
		

		// parse the object storage folder
		String backupName = fileInfoKey.substring(0, fileInfoKey.indexOf("/"));
		
		boolean isArchived = objects.stream()
		.map(obj -> obj.getKey())
		.filter(key -> (backupName + "/" + ARCHIVE_INFO).equals(key))
		.findAny().isPresent();
		
		if (isArchived) {
			logger.info("latest backup " + backupName + " is already archived");
			return;
		}
		
		logger.info("latest backup is " + backupName);
		
		Path archiveRootPath = Paths.get("storage-archive");
		Path currentBackupPath = archiveRootPath.resolve(backupName);
		Path downloadPath = currentBackupPath.resolve("download");		
		
		String key = backupName + "/" + StorageBackup.BACKUP_INFO;
		Map<Path, InfoLine> backupInfoMap = BackupUtils.infoFileToMap(transferManager, bucket, key, currentBackupPath);
							
		List<String> backupObjects = objects.stream()
		.map(o -> o.getKey())
		.filter(name -> name.startsWith(backupName + "/"))
		.collect(Collectors.toList());
		
		List<InfoLine> newFileInfos = backupInfoMap.values().stream()
			.filter(info -> backupName.equals(info.getBackupName()))
			.collect(Collectors.toList());
		
		List<String> archiveNames = backupInfoMap.values().stream()
				.filter(info -> !backupName.equals(info.getBackupName()))
				.map(info -> info.getBackupName())
				.distinct()
				.collect(Collectors.toList());
				
		logger.info("the backup has " + newFileInfos.size() + " new files in " + backupObjects.size() + " packages");
		logger.info((backupInfoMap.size() - newFileInfos.size()) + " files will be moved from the archive " + archiveNames);
		
		downloadPath.toFile().mkdirs();
		
		downloadFiles(backupObjects, bucket, transferManager, downloadPath);
		
		collectFiles(archiveRootPath, currentBackupPath, downloadPath, backupInfoMap, backupName);
		
//		FileUtils.deleteDirectory(downloadPath.toFile());
		
		logger.info("upload archive info to " + bucket + "/" + backupName + "/" + ARCHIVE_INFO + " for next incremental backup");
		Path archiveInfoPath = writeArchiveInfo(currentBackupPath, backupInfoMap);
		
		Upload upload = transferManager.upload(bucket, backupName + "/" + ARCHIVE_INFO, archiveInfoPath.toFile());
		upload.waitForCompletion();
		logger.info("backup archiving done");		
	}
	
	public static String findLatest(List<S3ObjectSummary> objects, String fileName) {
		List<String> fileBrokerBackups = objects.stream()
			.map(o -> o.getKey())
			.filter(name -> name.startsWith(StorageBackup.FILE_BROKER_BACKUP_NAME_PREFIX))
			// only completed backups (the file info list uploaded in the end)
			.filter(name -> name.endsWith("/" + fileName))
			// this compares strings, but luckily it works with this timestamp format from Instant.toString()
			.sorted()
			.collect(Collectors.toList());
				
		if (fileBrokerBackups.isEmpty()) {
			return null;
		}
		
		// return latest
		return fileBrokerBackups.get(fileBrokerBackups.size() - 1);
	}

	private Path writeArchiveInfo(Path currentBackupPath, Map<Path, InfoLine> backupInfoMap) throws IOException {
		
		Path archiveInfoPath = currentBackupPath.resolve(ARCHIVE_INFO);
		
		if (Files.exists(archiveInfoPath)) {
			logger.warn("this shouldn't run when the archive info exists already");
			Files.delete(archiveInfoPath);
		}
		
		backupInfoMap.values().stream().forEach(backupInfo -> {
			try {				
				
				Path archiveFilePath = currentBackupPath.resolve(backupInfo.getGpgPath());
				
				// check that the file is in archive
				if (!Files.exists(archiveFilePath)) {
					logger.warn("file " + backupInfo.getGpgPath() + " is missing");
					return;
				}
				
				// check that the file size is correct
				if (Files.size(archiveFilePath) != backupInfo.getGpgSize()) {
					logger.warn("file " + backupInfo.getGpgPath() + " has size " + Files.size(archiveFilePath) + ", expected " + backupInfo.getGpgSize());
					return;
				}
				
				// we could also the checksum of the encrypted file, but it would take a lot of time
				
				// other fields we have to take from the original backupInfo				
				backupInfo.setBackupName(currentBackupPath.getFileName().toString());
				
				Files.write(archiveInfoPath, Collections.singleton(backupInfo.toLine()), Charset.defaultCharset(), 
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				
			} catch (IOException e) {
				logger.warn("writing archive info failed", e);
			}
		});
		
		return archiveInfoPath;
	}

	private void collectFiles(Path archiveRootPath, Path currentBackupPath, Path downloadPath, Map<Path, InfoLine> backupInfoMap, String backupName) throws IOException {
		backupInfoMap.values()
		.forEach(info -> {						
			try {				
				Path candidate;
				if (backupName.equals(info.getBackupName())) {
					// file should be in the latest backup
					candidate = downloadPath.resolve(info.getGpgPath());									
				} else {
					// the file should be in the archive 
					candidate = archiveRootPath.resolve(info.getBackupName()).resolve(info.getGpgPath());
				}
				
				if (isValid(candidate, info.getGpgSize())) {
					archiveFile(candidate, currentBackupPath, info.getGpgPath());
				} else {
					logger.error("file " + candidate + " not found");
				}								
			} catch (IOException e) {
				logger.error("archive error", e);
			}
		});
	}

	private boolean isValid(Path candidate, long gpgSize) throws IOException {
		if (Files.exists(candidate)) {
			long candidateSize = Files.size(candidate);
			
			if (candidateSize == gpgSize) {
				return true;
			} else {
				logger.warn("file " + candidate + " found, but the size " + candidateSize + " does not match expected " + gpgSize);
			}
		}
		return false;
	}

	/**
	 * @param source file to move
	 * @param targetPathRoot existing target dir
	 * @param targetSubPath target file, sub dirs created if necessary
	 * @throws IOException
	 */
	private void archiveFile(Path source, Path targetPathRoot, Path targetSubPath) throws IOException {

		Path target = targetPathRoot.resolve(targetSubPath);
		Files.createDirectories(target.getParent());
		Files.move(source, target);
	}

	private void downloadFiles(List<String> backupObjects, String bucket, TransferManager transferManager, Path downloadDirPath) throws AmazonServiceException, AmazonClientException, InterruptedException, IOException {
		for (String key : backupObjects) {
						
			String filename = Paths.get(key).getFileName().toString();
			Path downloadFilePath = downloadDirPath.resolve(filename);
			
			logger.info("download " + bucket + "/" + key);
			Download download = transferManager.download(bucket, key, downloadFilePath.toFile());
			download.waitForCompletion();
			
			extract(downloadFilePath, downloadDirPath);
		}
	}

		
	private void extract(Path tarPath, Path downloadDirPath) throws IOException, InterruptedException {
		logger.info("extract " + tarPath.getFileName());		
		ProcessUtils.run(null, null, "tar", "-xf", tarPath.toString(), "--directory", downloadDirPath.toString());
		Files.delete(tarPath);
	}

	public static void main(String[] args) {
		new StorageBackupArchiver(false);
	}
}
