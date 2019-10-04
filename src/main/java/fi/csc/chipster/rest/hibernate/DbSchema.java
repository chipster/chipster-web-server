package fi.csc.chipster.rest.hibernate;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.flywaydb.core.Flyway;
import org.hibernate.boot.MetadataBuilder;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;
import org.hibernate.tool.hbm2ddl.SchemaExport;
import org.hibernate.tool.schema.TargetType;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.hibernate.usertype.UserType;

public class DbSchema {
	
	private Logger logger = LogManager.getLogger();
	
	private static final String EXPORT_PATH_PREFIX = "src/main/resources/flyway/";
	private static final String EXPORT_PATH_POSTFIX = "/exported_hibernate_schema.ddl";
	private static final String MIGRATION_RESOURCE_PREFIX = "classpath:flyway.";
	
	private String role;


	public DbSchema(String role) {
		this.role = role;
	}

	public void migrate(String url, String user, String password) {

		Flyway flyway = Flyway.configure()
			.dataSource(url, user, password)
			// location of migration files
			.locations(MIGRATION_RESOURCE_PREFIX + role)
			.load();
						
		flyway.migrate();
	}

	/**
     * Export the Hibernate schema to a file
     * 
     * After making changes to the classes, changes in this file
     * (tracked in git) should show what kind of DB migration scripts are needed. 
     * 
     * @param hibernateClasses
     * @param file
	 * @param driver 
	 * @param url 
	 * @throws InterruptedException 
	 * @throws IOException 
	 * @throws SQLException 
     */
    public void exportHibernateSchema(List<Class<?>> hibernateClasses, String file, String dialect, String url) {
    	
    	Map<String, String> settings = new HashMap<>();
		settings.put(Environment.DIALECT, dialect);
		settings.put(Environment.URL, url);
 
        MetadataSources metadata = new MetadataSources(
        		new StandardServiceRegistryBuilder()
                        .applySettings(settings)
                        .build());
        
		for (Class<?> c : hibernateClasses) {
			metadata.addAnnotatedClass(c);
		}
		
		MetadataBuilder metadataBuilder = metadata.getMetadataBuilder();
				
		HashMap<String, UserType> types = HibernateUtil.getUserTypes(true); 		
		for (String name : types.keySet()) {
			metadataBuilder.applyBasicType(types.get(name), name);
		}		                
        
        File exportFile = new File(file);
        if (exportFile.exists()) {
        	exportFile.delete();
        }
        
        new SchemaExport()
	        .setFormat(true)
	        .setOutputFile(file)
	        .setDelimiter(";")
	        .create(EnumSet.of(TargetType.SCRIPT), metadataBuilder.build());    	
    }


	public void export(List<Class<?>> hibernateClasses, String dialect, String url) {
    	String exportFile = getExportFile();
    	this.exportHibernateSchema(hibernateClasses, exportFile, dialect, url);
	}

	private String getExportFile() {
		return EXPORT_PATH_PREFIX + role + EXPORT_PATH_POSTFIX;
	}

	public void printSchemaError(SchemaManagementException e) {
		logger.error("\n"
				+ "\n"
				+ "Hibernate Java classses don't match with the current DB schema. \n"
				+ "Reason 1: If you have changed the Hibernate classes, you must: \n"
				+ " - compare your current Hibernate schema is (exported to " + getExportFile() + ")\n"
				+ "   against its previous versions in git\n"
				+ " - write a migration script (see the .sql files in the same dir)\n"
				+ " - commit migration files and " + getExportFile() + " for a reference point for the future changes\n"
				+ "\n"
				+ "Reason 2: Incorrect use of Flyway baseline for a database that really isn't in that version\n"
				+ "Reason 3: Other version of code has made schema changes\n"
				+ "Reason 4: Manual schema changes\n"
				+ "\n", e);
	}

}
