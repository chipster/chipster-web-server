package fi.csc.chipster.rest.hibernate;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.Download;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ProcessUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;

public class DbBackup {

	public class RestoreException extends RuntimeException {
		
		public RestoreException(Exception cause) {
			super(cause);
		}
		
		public RestoreException(String msg) {
			super(msg);
		}		
	}
	
	private static final String CONF_DB_BACKUP_DAILY_COUNT_FILE = "db-backup-daily-count-file";
	private static final String CONF_DB_BACKUP_MONTHLY_COUNT_S3 = "db-backup-monthly-count-s3";
	private static final String CONF_DB_BACKUP_DAILY_COUNT_S3 = "db-backup-daily-count-s3";

	
	private final Logger logger = LogManager.getLogger();
	
	private static final String BACKUP_NAME_POSTFIX = ".sql.lz4";
	private static final String BACKUP_NAME_POSTFIX_UNCOMPRESSED = ".sql";
	private static final String BACKUP_OBJECT_NAME_PART = "-db-backup_";
	
	private static final String CONF_DB_BACKUP_TIME = "db-backup-time";
	private static final String CONF_DB_BACKUP_INTERVAL = "db-backup-interval";
	private static final String CONF_DB_RESTORE_KEY = "db-restore-key";
	private static final String CONF_DB_BACKUP_BUCKET = "db-backup-bucket";
	
	private static final String COND_DB_BACKUP_S3_SIGNER_OVERRIDE = "db-backup-s3-signer-override";
	private static final String CONF_DB_BACKUP_S3_SECRET_KEY = "db-backup-s3-secret-key";
	private static final String CONF_DB_BACKUP_S3_ACCESS_KEY = "db-backup-s3-access-key";
	private static final String CONF_DB_BACKUP_S3_REGION = "db-backup-s3-region";
	private static final String CONF_DB_BACKUP_S3_ENDPOINT = "db-backup-s3-endpoint";

	private Config config;
	private String role;	
	private Timer backupTimer;
	private String url;
	private String user;
	private String password;
	
	private final String backupPrefix;
	private final String backupPostfix;
	private final String backupPostfixUncompressed;

	private int dailyCountS3;
	private int monthlyCountS3;
	private int dailyCountFile;
	private SessionFactory sessionFactory;

	public DbBackup(Config config, String role, String url, String user, String password) {
		this.config = config;
		this.role = role;
		this.url = url;
		this.user = user;
		this.password = password;
		
		backupPrefix = role + BACKUP_OBJECT_NAME_PART;
		backupPostfix = BACKUP_NAME_POSTFIX;
		backupPostfixUncompressed = BACKUP_NAME_POSTFIX_UNCOMPRESSED;
		dailyCountS3 = Math.max(3, Integer.parseInt(config.getString(CONF_DB_BACKUP_DAILY_COUNT_S3, role)));
		monthlyCountS3 = Integer.parseInt(config.getString(CONF_DB_BACKUP_MONTHLY_COUNT_S3, role));
		dailyCountFile = Integer.parseInt(config.getString(CONF_DB_BACKUP_DAILY_COUNT_FILE, role));
		
		sessionFactory = HibernateUtil.buildSessionFactory(new ArrayList<Class<?>>(), url, "none", user, password, config, role);
	}
	
	public void checkRestore() {
		
		String restoreKey = getRestoryKey(config, role);
		
		if (!restoreKey.isEmpty()) {
    		restore(restoreKey);
    		// don't start backups when restoring to make it safer to restore from the backups of some other installation    		
    		throw new RestoreException(role + " db restore completed. Remove the db-restore-key-" + role + " from the configuration, restart the Backup service and start all other services");
    	}
	}
	
	public static String getRestoryKey(Config config, String role) {
		return config.getString(CONF_DB_RESTORE_KEY, role);
	}

	public void scheduleBackups() {
		int backupInterval = Integer.parseInt(config.getString(CONF_DB_BACKUP_INTERVAL, role));
	    String backupTimeString = config.getString(CONF_DB_BACKUP_TIME, role);	    
		
		startBackupTimer(backupInterval, backupTimeString, sessionFactory);		
	}
	
	private void startBackupTimer(int backupInterval, String backupTimeString, SessionFactory sessionFactory) {
    	
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
    	logger.info("next " + role + " db backup is scheduled at " + firstBackupTime.getTime().toString());
    	logger.info("save " + role + " db backups to bucket:  " + getBackupBucket());
    	
		backupTimer = new Timer();
		backupTimer.scheduleAtFixedRate(new TimerTask() {			
			@Override
			public void run() {		    					
				try {
					backup();
				} catch (IOException | AmazonClientException | InterruptedException e) {
					logger.error(role + " db backup failed", e);
				}				
				
			}
		}, firstBackupTime.getTime(), backupInterval * 60 * 60 * 1000);
//    	}, new Date(), backupInterval * 60 * 60 * 1000);
	}
    
