package fi.csc.chipster.archive;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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

public class BackupArchive {
	
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

	public BackupArchive() {	
				
		this.config = new Config();		
				
		archiveAndCleanUp();
	}
	
	private void archiveAndCleanUp() {
		
		for (String role : config.getDbBackupRoles()) {
			String backupPrefix = role + DbBackup.BACKUP_OBJECT_NAME_PART;
			archiveAndCleanUp(role, BackupType.FULL, backupPrefix);
		}
		
		logger.info("find file-storage backups from S3");
		
		//TODO make prefix configurable
		for (String backupPrefix : findStorageBackups("file-storage", "_", Role.FILE_STORAGE)) {
			
			
			archiveAndCleanUp(Role.FILE_STORAGE, BackupType.INCREMENTAL, backupPrefix);		
		}
	}
	
	private HashSet<String> findStorageBackups(String startsWith, String delimiter, String role) {
		String bucket = BackupUtils.getBackupBucket(config, Role.FILE_STORAGE);
		TransferManager transferManager = BackupUtils.getTransferManager(config, role);
		List<S3ObjectSummary> objects = S3Util.getObjects(transferManager, bucket);
		List<String> backups = findBackups(objects, startsWith, BACKUP_INFO);
		
		HashSet<String> backupPrefixes = new HashSet<>();
		
		for (String backup : backups) {
			String[] parts = backup.split(delimiter);
			backupPrefixes.add(parts[0] + delimiter);			
		}
		
		return backupPrefixes;
	}

	private void archiveAndCleanUp(String role, BackupType type, String backupPrefix) {
				
		int dailyCount = Math.max(3, Integer.parseInt(config.getString(CONF_BACKUP_DAILY_COUNT, role)));
		int monthlyCount = Integer.parseInt(config.getString(CONF_BACKUP_MONTHLY_COUNT, role));
		
		Path archiveRootPath = Paths.get("backup-archive");
		
		TransferManager transferManager = BackupUtils.getTransferManager(config, role);
		logger.info("find " + backupPrefix + " from S3");
		
		String bucket = BackupUtils.getBackupBucket(config, role);
		List<S3ObjectSummary> objects = S3Util.getObjects(transferManager, bucket);
		
		// archive all starting from the oldest
		List<String> backups = findBackups(objects, backupPrefix, BACKUP_INFO);
		List<String> archivedBackups = findBackups(objects, backupPrefix, ARCHIVE_INFO);
		
		// skip backups that are already archived
		backups.removeAll(archivedBackups);
		
		logger.info("found " + backups.size() + " unarchived backups");
		
		try {
			for (String backupName : backups) {					
				
				logger.info("archive backup " + backupName);
				
				try {
					archive(transferManager, backupPrefix, archiveRootPath, role, backupName, bucket, objects);				
					
					if (type == BackupType.FULL) {
						removeOldFullArchives(archiveRootPath, backupPrefix, dailyCount, monthlyCount);
					} else {
						removeOldIncrementalArchives(archiveRootPath, backupPrefix, 60);
					}
					
				} catch (IOException | InterruptedException | CleanUpException e) {
					logger.error("archive backup error", e);				
				}
			}
			
			cleanUpS3(transferManager, backupPrefix, role, bucket);
		} catch (ArchiveException e) {
			// hopefully this is enough if the archiving takes longer than 24 hours to protect against multiple processes moving the files
			// at the same time
			logger.error("archive error, skip all " + backupPrefix, e);
		} finally {
			transferManager.shutdownNow();
		}
	}
	
	private void cleanUpS3(TransferManager transferManager, String backupNamePrefix, String role, String bucket) {
		
		logger.info("clean up archived S3 backups of " + backupNamePrefix);
		
		// get objects to find also those that we just archived
		List<S3ObjectSummary> objects = S3Util.getObjects(transferManager, bucket);
		List<String> archivedBackups = findBackups(objects, backupNamePrefix, ARCHIVE_INFO);
		
		// delete all but the latest
		
		Set<String> objectsToDelete = new HashSet<String>();
		
		if (archivedBackups.size() > 1) {
			
			for (String backupName : archivedBackups.subList(0, archivedBackups.size() - 1)) {				
				logger.info("delete backup " + backupName + " from S3");
				Set<String> objectsOfBackup = objects.stream()
				.map(obj -> obj.getKey())
				.filter(key -> key.startsWith(backupName + "/"))
				.collect(Collectors.toSet());
				
				objectsToDelete.addAll(objectsOfBackup);			
			}
		}
		
		for (int i = 0; i < 10 && !objectsToDelete.isEmpty(); i++) {
			logger.info("delete " + objectsToDelete.size() + " objects from S3, attempt " + i);
			objectsToDelete.stream()
			.forEach(key -> transferManager.getAmazonS3Client().deleteObject(bucket, key));
			
			Set<String> keys = transferManager.getAmazonS3Client().listObjects(bucket).getObjectSummaries().stream()
			.map(obj -> obj.getKey())
			.collect(Collectors.toSet());
			
			Set<String> remaining = objectsToDelete.stream()
					.filter(key -> keys.contains(key))
					.collect(Collectors.toSet());
			
			logger.info(remaining.size() + " objects are still present");
			objectsToDelete = remaining;
		}		
		
		logger.info("clean up done");
	}

