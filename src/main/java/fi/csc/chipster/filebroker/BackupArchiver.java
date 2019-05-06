package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
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
import fi.csc.chipster.rest.hibernate.BackupRotation2;
import fi.csc.chipster.rest.hibernate.DbBackup;
import fi.csc.chipster.rest.hibernate.S3Util;

public class BackupArchiver {
	
	public static final String BACKUP_INFO = "backup-info";
	public static final String ARCHIVE_INFO = "archive-info";
	
	private static final String CONF_BACKUP_MONTHLY_COUNT = "backup-monthly-count";
	private static final String CONF_BACKUP_DAILY_COUNT = "backup-daily-count";
	
	private enum BackupType {
		INCREMENTAL,
		FULL;
	}

	private Logger logger = LogManager.getLogger();
	
	private Config config;

	public BackupArchiver() {	
				
		this.config = new Config();		
				
		archiveAndCleanUp();
	}
	
	private void archiveAndCleanUp() {
		
		for (String role : config.getDbBackupRoles()) {
			String backupPrefix = role + DbBackup.BACKUP_OBJECT_NAME_PART;
			archiveAndCleanUp(role, BackupType.FULL, backupPrefix);
		}
		
		archiveAndCleanUp(Role.FILE_BROKER, BackupType.INCREMENTAL, StorageBackup.FILE_BROKER_BACKUP_NAME_PREFIX);		
	}
	
	private void archiveAndCleanUp(String role, BackupType type, String backupPrefix) {
				
		int dailyCount = Math.max(3, Integer.parseInt(config.getString(CONF_BACKUP_DAILY_COUNT, role)));
		int monthlyCount = Integer.parseInt(config.getString(CONF_BACKUP_MONTHLY_COUNT, role));
		
		Path archiveRootPath = Paths.get("backup-archive");
		
		TransferManager transferManager = BackupUtils.getTransferManager(config, role);
		try {
			archive(transferManager, backupPrefix, archiveRootPath, role);				
			cleanUpS3(transferManager, backupPrefix, role);
			
			if (type == BackupType.FULL) {
				removeOldFullArchives(archiveRootPath, backupPrefix, dailyCount, monthlyCount);
			} else {
				removeOldIncrementalArchives(archiveRootPath, StorageBackup.FILE_BROKER_BACKUP_NAME_PREFIX, 60);
			}
			
		} catch (IOException | InterruptedException | CleanUpException e) {
			logger.error("backup archiving error", e);
			
		} finally {			
			transferManager.shutdownNow();
		}	
	}
	
	private void cleanUpS3(TransferManager transferManager, String backupNamePrefix, String role) {
		
		logger.info("clean up archived S3 backups of " + backupNamePrefix);
		
		String bucket = BackupUtils.getBackupBucket(config, role);
		List<S3ObjectSummary> objects = S3Util.getObjects(transferManager, bucket);
		
		List<String> archivedBackups = objects.stream()
				.map(o -> o.getKey())
				.filter(name -> name.startsWith(backupNamePrefix))
				// only archived backups (the archive info is uploaded in the end)
				.filter(name -> name.endsWith("/" + BackupArchiver.ARCHIVE_INFO))
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

	private void archive(TransferManager transferManager, String backupNamePrefix, Path archiveRootPath, String role) throws IOException, InterruptedException {
						
		logger.info("find latest " + backupNamePrefix + " from S3");
				
		String bucket = BackupUtils.getBackupBucket(config, role);
		List<S3ObjectSummary> objects = S3Util.getObjects(transferManager, bucket);
		String fileInfoKey = findLatest(objects, backupNamePrefix, BACKUP_INFO);
		
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
				
		Path currentBackupPath = archiveRootPath.resolve(backupName);
		Path downloadPath = currentBackupPath.resolve("download");
		
		String key = backupName + "/" + BACKUP_INFO;
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
				
		logger.info("the backup has " + newFileInfos.size() + " new files in " + (backupObjects.size() - 1) + " packages");
		if (archiveNames.size() == 0) {
			logger.info("no files will moved from the old archives");
		}
		if (archiveNames.size() == 1) {			
			logger.info((backupInfoMap.size() - newFileInfos.size()) + " files will be moved from the archive " + archiveNames.get(0));
		} else {
			// this isn't used at the moment
			logger.warn("the backup is using files from several archive versions (current is " + backupName + "): " + archiveNames);
		}
		
		downloadPath.toFile().mkdirs();
		
		downloadFiles(backupObjects, bucket, transferManager, downloadPath);
		
		collectFiles(archiveRootPath, currentBackupPath, downloadPath, backupInfoMap, backupName);
		
		FileUtils.deleteDirectory(downloadPath.toFile());
		
		logger.info("upload archive info to " + bucket + "/" + backupName + "/" + ARCHIVE_INFO + " for next incremental backup");
		Path archiveInfoPath = writeArchiveInfo(currentBackupPath, backupInfoMap);
		
		Upload upload = transferManager.upload(bucket, backupName + "/" + ARCHIVE_INFO, archiveInfoPath.toFile());
		upload.waitForCompletion();
		logger.info("backup archiving done");		
	}
	
	public static String findLatest(List<S3ObjectSummary> objects, String backupNamePrefix, String fileName) {
		List<String> fileBrokerBackups = objects.stream()
			.map(o -> o.getKey())
			.filter(name -> name.startsWith(backupNamePrefix))
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
			
			if (key.endsWith(".tar")) {
				extract(downloadFilePath, downloadDirPath);
			}
		}
	}
		
