package fi.csc.chipster.backup;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.UnauthenticatedAdminResource;
import fi.csc.chipster.rest.hibernate.DbBackup;
import fi.csc.chipster.rest.hibernate.HibernateUtil;

/**
 * Backup service for all Chipster databases
 * 
 * Backups are taken in a centralized service, because we wan't only one backup, but 
 * there may be many instances of the actual service. 
 * 
 * @author klemela
 *
 */
public class Backup {
	
	private Logger logger = LogManager.getLogger();
		
	private List<DbBackup> dbBackups;
	private HttpServer adminServer;

	public Backup(Config config) throws IOException {		     
    	
		Set<String> roles = config.getDbBackupRoles();			
		
		dbBackups = roles.stream().map(role -> {
		
			String url = config.getString(HibernateUtil.CONF_DB_URL, role);
			String user = config.getString(HibernateUtil.CONF_DB_USER, role);
			String dbPassword = config.getString(HibernateUtil.CONF_DB_PASS, role);	    	
			
			logger.info("backup " + role + " db in " + url);
			return new DbBackup(config, role, url, user, dbPassword);
			
		}).collect(Collectors.toList());
		
		
		logger.info("starting the admin rest server");
		// this is unauthenticated, because we don't wan't the auth to run when we restore its DB
		this.adminServer = RestUtils.startUnauthenticatedAdminServer(
				new UnauthenticatedAdminResource(), Role.BACKUP, config);
	}

	public void start() {
		dbBackups.stream().forEach(b -> {	
			b.scheduleBackups();
		});			
	}
	
	public static void main(String[] args) throws Exception {

		final Backup service = new Backup(new Config());
		service.start();

		RestUtils.waitForShutdown("backup service", null);
	}


	public void close() {
		RestUtils.shutdown("backup-admin", adminServer);
	}
}
