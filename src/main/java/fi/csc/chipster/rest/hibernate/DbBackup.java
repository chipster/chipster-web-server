package fi.csc.chipster.rest.hibernate;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.Map.Entry;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.joda.time.Instant;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.Upload;

import fi.csc.chipster.rest.Config;
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
	
	private final Logger logger = LogManager.getLogger();
	
	private static final String BACKUP_NAME_POSTFIX = ".zip";
	private static final String BACKUP_OBJECT_NAME_PART = "-db-backup_";
	
	private static final String CONF_ROLE_DB_BACKUP_TIME = "-db-backup-time";
	private static final String CONF_ROLE_DB_BACKUP_INTERVAL = "-db-backup-interval";
	private static final String CONF_ROLE_DB_RESTORE_KEY = "-db-restore-key";
	private static final String CONF_ROLE_DB_BACKUP_BUCKET = "-db-backup-bucket";
	
	private static final String CONF_DB_BACKUP_EXIT_AFTER_RESTORE = "db-backup-exit-after-restore";
	private static final String COND_DB_BACKUP_S3_SIGNER_OVERRIDE = "db-backup-s3-signer-override";
	private static final String CONF_DB_BACKUP_S3_SECRET_KEY = "db-backup-s3-secret-key";
	private static final String CONF_DB_BACKUP_S3_ACCESS_KEY = "db-backup-s3-access-key";
	private static final String CONF_DB_BACKUP_S3_REGION = "db-backup-s3-region";
	private static final String CONF_DB_BACKUP_S3_ENDPOINT = "db-backup-s3-endpoint";
	
	private static final int ROTATION_DAILY_COUNT = 90;
	private static final int ROTATION_MONTHLY_COUNT = 24;	

	private Config config;
	private String role;	
	private Timer backupTimer;
	private String url;
	private String user;
	private String password;
	
	private final String backupPrefix;
	private final String backupPostfix;

	public DbBackup(Config config, String role, String url, String user, String password) {
		this.config = config;
		this.role = role;
		this.url = url;
		this.user = user;
		this.password = password;
		
		backupPrefix = role + BACKUP_OBJECT_NAME_PART;
		backupPostfix = BACKUP_NAME_POSTFIX;	
	}
	
	public void checkRestore() {
		
		String restoreKey = config.getString(role + CONF_ROLE_DB_RESTORE_KEY);    	
    	boolean exitAfterRestore = config.getBoolean(CONF_DB_BACKUP_EXIT_AFTER_RESTORE);
		
		if (!restoreKey.isEmpty()) {
    		restore(restoreKey, url, user, password);
    		
    		if (exitAfterRestore) {
    			throw new RestoreException(role + "-h2 restore completed, but the service won't start until you remove the " + role + "-db-restore-key from the configuration");
    		}
    	}
	}
	
	public void scheduleBackups(SessionFactory sessionFactory) {
		int backupInterval = config.getInt(role + CONF_ROLE_DB_BACKUP_INTERVAL);
	    String backupTimeString = config.getString(role + CONF_ROLE_DB_BACKUP_TIME);	    
		
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
    	logger.info("Next database backup is scheduled at " + firstBackupTime.getTime().toString());
    	
		backupTimer = new Timer();
		backupTimer.scheduleAtFixedRate(new TimerTask() {			
			@Override
			public void run() {
		    	HibernateUtil.runInTransaction(new HibernateRunnable<Void>() {
					@Override
					public Void run(Session hibernateSession) {				
						try {
							backup(hibernateSession);
						} catch (IOException | AmazonClientException | InterruptedException e) {
							logger.error(role + "-h2 backup failed", e);
						}				
						return null;
					}
				}, sessionFactory);
			}
		//}, firstBackupTime.getTime(), backupInterval * 60 * 60 * 1000);
    	}, new Date(), backupInterval * 60 * 60 * 1000);
	}
    
    private void restore(String key, String url, String user, String password) throws RestoreException {
    	
    	// create an own Hibernate SessionFactory
    	// we must do this before the schema migration because the DB must be empty
    	SessionFactory restoreSessionFactory = HibernateUtil.buildSessionFactory(new ArrayList<Class<?>>(), url, "create", user, password, config, role);
    	
    	Exception error = HibernateUtil.runInTransaction(new HibernateRunnable<Exception>() {
			@Override
			public Exception run(Session hibernateSession) {				
				try {					
					if (!getTableStats(hibernateSession).isEmpty()) {
						throw new RestoreException("Restore is allowed only to an empty DB. Please delete the database files first.");
					}
					
					// set the presigned URL to expire after a few seconds
					// to see if it is enough that the url hasn't expired when the download starts
					TransferManager transferManager = getTransferManager();
					URL url = S3Util.getPresignedUrl(transferManager, getBackupBucket(), key, 5);
					
					logger.info("restore " + role + "-h2 backup from " + url.toString());					
					int rows = hibernateSession
						.createNativeQuery("RUNSCRIPT FROM '" + url.toString() + "' COMPRESSION ZIP")
						.executeUpdate();
					logger.info("backup restored, rows: " + rows);
					
					printTableStats(hibernateSession);
					
				} catch (Exception e) {
					logger.error(role + "-h2 restore failed", e);
					return e;
				}				
				return null;
			}
		}, restoreSessionFactory);
    	
    	restoreSessionFactory.close();
    	
    	if (error != null) {
    		throw new RestoreException(error);
    	}
    }
    
	private void backup(Session hibernateSession) throws IOException, AmazonServiceException, AmazonClientException, InterruptedException {			
		
		String bucket = getBackupBucket();
		if (bucket.isEmpty()) {
			logger.warn("no backup configuration for " + role + "-h2");
			return;
		}
		printTableStats(hibernateSession);
		
		File backupFile = new File("db-backups", backupPrefix + Instant.now() + BACKUP_NAME_POSTFIX);		
		
		logger.info("save " + role + "-h2 backup to " + backupFile.getAbsolutePath());
		hibernateSession.createNativeQuery("SCRIPT TO '" + backupFile.getAbsolutePath() + "' COMPRESSION ZIP").getResultList();
		
		logger.info("upload " + role + "-h2 backup");
				
		TransferManager transferManager = getTransferManager();
		Upload upload = transferManager.upload(bucket, backupFile.getName(), backupFile);
		upload.waitForCompletion();		
		
		backupFile.delete();
		
		removeOldBackups(transferManager, bucket, backupPrefix, backupPostfix);
	}
	
	private Map<String, Long> getTableStats(Session hibernateSession) {
		@SuppressWarnings("unchecked")
		List<Object[]> tables = hibernateSession.createNativeQuery("SHOW TABLES").getResultList();
		
		Map<String, Long> tableRows = new LinkedHashMap<>();
		
		for (Object[] tableResult : tables) {		
			String table = tableResult[0].toString();			
			// the second array element is "PUBLIC"			
			BigInteger rows = (BigInteger) hibernateSession.createNativeQuery("SELECT count(*) FROM \"" + table + "\"").getSingleResult();
			tableRows.put(table, rows.longValue());
		}
		return tableRows;
	}
	
	public void printTableStats(Session hibernateSession) {
		String logLine = "table row counts: ";
		for (Entry<String, Long> entry : getTableStats(hibernateSession).entrySet()) {
			logLine += entry.getKey() + " " + entry.getValue() + ", ";			
		}		
		logger.info(logLine);
	}

	private TransferManager getTransferManager() {
		String endpoint = config.getString(CONF_DB_BACKUP_S3_ENDPOINT);
		String region = config.getString(CONF_DB_BACKUP_S3_REGION);
		String access = config.getString(CONF_DB_BACKUP_S3_ACCESS_KEY);
		String secret = config.getString(CONF_DB_BACKUP_S3_SECRET_KEY);
		String signerOverride = config.getString(COND_DB_BACKUP_S3_SIGNER_OVERRIDE);		
		
		if (endpoint == null || region == null || access == null || secret == null) {
			logger.warn("backups are not configured");
		}
		
		return S3Util.getTransferManager(endpoint, region, access, secret, signerOverride);
	}
	
	private String getBackupBucket() {
		return config.getString(role + CONF_ROLE_DB_BACKUP_BUCKET);
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
	private void removeOldBackups(TransferManager transferManager, String bucket, String backupPrefix, String backupPostfix) {
		logger.info("list " + role + "-h2 backups");
		List<S3ObjectSummary> summaries = S3Util.getObjects(transferManager, bucket);
				
		logger.info(summaries.size() + " backups found, total size " 
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSize(summaries)));
		// all backups
		TreeMap<Instant, S3ObjectSummary> filesToDelete = BackupRotation2.parse(summaries, backupPrefix, backupPostfix);
				
		TreeMap<Instant, S3ObjectSummary> daily = BackupRotation2.getFirstOfEachDay(filesToDelete);
		TreeMap<Instant, S3ObjectSummary> monthly = BackupRotation2.getFirstOfEachMonth(filesToDelete);
		
		// keep these
		TreeMap<Instant, S3ObjectSummary> latestDaily = BackupRotation2.getLast(daily, ROTATION_DAILY_COUNT);
		TreeMap<Instant, S3ObjectSummary> latestMonthly = BackupRotation2.getLast(monthly, ROTATION_MONTHLY_COUNT);
		
		logger.info(latestDaily.size() + " daily backups kept, total size "
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSize(latestDaily.values())));
		logger.info(latestMonthly.size() + " monthly backups kept, total size "
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSize(latestMonthly.values())));
		
		// remove the backups to keep from the list of all backups to get those that we want to remove 
		filesToDelete = BackupRotation2.removeAll(filesToDelete, latestDaily.keySet());
		filesToDelete = BackupRotation2.removeAll(filesToDelete, latestMonthly.keySet());
				
		for (S3ObjectSummary obj : filesToDelete.values()) {
			logger.info("delete backup " + obj.getKey());
			transferManager.getAmazonS3Client().deleteObject(bucket, obj.getKey());
		}	
			
		logger.info(filesToDelete.size() + " backups deleted, total size "
				+ FileUtils.byteCountToDisplaySize(BackupRotation2.getTotalSize(filesToDelete.values())));
	}

}
