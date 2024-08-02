package fi.csc.chipster.rest.hibernate;

import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;

import fi.csc.chipster.archive.BackupArchive;
import fi.csc.chipster.archive.GpgBackupUtils;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ProcessUtils;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.hibernate.HibernateUtil.DatabaseConnectionRefused;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.transfer.s3.S3TransferManager;

public class DbBackup implements StatusSource {

	private final static Logger logger = LogManager.getLogger();

	private static final String BACKUP_NAME_POSTFIX_UNCOMPRESSED = ".sql";
	public static final String BACKUP_OBJECT_NAME_PART = "-db-backup_";

	public static final String DB_BACKUPS = "db-backups";

	private Config config;
	private String role;

	private String url;
	private String user;
	private String password;

	private final String backupPrefix;
	private final String backupPostfixUncompressed;

	private SessionFactory sessionFactory;

	private String gpgRecipient;
	private String gpgPassphrase;

	private Path backupRoot;

	private Map<String, Object> stats = new HashMap<String, Object>();

	private S3TransferManager transferManager;

	private String bucket;

	private S3AsyncClient s3AsyncClient;

	public DbBackup(Config config, String role, String url, String user, String password, Path backupRoot)
			throws IOException, InterruptedException {
		this.config = config;
		this.role = role;
		this.url = url;
		this.user = user;
		this.password = password;
		this.backupRoot = backupRoot;

		backupPrefix = role + BACKUP_OBJECT_NAME_PART;
		backupPostfixUncompressed = BACKUP_NAME_POSTFIX_UNCOMPRESSED;

		this.gpgRecipient = GpgBackupUtils.importPublicKey(config, role);
		this.gpgPassphrase = config.getString(GpgBackupUtils.CONF_BACKUP_GPG_PASSPHRASE, role);

		this.bucket = GpgBackupUtils.getBackupBucket(config, role);
		if (bucket.isEmpty()) {
			logger.warn("no backup configuration for " + role + " db");
			return;
		}

		this.s3AsyncClient = GpgBackupUtils.getS3Client(config, role);
		this.transferManager = S3Util.getTransferManager(this.s3AsyncClient);

		Configuration hibernateConf = HibernateUtil.getHibernateConf(new ArrayList<Class<?>>(), url, "none", user,
				password, config, role);
		try {
			// fail fast if there is no Postgres
			HibernateUtil.testConnection(url, user, password);
			sessionFactory = HibernateUtil.buildSessionFactory(hibernateConf);
		} catch (DatabaseConnectionRefused e) {
			logger.error(role + " db backups disabled: " + e.getMessage());
		}
	}

	public void cleanUpAndBackup() {
		stats.clear();
		try {
			dbCleanUp();
		} catch (InterruptedException | IOException e) {
			logger.error(role + " db clean up failed", e);
		}
		try {
			backup();
		} catch (IOException | InterruptedException e) {
			logger.error(role + " db backup failed", e);
		}
	}

	private void dbCleanUp() throws IOException, InterruptedException {

		// can't use runPostgres() because unfortunately vacuumlo has little bit
		// different parameters
		final Map<String, String> env = new HashMap<>();
		env.put("PGPASSWORD", this.password);

		String dbUrl = url.replace("jdbc:", "");

		logger.info(role + " db vacuumlo");
		ProcessUtils.run(null, null, env, true, new String[] { "vacuumlo", "-U", this.user, dbUrl });
	}

