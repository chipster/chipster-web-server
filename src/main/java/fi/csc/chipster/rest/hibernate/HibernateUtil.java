package fi.csc.chipster.rest.hibernate;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.flywaydb.core.Flyway;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;
import org.hibernate.tool.schema.spi.SchemaManagementException;

import fi.csc.chipster.rest.Config;

public class HibernateUtil {


	private static final String EXPORT_PATH_PREFIX = "src/main/resources/flyway/";
	private static final String EXPORT_PATH_POSTFIX = "/exported_hibernate_schema.ddl";
	private static final String MIGRATION_RESOURCE_PREFIX = "classpath:flyway.";

	private static Logger logger = LogManager.getLogger();
	
    private SessionFactory sessionFactory;

	private Config config;

	private String role;

    public HibernateUtil(Config config, String role, List<Class<?>> hibernateClasses) {
		this.config = config;
		this.role = role;
		this.init(hibernateClasses);
	}
    
    public void init(List<Class<?>> hibernateClasses) {
    	    	
    	String exportFile = EXPORT_PATH_PREFIX + role + EXPORT_PATH_POSTFIX;
    	this.exportHibernateSchema(hibernateClasses, exportFile);
    	
    	String url = config.getString(role + "-db-url");
    	String user = config.getString(role + "-db-user");
    	String password = config.getString(role + "-db-pass");
    			
    	this.migrateSchema(url, user, password);
		
		if (password.length() < 8) {
			logger.warn("weak db passowrd for " + role + ", length " + password.length());
		}
        	
		// make sure the Flyway migrations match with the current Hibernate classes  
		String hbm2ddlAuto = "validate";
		
		logger.info("connect to db " + url);
		try {
			this.sessionFactory = buildSessionFactory(hibernateClasses, url, hbm2ddlAuto, user, password);
			logger.info("connected");
			
    	} catch (SchemaManagementException e) {
    		logger.error(
    				"Schema validation failed. If you have changed the Hibernate classes, you must "
    				+ "write a migration script for the existing databases. "
    				+ "Your current schema, based on the Hibernate "
    				+ "classes, is exported to " + exportFile + ". Compare it against its "
    				+ "previous version in git. The .sql files in the same folder define the state "
    				+ "of the existing databases. Add a new file .sql file and the necessary commands for "
    				+ "migrating the existing databases to the new schema.", e);
    		throw e;
    	}
    }
    
    private void migrateSchema(String url, String user, String password) {
		Flyway flyway = new Flyway();
		flyway.setSchemas(role);
		flyway.setDataSource(url, user, password);
		
		// location of migration files
		flyway.setLocations(MIGRATION_RESOURCE_PREFIX + role);
		
		// enable once for converting pre-Flyway databases
		String baselineKey = role + "-db-baseline";
		if (config.getBoolean(baselineKey)) {
			logger.warn("Protection against migrating a wrong database is disabled. Set " + baselineKey + " to false.");
			flyway.baseline();
		}
		
		flyway.migrate();
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
    	SessionFactory exampleSessionFactory = buildSessionFactory(hibernateClasses, url, hbm2ddlAuto, "sa", "");
    	
    	this.runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {				
				hibernateSession.createNativeQuery("SCRIPT NODATA TO '" + file + "'").getResultList();				
				return null;
			}
		}, exampleSessionFactory);
    	
    	exampleSessionFactory.close();
    }

	public SessionFactory buildSessionFactory(List<Class<?>> hibernateClasses, String url, String hbm2ddlAuto, String user, String password) {    	
    	    		
		final org.hibernate.cfg.Configuration hibernateConf = new org.hibernate.cfg.Configuration();
		
		hibernateConf.setProperty(Environment.DRIVER, config.getString(role + "-db-driver"));
		hibernateConf.setProperty(Environment.URL, url);
		hibernateConf.setProperty(Environment.USER, user);
		hibernateConf.setProperty(Environment.PASS, password);
		hibernateConf.setProperty(Environment.DIALECT, config.getString(role + "-db-dialect"));
		hibernateConf.setProperty(Environment.SHOW_SQL, config.getString(role + "-db-show-sql"));
		hibernateConf.setProperty(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
		hibernateConf.setProperty("hibernate.c3p0.min_size", config.getString(role + "-db-c3p0-min-size"));
		hibernateConf.setProperty("hibernate.hbm2ddl.auto", hbm2ddlAuto);
		
		for (Class<?> c : hibernateClasses) {
			hibernateConf.addAnnotatedClass(c);
		}    		    	   
		 
		return hibernateConf.buildSessionFactory(
				new StandardServiceRegistryBuilder()
				.applySettings(hibernateConf.getProperties())
				.build());
    }

	public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

	public org.hibernate.Session beginTransaction() {
		org.hibernate.Session session = getSessionFactory().getCurrentSession();
		session.beginTransaction();
		return session;
	}

	public void commit() {
		getSessionFactory().getCurrentSession().getTransaction().commit();
	}
	
	public void rollback() {
		getSessionFactory().getCurrentSession().getTransaction().rollback();
	}

	public org.hibernate.Session session() {
		return getSessionFactory().getCurrentSession();
	}

	public void rollbackIfActive() {
		if (session().getTransaction().getStatus().canRollback()) {
			session().getTransaction().rollback();
		}		
	}

	public <T> T runInTransaction(HibernateRunnable<T> runnable) {
		return this.runInTransaction(runnable, getSessionFactory());
	}
	public <T> T runInTransaction(HibernateRunnable<T> runnable, SessionFactory sessionFactory) {
		
		T returnObj = null;
		Session session = sessionFactory.openSession();
		org.hibernate.Transaction transaction = session.beginTransaction();
		try {
			returnObj = runnable.run(session);
			transaction.commit();
		} catch (Exception e) {
			transaction.rollback();
			logger.error("transaction failed", e);
		}
		session.close();
		return returnObj;
	}
	
	public interface HibernateRunnable<T> {
		public T run(Session hibernateSession);
	}
}