	private void extract(Path tarPath, Path downloadDirPath) throws IOException, InterruptedException {
		logger.info("extract " + tarPath.getFileName());		
		ProcessUtils.run(null, null, "tar", "-xf", tarPath.toString(), "--directory", downloadDirPath.toString());
		Files.delete(tarPath);
	}
		
	/**
	 * Remove old archives
	 * 
	 * Suitable only for full backups, where each archive is a useful alone.
	 * 
	 * Keep x daily backups and y monthly backups. This is relatively safe even if the 
	 * server time was incorrect or the backups were created more often. Files
	 * created during the clean-up are kept.
	 * 
	 * @param archiveRootPath
	 * @param backupPrefix
	 * @param dailyCount
	 * @param monthlyCount
	 * @throws IOException
	 * @throws CleanUpException 
	 */
	private void removeOldFullArchives(Path archiveRootPath, String backupPrefix, int dailyCount, int monthlyCount) throws IOException, CleanUpException {
		logger.info("list " + backupPrefix + " archives");
		
		TreeMap<Instant, Path> backupsToDelete = getArchives(archiveRootPath, backupPrefix);
		
		logger.info(backupsToDelete.size() + " archives found");
		
		checkClock(backupsToDelete);
				
		TreeMap<Instant, Path> daily = BackupRotation2.getFirstOfEachDay(backupsToDelete);
		TreeMap<Instant, Path> monthly = BackupRotation2.getFirstOfEachMonth(backupsToDelete);
		
		// keep these
		TreeMap<Instant, Path> latestDaily = BackupRotation2.getLast(daily, dailyCount);
		TreeMap<Instant, Path> latestMonthly = BackupRotation2.getLast(monthly, monthlyCount);
		
		logger.info(latestDaily.size() + " daily backups kept");
		logger.info(latestMonthly.size() + " monthly backups kept");
		
		// remove the backups to keep from the list of all backups to get those that we want to remove 
		backupsToDelete = BackupRotation2.removeAll(backupsToDelete, latestDaily.keySet());
		backupsToDelete = BackupRotation2.removeAll(backupsToDelete, latestMonthly.keySet());
				
		for (Path obj : backupsToDelete.values()) {
			logger.info("delete backup " + obj);
			FileUtils.deleteDirectory(obj.toFile());
		}	
			
		logger.info(backupsToDelete.size() + " backups deleted");
	}
	
	private TreeMap<Instant, Path> getArchives(Path archiveRootPath, String backupPrefix) throws IOException {
		List<Path> allFiles = Files.list(archiveRootPath)
				.filter(path -> path.getFileName().toString().startsWith(backupPrefix))	
				.collect(Collectors.toList());	
			
			// all backups
			TreeMap<Instant, Path> archives = BackupRotation2.parse(allFiles, backupPrefix, path -> path.getFileName().toString());
			
			return archives;
	}

	/**
	 * Clean up incremental archives
	 * 
	 * Individual incremental archives are not useful without having all newer archives.
	 * 
	 * @param archiveRootPath
	 * @param backupPrefix
	 * @param dayCount
	 * @throws IOException
	 * @throws CleanUpException 
	 */
	private void removeOldIncrementalArchives(Path archiveRootPath, String backupPrefix, int dayCount) throws IOException, CleanUpException {
		logger.info("list " + backupPrefix + " archives");
		
		TreeMap<Instant, Path> backupsToDelete = getArchives(archiveRootPath, backupPrefix);
		
		logger.info(backupsToDelete.size() + " archives found");
		
		checkClock(backupsToDelete);		
		
		// keep these
		TreeMap<Instant, Path> latestByCount = BackupRotation2.getLast(backupsToDelete, dayCount);
		TreeMap<Instant, Path> latestByTime = BackupRotation2.getNewerThan(backupsToDelete, Instant.now().minus(dayCount, ChronoUnit.DAYS));
		
		// if backups are made daily, this should be more or less equal
		logger.info(latestByCount.size() + " last backups kept");
		logger.info(latestByTime.size() + " backups of last " + dayCount + " days kept");
		
		// remove the backups to keep from the list of all backups to get those that we want to remove 
		backupsToDelete = BackupRotation2.removeAll(backupsToDelete, latestByCount.keySet());
		backupsToDelete = BackupRotation2.removeAll(backupsToDelete, latestByTime.keySet());
				
		for (Path obj : backupsToDelete.values()) {
			logger.info("delete backup " + obj);
			FileUtils.deleteDirectory(obj.toFile());
		}	
			
		logger.info(backupsToDelete.size() + " backups deleted");
	}


	private void checkClock(TreeMap<Instant, Path> backupsToDelete) throws CleanUpException {
		
		// don't clean up if the clock might be wrong
		if (!backupsToDelete.isEmpty()) {
			if (backupsToDelete.lastKey().isAfter(Instant.now())) {
				throw new CleanUpException("the last archive is newer than the current time. Refusing to clean up anything");
			}
			
			int maxAge = 7;
			if (backupsToDelete.lastKey().isBefore(Instant.now().minus(maxAge, ChronoUnit.DAYS))) {
				throw new CleanUpException("the last archive is older " + maxAge + " days. Refusing to clean up anything");
			}
		}
	}

	public static void main(String[] args) {
		new BackupArchiver();
	}
}
