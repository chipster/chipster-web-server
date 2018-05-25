package fi.csc.chipster.rest.hibernate;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.tool.schema.spi.SchemaManagementException;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;

public class DbSchema {
	
	private Logger logger = LogManager.getLogger();
	
	private static final String CONF_DB_BASELINE = "db-baseline-version";
	private static final String EXPORT_PATH_PREFIX = "src/main/resources/flyway/";
	private static final String EXPORT_PATH_POSTFIX = "/exported_hibernate_schema.ddl";
	private static final String MIGRATION_RESOURCE_PREFIX = "classpath:flyway.";
	
	private Config config;
	private String role;


	public DbSchema(Config config, String role) {
		this.config = config;
		this.role = role;
	}

	public void migrate(String url, String user, String password) {
		Flyway flyway = new Flyway();
		flyway.setSchemas("PUBLIC");
		flyway.setDataSource(url, user, password);
		
		// location of migration files
		flyway.setLocations(MIGRATION_RESOURCE_PREFIX + role);
		
		// enable once for converting pre-Flyway databases
		String baselineKey = CONF_DB_BASELINE + "-" + role;
		String baselineVersion = config.getString(CONF_DB_BASELINE, role);
		if (!baselineVersion.isEmpty()) {
			logger.info("baseline " + role + "-h2 to schema version " + baselineVersion);
			flyway.setBaselineVersionAsString(baselineVersion);
			flyway.baseline();
			logger.warn("protection against migrating a wrong database is disabled. Remove " + CONF_DB_BASELINE + "-" + role + " from the configuration");			
		}	
		
		try {
			flyway.migrate();
		
		} catch (FlywayException e) {
			// Let's print additional instructions for Chipster maintainers. Unfortunately the exception is really generic.
			// Matching the message may break easily, but maybe it's fine for this non-critical task.
			if (e.getMessage().contains("Found non-empty schema") && e.getMessage().contains("without schema history table")) {
				logger.error("The DB has a schema, but it doesn't have a flyway_schema_history table. If you know that your DB "
						+ "schema is identical with some Chipster schema version, you can create the flyway_schema_history_table by setting the "
						+ "configuration item " + baselineKey + ". This won't change the schema itself and the Flyway won't allow "
						+ "you to retry failed migrations, so make sure you have a DB backups at hand before trying this.");
			}
			throw e;
			
		} catch (SchemaManagementException e) {
			logger.error("The DB schema isn't what the flyway_schema_history says. Most likely someone "
					+ "has made manual changes to the schema or used the " + baselineKey + " setting in the DB "
					+ "that really doesn't really match with that schema version.");
			throw e;
		}
	}

	/**
     * Export the Hibernate schema to a file
     * 
     * Create a dummy in-memory DB from the given Hibernate classes and write
     * its schema to a file. After making changes to the classes, changes in this file
     * (tracked in git) should show what kind of DB migration scripts are needed. 
     * 
     * @param hibernateClasses
     * @param file
     */
    public void exportHibernateSchema(List<Class<?>> hibernateClasses, String file) {
    	    	
    	// private anonymous in-memory db
    	String url = "jdbc:h2:mem:";
    	// drop old databases, create new and drop when closed
    	String hbm2ddlAuto = "create-drop";
    	
		logger.info("export the current Hibernate schema to " + file);
		// hard-coded credentials are fine for this local one time use
    	SessionFactory exampleSessionFactory = HibernateUtil.buildSessionFactory(hibernateClasses, url, hbm2ddlAuto, "sa", "", config, role);
    	
    	HibernateUtil.runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {				
				hibernateSession.createNativeQuery("SCRIPT NODATA TO '" + file + "'").getResultList();				
				return null;
			}
		}, exampleSessionFactory);
    	
    	exampleSessionFactory.close();
    }


	public void export(List<Class<?>> hibernateClasses) {
    	String exportFile = getExportFile();
    	this.exportHibernateSchema(hibernateClasses, exportFile);
	}

	private String getExportFile() {
		return EXPORT_PATH_PREFIX + role + EXPORT_PATH_POSTFIX;
	}

	public void printSchemaError(SchemaManagementException e) {
		logger.error(
				"The Hibernate Java classses don't match with the current DB schema. Either the "
				+ "Hibernate classes have changed or the DB schema is wrong. Reason 1: If you have changed the Hibernate classes, you must "
				+ "write a migration script for the existing databases. "
				+ "Your current schema, based on the Hibernate "
				+ "classes, is exported to " + getExportFile() + ". Compare it against its "
				+ "previous version in git. The .sql files in the same folder define the state "
				+ "of the existing databases. Add a new .sql file and the necessary commands for "
				+ "migrating the existing databases to the new schema. Reason 2: your database schema may be wrong because "
				+ "of using Flyway baseline for a database that really isn't in that version, changes made by different "
				+ "code version or manual changes.", e);
	}

}