	private void archive(TransferManager transferManager, String backupNamePrefix, Path archiveRootPath, String role, String backupName, String bucket, List<S3ObjectSummary> objects) throws IOException, InterruptedException, ArchiveException {		
				
		Path currentBackupPath = archiveRootPath.resolve(backupName);
		Path downloadPath = currentBackupPath.resolve("download");
		
		if (Files.exists(currentBackupPath)) {
			throw new ArchiveException("archive path " + currentBackupPath + " exists already. Is other process running?");
		}
		
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
		} else if (archiveNames.size() == 1) {			
			logger.info((backupInfoMap.size() - newFileInfos.size()) + " files will be moved from the archive " + archiveNames.get(0));
		} else {
			// this isn't used at the moment
			logger.warn("the backup is using files from several archive versions (current is " + backupName + "): " + archiveNames);
		}
		
		downloadPath.toFile().mkdirs();
		
		downloadFiles(backupObjects, bucket, transferManager, downloadPath);
		
		collectFiles(archiveRootPath, currentBackupPath, downloadPath, backupInfoMap, backupName, backupNamePrefix);
		
		FileUtils.deleteDirectory(downloadPath.toFile());
		
		logger.info("upload archive info to " + bucket + "/" + backupName + "/" + ARCHIVE_INFO + " for next incremental backup");
		Path archiveInfoPath = writeArchiveInfo(currentBackupPath, backupInfoMap);
		
		Upload upload = transferManager.upload(bucket, backupName + "/" + ARCHIVE_INFO, archiveInfoPath.toFile());
		upload.waitForCompletion();
		logger.info("backup archiving done");		
	}
	
	private List<String> findBackups(List<S3ObjectSummary> objects, String backupNamePrefix, String fileName) {			
		
		return objects.stream()
			.map(o -> o.getKey())
			.filter(name -> name.startsWith(backupNamePrefix))
			// only completed backups (the file info list uploaded in the end)
			.filter(name -> name.endsWith("/" + fileName))
			// take only the backupName part
			.map(fileInfoKey -> fileInfoKey.substring(0, fileInfoKey.indexOf("/")))
			// this compares strings, but luckily it works with this timestamp format from Instant.toString()
			.sorted()
			.collect(Collectors.toList());		
	}

	private Path writeArchiveInfo(Path currentBackupPath, Map<Path, InfoLine> backupInfoMap) throws IOException {
		
		Path archiveInfoPath = currentBackupPath.resolve(ARCHIVE_INFO);
		
		if (Files.exists(archiveInfoPath)) {
			logger.warn("this shouldn't run when the archive info exists already");
			Files.delete(archiveInfoPath);
		}
		
		// make sure the archiveInfo is created even if there are no changes
		FileUtils.touch(archiveInfoPath.toFile());
		
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

	private void collectFiles(Path archiveRootPath, Path currentBackupPath, Path downloadPath, Map<Path, InfoLine> backupInfoMap, String backupName, String backupNamePrefix) throws IOException {
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
					// search from other archives
					try (Stream<Path> archives = Files.list(archiveRootPath)) {
						Optional<Path> candidateOptional = archives
    			        // start from latest
    		            .sorted(Collections.reverseOrder())
						.filter(archive -> {
						    // check only folders of this file-storage
						    return archive.getFileName().toString().startsWith(backupNamePrefix);
						    
						})
						.map(archive -> archive.resolve(info.getGpgPath()))
						.filter(file -> {
							try {
								return isValid(file, info.getGpgSize());
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
						})
						.findAny();					
						if (candidateOptional.isPresent()) {
							logger.warn("file " + candidate + " was found from other archive (maybe several backups were created before running archive?)");
							archiveFile(candidateOptional.get(), currentBackupPath, info.getGpgPath());
						} else {
							logger.error("file " + candidate + " not found");	
						}
					};
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
			
			int maxRetries = 10;
			for (int i = 0; i < maxRetries; i++) {
				logger.info("download " + bucket + "/" + key);
				try {
					Download download = transferManager.download(bucket, key, downloadFilePath.toFile());
//					download.addProgressListener(new ProgressListener() {
//						@Override
//						public void progressChanged(ProgressEvent progressEvent) {
//							logger.info("download progress " + progressEvent.getEventType().name()
//									+ " bytes: " + FileUtils.byteCountToDisplaySize(progressEvent.getBytes())
//									+ " transferred: " + FileUtils.byteCountToDisplaySize(progressEvent.getBytesTransferred()));
//						}						
//					});
					download.waitForCompletion();
					break;
				} catch (AmazonClientException e) {
					logger.warn("download try " + i + " failed with exception", e);
					if (i == maxRetries - 1) {
						throw e;
					}
				}
			}
			
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
		
		try (Stream<Path> files = Files.list(archiveRootPath)) {		
			List<Path> allFiles = files
					.filter(path -> path.getFileName().toString().startsWith(backupPrefix))	
					.collect(Collectors.toList());	
				
			// all backups
			TreeMap<Instant, Path> archives = BackupRotation2.parse(allFiles, backupPrefix, path -> path.getFileName().toString());
			
			return archives;
		}
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
		new BackupArchive();
	}
}
