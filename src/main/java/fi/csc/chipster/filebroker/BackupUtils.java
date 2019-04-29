package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.transfer.Download;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ProcessUtils;
import fi.csc.chipster.rest.hibernate.S3Util;

public class BackupUtils {
		
	private static final String ENV_GPG_PASSPHRASE = "GPG_PASSPHRASE";

	private final static Logger logger = LogManager.getLogger();
	
	private static final String CONF_BACKUP_BUCKET = "backup-bucket";	
	private static final String COND_BACKUP_S3_SIGNER_OVERRIDE = "backup-s3-signer-override";
	private static final String CONF_BACKUP_S3_SECRET_KEY = "backup-s3-secret-key";
	private static final String CONF_BACKUP_S3_ACCESS_KEY = "backup-s3-access-key";
	private static final String CONF_BACKUP_S3_REGION = "backup-s3-region";
	private static final String CONF_BACKUP_S3_ENDPOINT = "backup-s3-endpoint";
	
	private static final String CONF_BACKUP_TIME = "backup-time";
	private static final String CONF_BACKUP_INTERVAL = "backup-interval";
	
	public static final String CONF_BACKUP_GPG_RECIPIENT = "backup-gpg-recipient";	
	public static final String CONF_BACKUP_GPG_PASSPHRASE = "backup-gpg-passphrase";	

	public static Map<Path, InfoLine> infoFileToMap(TransferManager transferManager, String bucket, String key, Path tempDir) throws AmazonServiceException, AmazonClientException, InterruptedException, IOException {
						
		Path infoPath = tempDir.resolve(key);
		Download download = transferManager.download(bucket, key, infoPath.toFile());
		download.waitForCompletion();
					
		Map<Path, InfoLine> map = (HashMap<Path, InfoLine>) Files.lines(infoPath)
				.map(line -> InfoLine.parseLine(line))
				.collect(Collectors.toMap(info -> info.getPath(), info -> info));
		Files.delete(infoPath);
		
		return map;
	}
	
	public static void backupFileAsTar(String name, Path storage, Path file, Path backupDir, TransferManager transferManager, String bucket, String backupName, Path backupInfoPath, String recipient, String gpgPassphrase, String gpgVersion) throws IOException, InterruptedException {
		backupFilesAsTar(name, storage, Collections.singleton(file), backupDir, transferManager, bucket, backupName, backupInfoPath, recipient, gpgPassphrase, gpgVersion);
	}
	
