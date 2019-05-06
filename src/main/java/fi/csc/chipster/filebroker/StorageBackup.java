package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.S3Util;

public class StorageBackup {
		
	public static final String FILE_BROKER_BACKUP_NAME_PREFIX = "file-broker-backup_";

	private Logger logger = LogManager.getLogger();
	
	private Path storage;
	private Config config;
	private String role;
	private String bucket;
	private String recipient;

	@SuppressWarnings("unused")
	private Timer timer;

	private String gpgPassphrase;

	private String gpgVersion;

	public StorageBackup(Path storage, boolean scheduleTimer, Config config) throws IOException, InterruptedException {	
		
		this.storage = storage; 		
		this.role = Role.FILE_BROKER;
		this.recipient = config.getString(BackupUtils.CONF_BACKUP_GPG_RECIPIENT, role);
		this.gpgPassphrase = config.getString(BackupUtils.CONF_BACKUP_GPG_PASSPHRASE, role);
		
		this.config = config;		
		this.bucket = BackupUtils.getBackupBucket(config, role);
		
		this.gpgVersion = BackupUtils.getGpgVersion();
		
		if (scheduleTimer) {
			timer = BackupUtils.startBackupTimer(new TimerTask() {			
				@Override
				public void run() {
					backupNow();
				}
			}, role, config);
		} else {
			backupNow();
		}
	}
	
	public void backupNow() {
		try {
			backup();
		} catch (IOException | InterruptedException e) {
			logger.error("backup error", e);
		}
	}
	