    private void restore(String key) throws RestoreException {
    	
    	if (!getTableStats().isEmpty()) {
			throw new RestoreException("Restore is allowed only to an empty DB. Please stop all services using the DB, delete the DB files and restart the DB process.");
		}
    	
    	File restoreFile = new File("db-backups", role + "-db-restore.sql.lz4");
    	File restoreFileExtracted = new File("db-backups", role + "-db-restore.sql");
    	
    	try {	
			logger.info("download " + role + " db backup to " + restoreFile);
			
			TransferManager transferManager = getTransferManager();
			Download download = transferManager.download(getBackupBucket(), key, restoreFile);
			download.waitForCompletion();
			
			logger.info("extract  " + role + " db backup");
			ProcessUtils.run(restoreFile, restoreFileExtracted, "lz4",  "-d");
			
			logger.info("restore  " + role + " db backup from " + restoreFileExtracted);
				
			runPostgres(restoreFileExtracted, null, "psql");
						
			logger.info("backup restored");
			
			printTableStats();
    	} catch (AmazonClientException | InterruptedException | IOException e) {
			logger.error(role + "db restore failed", e);
			throw new RestoreException(e);
		} finally {
			restoreFile.delete();
			restoreFileExtracted.delete();
		}
    }
    
	private void backup() throws IOException, AmazonServiceException, AmazonClientException, InterruptedException {			
		
		String bucket = getBackupBucket();
		if (bucket.isEmpty()) {
			logger.warn("no backup configuration for " + role + " db");
			return;
		}
		
		printTableStats();
		
		Instant now = Instant.now();
		
		File backupFileUncompressed = new File("db-backups", backupPrefix + now + backupPostfixUncompressed);
		File backupFile = new File("db-backups", backupPrefix + now + backupPostfix);		
		
		logger.info("save     " + role + " db backup to " + backupFileUncompressed.getAbsolutePath());
		
		// Stream the script to a local file
		runPostgres(null, backupFileUncompressed, "pg_dump");
		logger.info("compress " + role + " db backup (" + FileUtils.byteCountToDisplaySize(backupFileUncompressed.length()) + ")");
		ProcessUtils.run(backupFileUncompressed, backupFile, "lz4");
		backupFileUncompressed.delete();
		
		logger.info("upload   " + role + " db backup (" + FileUtils.byteCountToDisplaySize(backupFile.length()) + ")");
				
		TransferManager transferManager = getTransferManager();
		Upload upload = transferManager.upload(bucket, backupFile.getName(), backupFile);
		upload.waitForCompletion();		
		
		removeOldFileBackups(backupFile.getParentFile(), backupPrefix, backupPostfix);
		removeOldS3Backups(transferManager, bucket, backupPrefix, backupPostfix);
	}
	
	private void runPostgres(File stdinFile, File stdoutFile, String command) throws IOException, InterruptedException {
				
		List<String> cmd = new ArrayList<String>();
		cmd.add(command);		
		cmd.add("--dbname=" + this.url.replace("jdbc:", ""));
		cmd.add("--username=" + this.user);
		
		final Map<String, String> env = new HashMap<>();
        env.put("PGPASSWORD", this.password);
		
		ProcessUtils.run(stdinFile, stdoutFile, env, cmd.toArray(new String[0]));
	}
	
	private Map<String, Long> getTableStats() {
		
		return HibernateUtil.runInTransaction(new HibernateRunnable<Map<String, Long>>() {
			@Override
			public Map<String, Long> run(Session hibernateSession) {
				@SuppressWarnings("unchecked")
				List<String> tables = hibernateSession.createNativeQuery("SELECT tablename FROM pg_catalog.pg_tables where schemaname='public'").getResultList();
				
				Map<String, Long> tableRows = new LinkedHashMap<>();
				
				for (String table : tables) {								
					BigInteger rows = (BigInteger) hibernateSession.createNativeQuery("SELECT count(*) FROM \"" + table + "\"").getSingleResult();
					tableRows.put(table, rows.longValue());
				}
				return tableRows;
			}
		}, this.sessionFactory);
	}
	
	public void printTableStats() {
		String logLine = "table row counts: ";
		for (Entry<String, Long> entry : getTableStats().entrySet()) {
			logLine += entry.getKey() + " " + entry.getValue() + ", ";			
		}		
		logger.info(logLine);
	}