	public static void backupFilesAsTar(String name, Path storage, Set<Path> files, Path backupDir, TransferManager transferManager, String bucket, String backupName, Path backupInfoPath, String recipient, String gpgPassphrase, String gpgVersion) throws IOException, InterruptedException {
		
		Path tarPath = backupDir.resolve(name + ".tar");
		
		// make sure there is no old tar file, because we would append to it
		if (Files.exists(tarPath)) {
			logger.warn(tarPath + " exists already");
			Files.delete(tarPath);
		}
		
		for (Path packageFilePath : files) {
			
			Path packageGpgPath = getPackageGpgPath(packageFilePath);
			
			Path localFilePath = storage.resolve(packageFilePath);		
			Path localGpgPath = backupDir.resolve(packageGpgPath);

			long fileSize;
			String sha512;
						
			try {			
				// checksum of the original file to be checked after restore
				// file read once, cpu bound
				sha512 = parseChecksumLine(ProcessUtils.runStdoutToString(null, "shasum", "-a", "512", localFilePath.toString()));
				fileSize = Files.size(localFilePath);
			} catch (NoSuchFileException e) {
				logger.error("file disappeared during the backup process: " + e.getMessage() + " (probably deleted by some user)");
				continue;
			}
							
			// compress and encrypt
			// file read and written once, cpu bound (shell pipe saves one write and read)
			String cmd = "";
			cmd += ProcessUtils.getPath("lz4") + " -q " + localFilePath.toString();			
			cmd += " | " + ProcessUtils.getPath("gpg") + " --output - --compress-algo none ";
			
			Map<String, String> env = new HashMap<String, String>() {{
				put(ENV_GPG_PASSPHRASE, gpgPassphrase);
			}};
			
			if (recipient != null && !recipient.isEmpty()) {
				// asymmetric encryption using the keys installed on the machine
				cmd += "--recipient " + recipient + " --encrypt -";
			} else if (gpgPassphrase != null && !gpgPassphrase.isEmpty()) {
				/* 
				 * Symmetric encryption
				 * 
				 * Try to hide the passphrase from the process list in case this runs in multiuser 
				 * system (container is safe anyway).
				 * - echo is not visible in the process list because it's a builtin
				 * - process substitution <() creates a anonymous pipe, where the content is not visible in the process list
				*/
				cmd += "--passphrase-file <(echo $" + ENV_GPG_PASSPHRASE + ") ";
				if (gpgVersion.startsWith("2.")) {
					cmd += "--pinentry-mode ";
				}
				
				cmd += "loopback --symmetric -";
			} else {
				throw new IllegalArgumentException("neither " + CONF_BACKUP_GPG_RECIPIENT + " or " + CONF_BACKUP_GPG_PASSPHRASE + " is configured");
			}
			
			
			Files.createDirectories(localGpgPath.getParent());
			ProcessUtils.run(null, localGpgPath.toFile(), env, false, "bash", "-c", cmd);
					
			long gpgFileSize = Files.size(localGpgPath);			
			
			// checksum of the compressed and encrypted file to monitor bit rot on the backup server
			// file read and written once, cpu bound 
			String gpgSha512 = parseChecksumLine(ProcessUtils.runStdoutToString(null, "shasum", "-a", "512", localGpgPath.toString()));
			
			// use --directory to "relativize" paths
			// file read and written once, io bound. Unfortunately tar can't read the file data from stdin
			ProcessUtils.run(null, null, "tar", "-f", tarPath.toString(), "--directory", backupDir.toString(), "--append", getPackageGpgPath(packageFilePath).toString());
			
			Files.delete(localGpgPath);
			
			String line = new InfoLine(packageFilePath, fileSize, sha512, packageGpgPath, gpgFileSize, gpgSha512, backupName).toLine();
			Files.write(backupInfoPath, Collections.singleton(line), Charset.defaultCharset(), 
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		
		upload(transferManager, bucket, backupName, tarPath, true);
				
		Files.delete(tarPath);
	}
	
	public static String getGpgVersion() throws IOException, InterruptedException {
		String output = ProcessUtils.runStdoutToString(null, "gpg", "--version");
		
		String[] rows = output.split("\n");
		if (rows.length == 0) {
			throw new IllegalArgumentException("gpg version parsing failed: " + output);
		}
		
		String[] cols = rows[0].split(" ");
		if (cols.length != 3) {
			throw new IllegalArgumentException("gpg version parsing failed, " + cols.length + " columns (" + rows[0] + ")");
		}
		String version = cols[2];
		logger.info("gpg version " + version);
		return version;
	}
	
	private static void upload(TransferManager transferManager, String bucket, String bucketDir, Path filePath, boolean verbose) throws IOException, AmazonServiceException, AmazonClientException, InterruptedException {
		if (verbose) {
			logger.info("upload " + filePath.getFileName() + " to " + bucket + "/" + bucketDir + " (" + FileUtils.byteCountToDisplaySize(Files.size(filePath)) + ")");		
		}
		Upload upload = transferManager.upload(bucket, bucketDir + "/" + filePath.getFileName(), filePath.toFile());
		upload.waitForCompletion();
	}
	
	public static Path getPackageGpgPath(Path packageFilePath) {
		return Paths.get(packageFilePath.toString() + ".lz4.gpg");
	}
	
	private static String parseChecksumLine(String line) throws IOException {
		String[] parts = line.split(" ");
		if (parts.length == 0) {
			throw new IllegalArgumentException("cannot parse checksum " + line + ", delimiter ' ' not found");
		}
		return parts[0];
	}

	public static void uploadBackupInfo(TransferManager transferManager, String bucket, String backupName,
			Path backupInfoPath) throws AmazonServiceException, AmazonClientException, InterruptedException {
		Upload upload = transferManager.upload(bucket, backupName + "/" + BackupArchiver.BACKUP_INFO, backupInfoPath.toFile());
		upload.waitForCompletion();
	}
	
	public static TransferManager getTransferManager(Config config, String role) {
		String endpoint = config.getString(CONF_BACKUP_S3_ENDPOINT, role);
		String region = config.getString(CONF_BACKUP_S3_REGION, role);
		String access = config.getString(CONF_BACKUP_S3_ACCESS_KEY, role);
		String secret = config.getString(CONF_BACKUP_S3_SECRET_KEY, role);
		String signerOverride = config.getString(COND_BACKUP_S3_SIGNER_OVERRIDE, role);		
		
		if (endpoint == null || region == null || access == null || secret == null) {
			logger.warn("backups are not configured");
		}
		
		return S3Util.getTransferManager(endpoint, region, access, secret, signerOverride);
	}
	
	
	public static String getBackupBucket(Config config, String role) {
		return config.getString(CONF_BACKUP_BUCKET, role);
	}

	public static Timer startBackupTimer(TimerTask timerTask, String role, Config config) {
		
		int backupInterval = Integer.parseInt(config.getString(CONF_BACKUP_INTERVAL, role));
		String backupTimeString = config.getString(CONF_BACKUP_TIME, role);	    
    	
    	int startHour = Integer.parseInt(backupTimeString.split(":")[0]);
	    int startMinute = Integer.parseInt(backupTimeString.split(":")[1]);
	    Calendar firstBackupTime = Calendar.getInstance();
	    if (firstBackupTime.get(Calendar.HOUR_OF_DAY) > startHour || 
	    		(firstBackupTime.get(Calendar.HOUR_OF_DAY) == startHour && 
	    				firstBackupTime.get(Calendar.MINUTE) >= startMinute)) {
	    	firstBackupTime.add(Calendar.DATE, 1);
	    }
    	firstBackupTime.set(Calendar.HOUR_OF_DAY, startHour);
    	firstBackupTime.set(Calendar.MINUTE, startMinute);
    	firstBackupTime.set(Calendar.SECOND, 0);
    	firstBackupTime.set(Calendar.MILLISECOND, 0);
    	logger.info("next " + role + " backup is scheduled at " + firstBackupTime.getTime().toString());
    	logger.info("save " + role + " backups to bucket:  " + BackupUtils.getBackupBucket(config, role));
    	
		Timer backupTimer = new Timer();
		backupTimer.scheduleAtFixedRate(timerTask, firstBackupTime.getTime(), backupInterval * 60 * 60 * 1000);
		return backupTimer;
	}
}