	private String findLatest(List<S3ObjectSummary> objects, String backupNamePrefix, String fileName) {
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
	
	private void backup() throws IOException, InterruptedException {
		
		String bucket = BackupUtils.getBackupBucket(config, role);
		if (bucket.isEmpty()) {
			logger.warn("no backup configuration for " + role);
			return;
		}
		
		TransferManager transferManager = BackupUtils.getTransferManager(config, role);
		
		Path backupDir = storage.resolve("backup");
		
		if (Files.exists(backupDir)) {
			logger.warn(backupDir + " exists already, is there other backup process running or has the previous run failed? ");
			FileUtils.cleanDirectory(backupDir.toFile());			
		}
		backupDir.toFile().mkdir();
		
		logger.info("find archived backups");
		List<S3ObjectSummary> objects = S3Util.getObjects(transferManager, bucket);
		
		String archiveInfoKey = findLatest(objects, FILE_BROKER_BACKUP_NAME_PREFIX, BackupArchiver.ARCHIVE_INFO);
		Map<Path, InfoLine> archiveInfoMap = new HashMap<>();
		String archiveName = null;		
		
		if (archiveInfoKey != null) {
			archiveName = archiveInfoKey.substring(0, archiveInfoKey.indexOf("/"));						
			archiveInfoMap = BackupUtils.infoFileToMap(transferManager, bucket, archiveInfoKey, backupDir);	
			
			logger.info("found an archive " + archiveName);
		} else {
			logger.info("no previous archive info found, doing full backup");			
		}
		
		Instant now = Instant.now();
		String backupName = FILE_BROKER_BACKUP_NAME_PREFIX + now;
			
		Path backupInfoPath = backupDir.resolve(BackupArchiver.BACKUP_INFO);				
		FileUtils.touch(backupInfoPath.toFile());
				
		// collect a list of all files in storage (except the backup dir)
		// now stored in memory to make it easier to calculate the incrementals
		logger.info("list files");
		Map<Path, InfoLine> storageFiles = listFiles(storage, backupDir);
		
		long fileCount = storageFiles.size();
		long fileSizeTotal = storageFiles.values().stream()
				.mapToLong(info -> info.getSize())
				.sum();
		
		long archiveFileCount = archiveInfoMap.size();
		long archiveSizeTotal = archiveInfoMap.values().stream()
				.mapToLong(info -> info.getSize())
				.sum();
		
		logger.info("there are " + fileCount + " files (" + FileUtils.byteCountToDisplaySize(fileSizeTotal) + ") in storage");
		
		Map<Path, InfoLine> filesToBackup = new HashMap<>();
		
		// no need to backup files that are already on the backup server a.k.a. archive
		caluclateIncremental(storageFiles, archiveInfoMap, backupInfoPath, filesToBackup, archiveName);									
		
		// calculateIncremental() found these files from the archive 
		long usableArchiveFileCount = Files.lines(backupInfoPath).count();
		long usableArchiveSizeTotal = Files.lines(backupInfoPath)
				.map(line -> InfoLine.parseLine(line))				
				.mapToLong(info -> info.getSize())
				.sum();
		
		long filesToBackupCount = filesToBackup.size();
		long filesToBackupSizeTotal = filesToBackup.values().stream()
				.mapToLong(info -> info.getSize())
				.sum();
		
		logger.info(usableArchiveFileCount + " files (" + FileUtils.byteCountToDisplaySize(usableArchiveSizeTotal) + ") are already in the archive");
		logger.info(filesToBackupCount + " files (" + FileUtils.byteCountToDisplaySize(filesToBackupSizeTotal) + ") need to backed up now");		
		logger.info((archiveFileCount - usableArchiveFileCount) + " files (" + FileUtils.byteCountToDisplaySize(archiveSizeTotal - usableArchiveSizeTotal) + ") in the archive are not needed anymore");
		
		/* 
		 * Group files by the filename prefix
		 *  
		 * We need some grouping for the files, because the object storage has too much latency for 
		 * handling all the small files one by one and everything in on huge tar would
		 * be clumsy to debug and to parallelize.
		 *  
		 * We have to scale the number of groups based on the total number of files.
		 * The number of files in one group should stay reasonable for the reasons above and having
		 * too many groups for a few files will be again just hard to debug and slow.
		 * 
		 * Finally, it would be nice to be able to find the files from those groups manually when needed.
		 * 
		 * This grouping is somewhat similar concept to file-broker partitions, but here the number of groups is more 
		 * critical and has to be adjusted according the number of files. Let's call them simply "groups" 
		 * for now.
		 */
		
		int prefixLength = getPrefixLength(filesToBackupCount);
		
		logger.info("group files by " + prefixLength + " first characters, max " + (int)Math.pow(16, prefixLength) + " group(s)");
		
		Set<String> prefixes = getFilenamePrefixes(filesToBackup, prefixLength);
		
		int groupIndex = 0;
		for (String prefix : prefixes) {			
			
			List<Path> groupFiles = filesToBackup.keySet().stream()
					.filter(path -> prefix.equals(path.getFileName().toString().substring(0, prefixLength)))				
					.collect(Collectors.toList());
			
			// find out file sizes, small and large files need to be handled differently
			// paths are relative to the storage dir
			Map<Path, Long> groupFileSizes = getFileSizes(storage, groupFiles);
			
			backupGroup(prefix, storage, groupFileSizes, backupDir, groupIndex, prefixes.size(), backupName, backupInfoPath, transferManager);
			groupIndex++;
		}
				
		long infoCount = Files.lines(backupInfoPath).count();
		logger.info(infoCount + " files backed up. " + (fileCount - infoCount) + " files disappeared during the backup (probably deleted)");
		
		// finally upload the backup info to signal that the backup is complete
		BackupUtils.uploadBackupInfo(transferManager, bucket, backupName, backupInfoPath);
		
		FileUtils.deleteDirectory(backupDir.toFile());
		
		transferManager.shutdownNow();
	}
	
	/**
	 * Get all unique prefixes of the filenames in file paths
	 * 
	 * @param filesToBackup File where the file paths are listed each on its own line
	 * @param prefixLength
	 * @return set of prefixes
	 * @throws IOException
	 */
	private Set<String> getFilenamePrefixes(Map<Path, InfoLine> filesToBackup, int prefixLength) throws IOException {
		return filesToBackup.keySet().stream()
		.map(path -> path.getFileName().toString().substring(0, prefixLength))
		.collect(Collectors.toSet());
	}

	private int getPrefixLength(long fileCount) {		
		if (fileCount < 500) {
			return 0;
		} else if (fileCount < 10 * 1000) {
			return 1;
		} else if (fileCount < 100 * 1000) {
			return 2;
		} else if (fileCount < 1000 * 1000) {
			return 3;
		}
		return 4;
	}
	
	private void caluclateIncremental(Map<Path, InfoLine> storageFiles, Map<Path, InfoLine> archiveInfoMap, Path backupInfoPath, Map<Path, InfoLine> filesToBackup, String archiveName) throws IOException {
		
		for (Path filePath : storageFiles.keySet()) {
			InfoLine archiveFileInfo = archiveInfoMap.get(filePath);			
			InfoLine currentFileInfo = storageFiles.get(filePath);
			
			if (archiveFileInfo == null) {
				// file not found from the archive
				filesToBackup.put(filePath, currentFileInfo);
				
			} else if (!BackupUtils.getPackageGpgPath(filePath).equals(archiveFileInfo.getGpgPath())) {
					logger.warn("package paths have changed");
					filesToBackup.put(filePath, currentFileInfo);
					
			} else if (currentFileInfo.getSize() != archiveFileInfo.getSize()) {
				logger.warn("file " + filePath + " size has changed: " + archiveFileInfo.getSize() + ", " + currentFileInfo.getSize());
				
				// file shouldn't change, but let's keep this to backup it again
				filesToBackup.put(filePath, currentFileInfo);
				
			} else {
				// file was found from the archive and the path and size are fine
				// we could also ocheck the checksum, but it would take a lot of time
				
				// archiver needs to now that it's still needed and where to find it (archiveName)			
				Files.write(backupInfoPath, Collections.singleton(archiveFileInfo.toLine()), Charset.defaultCharset(), 
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		}
	}

	private Map<Path, Long> getFileSizes(Path storage, List<Path> files) {
		return files.stream()
		.filter(path -> Files.isRegularFile(storage.resolve(path)))
		.collect(Collectors.toMap(path -> path, path -> {
			try {
				return Files.size(storage.resolve(path));
			} catch (IOException e) {
				logger.error("get file size failed: " + storage.resolve(path), e);
				return null;
			}
		}));
	}

	/**
	 * List recursively all files under dir
	 * 
	 * @param dir
	 * @param exclude exclude files under this path
	 * @throws IOException
	 */
	private Map<Path, InfoLine> listFiles(Path dir, Path exclude) throws IOException {		
		
		HashMap<Path, InfoLine> fileMap = new HashMap<>();
		
		Files.walk(dir, FileVisitOption.FOLLOW_LINKS)
        	.filter(Files::isRegularFile)
        	.filter(path -> !path.startsWith(exclude))
        	.map(path -> dir.relativize(path))
        	.filter(file -> {        		
        		// check that filenames don't contain delimiters of the info files
        		if (file.toString().contains("\n") || file.toString().contains("\t")) {
        			logger.warn("file " + file + " is skipped because it contains a new line and tab character in the filename");
        			return false;
        		}
        		return true;
        	})
        	.map(file -> {
				try {
					return new InfoLine(file, Files.size(storage.resolve(file)), null, null, -1, null, null);
				} catch (IOException e) {
					throw new RuntimeException("failed to get the size of file " + file, e);
				}
			})
        	.forEach(info -> {
        		fileMap.put(info.getPath(), info);
        	});
        	
        return fileMap;
	}

	private void backupGroup(String prefix, Path storage, Map<Path, Long> groupFileSizes, Path backupDir, int groupIndex, int groupCount, String backupName, Path backupInfoPath, TransferManager transferManager) throws IOException, InterruptedException {		
		
		long smallSizeLimit = 1 * 1024 * 1024; // 1 MiB
		long mediumSizeLimit = 1024 * 1024 * 1024; // 1 GiB
		
		Map<Path, Long> mediumFiles = new HashMap<>();
		Map<Path, Long> smallFiles = new HashMap<>();
		Map<Path, Long> largeFiles = new HashMap<>();
		
		for (Entry<Path, Long> entry : groupFileSizes.entrySet()) {
			if (entry.getValue() < smallSizeLimit) {
				smallFiles.put(entry.getKey(), entry.getValue());			
			} else  if (entry.getValue() < mediumSizeLimit) {
				mediumFiles.put(entry.getKey(), entry.getValue());			
			} else  {
				largeFiles.put(entry.getKey(), entry.getValue());
			}
		}
		
		long smallFilesTotal = smallFiles.values().stream().mapToLong(l -> l).sum();
		long mediumFilesTotal = mediumFiles.values().stream().mapToLong(l -> l).sum();
		long largeFilesTotal = largeFiles.values().stream().mapToLong(l -> l).sum();	
		
		String groupInfo = "encrypt group " + (groupIndex + 1) + "/" + groupCount;
		
		logger.info(groupInfo + ", files starting with '" + prefix + "'");
				
		// small files to be transferred in a tar package, extraction may take some time, but it's easy to create temporary copies
		logger.info(groupInfo + ", " + smallFiles.size() + " small files (" + FileUtils.byteCountToDisplaySize(smallFilesTotal) + ")");
		BackupUtils.backupFilesAsTar(prefix + "_small_files", storage, smallFiles.keySet(), backupDir, transferManager, bucket, backupName, backupInfoPath, recipient, gpgPassphrase, gpgVersion);
		
		// medium files to be transferred in a tar package, should be relatively easy to extract from stream
		logger.info(groupInfo + ", " + mediumFiles.size() + " medium files (" + FileUtils.byteCountToDisplaySize(mediumFilesTotal) + ")");
		BackupUtils.backupFilesAsTar(prefix + "_medium_files", storage, mediumFiles.keySet(), backupDir, transferManager, bucket, backupName, backupInfoPath, recipient, gpgPassphrase, gpgVersion);
		
		// large files to be transferred one by one
		logger.info(groupInfo + ", " + largeFiles.size() + " large files (" + FileUtils.byteCountToDisplaySize(largeFilesTotal) + ")");
		for (Path file : largeFiles.keySet()) {			
			BackupUtils.backupFileAsTar(file.getFileName().toString(), storage, file, backupDir, transferManager, bucket, backupName, backupInfoPath, recipient, gpgPassphrase, gpgVersion);
		}		
	}
	
	public static void main(String[] args) throws IOException, InterruptedException {
		new StorageBackup(Paths.get("storage"), false, new Config());
	}
}
