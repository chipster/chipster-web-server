package fi.csc.chipster.backup;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.archive.GpgBackupUtils;
import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServerComponent;
import fi.csc.chipster.rest.hibernate.DbBackup;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;

/**
 * Backup service for all Chipster databases
 * 
 * Backups are taken in a centralized service, because we wan't only one backup,
 * but
 * there may be many instances of the actual service.
 * 
 * @author klemela
 *
 */
public class Backup implements ServerComponent {

	private Logger logger = LogManager.getLogger();

	private List<DbBackup> dbBackups;
	private HttpServer adminServer;

	private ServiceLocatorClient serviceLocator;

	private AuthenticationClient authService;

	private Config config;

	@SuppressWarnings("unused")
	private Timer backupTimer;

	public Backup(Config config) throws IOException {

		this.config = config;

		String username = Role.BACKUP;
		String password = config.getPassword(username);

		this.serviceLocator = new ServiceLocatorClient(config);
		this.authService = new AuthenticationClient(serviceLocator, username, password, Role.SERVER);
		this.serviceLocator.setCredentials(authService.getCredentials());

		Path backupRoot = Paths.get(DbBackup.DB_BACKUPS);

		Set<String> roles = config.getDbBackupRoles();

		dbBackups = roles.stream().map(role -> {

			String url = config.getString(HibernateUtil.CONF_DB_URL, role);
			String user = config.getString(HibernateUtil.CONF_DB_USER, role);
			String dbPassword = config.getString(HibernateUtil.CONF_DB_PASS, role);

			logger.info("backup " + role + " db in " + url);
			try {
				return new DbBackup(config, role, url, user, dbPassword, backupRoot);
			} catch (IOException | InterruptedException e) {
				logger.error("backup error", e);
				return null;
			}

		}).collect(Collectors.toList());

		logger.info("starting the admin rest server");
		BackupAdminResource adminResource = new BackupAdminResource(dbBackups, config);
		adminResource.addFileSystem("db-backup", backupRoot.toFile());
		this.adminServer = RestUtils.startAdminServer(adminResource, null, Role.BACKUP, config, authService,
				serviceLocator);
	}

	public void start() {

		this.backupTimer = GpgBackupUtils.startBackupTimer(new TimerTask() {
			@Override
			public void run() {
				try {

					dbBackups.stream().forEach(b -> {
						b.cleanUpAndBackup();
					});

				} catch (Exception e) {
					logger.error("backup error", e);
				}
			}
		}, config);

		dbBackups.stream().forEach(b -> {
			logger.info(
					"save " + b.getRole() + " backups to bucket:  "
							+ GpgBackupUtils.getBackupBucket(config, b.getRole()));
		});
	}

	public static void main(String[] args) throws Exception {

		final Backup service = new Backup(new Config());
		service.start();

		RestUtils.waitForShutdown("backup service", (HttpServer) null);
	}

	public void close() {
		RestUtils.shutdown("backup-admin", adminServer);
		authService.close();
	}
}