	private TransferManager getTransferManager() {
		String endpoint = config.getString(CONF_DB_BACKUP_S3_ENDPOINT, role);
		String region = config.getString(CONF_DB_BACKUP_S3_REGION, role);
		String access = config.getString(CONF_DB_BACKUP_S3_ACCESS_KEY, role);
		String secret = config.getString(CONF_DB_BACKUP_S3_SECRET_KEY, role);
		String signerOverride = config.getString(COND_DB_BACKUP_S3_SIGNER_OVERRIDE, role);		
		
		if (endpoint == null || region == null || access == null || secret == null) {
			logger.warn("backups are not configured");
		}
		
		return S3Util.getTransferManager(endpoint, region, access, secret, signerOverride);
	}
	
	private String getBackupBucket() {
		return config.getString(CONF_DB_BACKUP_BUCKET, role);
	}

	/**
	 * Remove old backups
	 * 
	 * Keep x daily backups and y monthly backups. This is relatively safe even if the 
	 * server time was incorrect or the backups were created more often. Files
	 * created during the clean-up are kept.
	 * 
	 * @param transferManager
	 * @param bucket
	 * @param backupPrefix
	 * @param backupPostfix
	 */
	private void removeOldS3Backups(TransferManager transferManager, String bucket, String backupPrefix, String backupPostfix) {
		logger.info("list " + role + " db s3 backups");
		List<S3ObjectSummary> summaries = S3Util.getObjects(transferManager, bucket);
		
		// all backups
		TreeMap<Instant, S3ObjectSummary> filesToDelete = BackupRotation2.parse(summaries, backupPrefix, backupPostfix, o -> o.getKey());
		
		logger.info(summaries.size() + " s3 backups found, total size " 
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSizeS3(filesToDelete.values())));
				
		TreeMap<Instant, S3ObjectSummary> daily = BackupRotation2.getFirstOfEachDay(filesToDelete);
		TreeMap<Instant, S3ObjectSummary> monthly = BackupRotation2.getFirstOfEachMonth(filesToDelete);
		
		// keep these
		TreeMap<Instant, S3ObjectSummary> latestDaily = BackupRotation2.getLast(daily, dailyCountS3);
		TreeMap<Instant, S3ObjectSummary> latestMonthly = BackupRotation2.getLast(monthly, monthlyCountS3);
		
		logger.info(latestDaily.size() + " daily s3 backups kept, total size "
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSizeS3(latestDaily.values())));
		logger.info(latestMonthly.size() + " monthly s3 backups kept, total size "
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSizeS3(latestMonthly.values())));
		
		// remove the backups to keep from the list of all backups to get those that we want to remove 
		filesToDelete = BackupRotation2.removeAll(filesToDelete, latestDaily.keySet());
		filesToDelete = BackupRotation2.removeAll(filesToDelete, latestMonthly.keySet());
				
		for (S3ObjectSummary obj : filesToDelete.values()) {
			logger.info("delete s3 backup " + obj.getKey());
			transferManager.getAmazonS3Client().deleteObject(bucket, obj.getKey());
		}	
			
		logger.info(filesToDelete.size() + " s3 backups deleted, total size "
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSizeS3(filesToDelete.values())));
	}
	
	private void removeOldFileBackups(File dir, String backupPrefix, String backupPostfix) {
		logger.info("list " + role + " db backup files");	
		List<File> allFiles = Arrays.asList(dir.listFiles());
		
		// all backups
		TreeMap<Instant, File> filesToDelete = BackupRotation2.parse(allFiles, backupPrefix, backupPostfix, f -> f.getName());
		
		logger.info(allFiles.size() + " backup files found, total size " 
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSizeFiles(filesToDelete.values())));
				
		TreeMap<Instant, File> daily = BackupRotation2.getFirstOfEachDay(filesToDelete);
		
		// keep these
		TreeMap<Instant, File> latestDaily = BackupRotation2.getLast(daily, dailyCountFile);
		
		logger.info(latestDaily.size() + " daily backup files kept, total size "
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSizeFiles(latestDaily.values())));
		
		// remove the backups to keep from the list of all backups to get those that we want to remove 
		filesToDelete = BackupRotation2.removeAll(filesToDelete, latestDaily.keySet());
				
		for (File obj : filesToDelete.values()) {
			logger.info("delete backup file " + obj.getName());
			obj.delete();
		}	
			
		logger.info(filesToDelete.size() + " backup files deleted, total size "
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSizeFiles(filesToDelete.values())));
	}
}