	private void backup() throws IOException, InterruptedException {

		if (this.sessionFactory == null) {
			throw new IllegalStateException("no backup configuration for " + role);
		}

		printTableStats();

		Instant now = Instant.now();

		String backupName = backupPrefix + now;
		String backupFileBasename = backupPrefix + now;

		Path backupDir = backupRoot.resolve(backupName);
		Path backupFileUncompressed = backupDir.resolve(backupPrefix + now + backupPostfixUncompressed);
		Path backupInfoPath = backupDir.resolve(BackupArchive.BACKUP_INFO);

		Files.createDirectory(backupDir);

		logger.info("save     " + role + " db backup to " + backupFileUncompressed.toFile().getAbsolutePath());

		// Stream the script to a local file
		runPostgres(null, backupFileUncompressed.toFile(), false, "pg_dump");

		stats.put("lastBackupUncompressedSize", Files.size(backupFileUncompressed));

		GpgBackupUtils.backupFileAsTar(backupFileBasename, backupDir, backupFileUncompressed.getFileName(), backupDir,
				transferManager, bucket, backupName, backupInfoPath, gpgRecipient, gpgPassphrase, config);
		// the backupInfo is not really necessary because there is only one file, but
		// the BackupArchiver expects it
		GpgBackupUtils.uploadBackupInfo(transferManager, bucket, backupName, backupInfoPath);

		FileUtils.deleteDirectory(backupDir.toFile());

		stats.put("lastBackupDuration", Duration.between(now, Instant.now()).toMillis());

		logger.info("db backup of " + role + " done");
	}

	private void runPostgres(File stdinFile, File stdoutFile, boolean showStdout, String... command)
			throws IOException, InterruptedException {

		runPostgres(stdinFile, stdoutFile, this.url, this.user, this.password, showStdout, command);
	}

	public static void runPostgres(File stdinFile, File stdoutFile, String url, String user, String password,
			boolean showStdout, String... command) throws IOException, InterruptedException {

		List<String> cmd = new ArrayList<String>(Arrays.asList(command));
		cmd.add("--dbname=" + url.replace("jdbc:", ""));
		cmd.add("--username=" + user);

		final Map<String, String> env = new HashMap<>();
		env.put("PGPASSWORD", password);

		ProcessUtils.run(stdinFile, stdoutFile, env, showStdout, cmd.toArray(new String[0]));
	}

	private Map<String, Long> getTableStats() {

		return HibernateUtil.runInTransaction(new HibernateRunnable<Map<String, Long>>() {
			@Override
			public Map<String, Long> run(Session hibernateSession) {
				@SuppressWarnings("unchecked")
				List<String> tables = hibernateSession
						.createNativeQuery("SELECT tablename FROM pg_catalog.pg_tables where schemaname='public'")
						.getResultList();

				Map<String, Long> tableRows = new LinkedHashMap<>();

				for (String table : tables) {
					BigInteger rows = (BigInteger) hibernateSession
							.createNativeQuery("SELECT count(*) FROM \"" + table + "\"").getSingleResult();
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

	public static void main(String[] args) throws IOException, InterruptedException {

		Config config = new Config();
		String role = Role.SESSION_DB;

		String url = config.getString(HibernateUtil.CONF_DB_URL, role);
		String user = config.getString(HibernateUtil.CONF_DB_USER, role);
		String dbPassword = config.getString(HibernateUtil.CONF_DB_PASS, role);
		DbBackup dbBackup = new DbBackup(config, role, url, user, dbPassword, Paths.get(DB_BACKUPS));

		dbBackup.dbCleanUp();
		dbBackup.backup();
	}

	public String getRole() {
		return role;
	}

	@Override
	public Map<String, Object> getStatus() {

		Map<String, Object> statsWithRole = stats.keySet().stream()
				.collect(Collectors.toMap(key -> key + ",backupOfRole=" + role, key -> stats.get(key)));

		return statsWithRole;
	}

	public boolean monitoringCheck() {

		int backupInterval = Integer.parseInt(config.getString(GpgBackupUtils.CONF_BACKUP_INTERVAL, role));

		Instant backupTime = GpgBackupUtils.getLatestArchive(s3AsyncClient, backupPrefix, bucket);

		// false if there is no success during two backupIntervals
		return backupTime != null && backupTime.isAfter(Instant.now().minus(2 * backupInterval, ChronoUnit.HOURS));
	}
}
