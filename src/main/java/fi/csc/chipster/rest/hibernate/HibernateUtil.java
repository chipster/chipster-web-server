package fi.csc.chipster.rest.hibernate;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;
import org.hibernate.tool.schema.spi.SchemaManagementException;

import fi.csc.chipster.rest.Config;

public class HibernateUtil {

	private static final String CONF_DB_C3P0_MIN_SIZE = "db-c3p0-min-size";
	private static final String CONF_DB_SHOW_SQL = "db-show-sql";
	private static final String CONF_DB_DIALECT = "db-dialect";
	private static final String CONF_DB_DRIVER = "db-driver";

	public static final String CONF_DB_PASS = "db-pass";
	public static final String CONF_DB_USER = "db-user";
	public static final String CONF_DB_URL = "db-url";

	private static Logger logger = LogManager.getLogger();
	
    private SessionFactory sessionFactory;

	private Config config;

	private String role;

	private DbSchema dbSchema;

    public HibernateUtil(Config config, String role, List<Class<?>> hibernateClasses) {
		this.config = config;
		this.role = role;
	
		this.init(hibernateClasses);
	}
    
    public void init(List<Class<?>> hibernateClasses) {
    	
    	// The restore configuration is really used in the Backups service, but configuring it also for the actual service 
    	// might be a handy way to prevent it from creating the schema
    	String restoreKey = DbBackup.getRestoryKey(config, role);
    	if (!restoreKey.isEmpty()) {
    		throw new RuntimeException("Configuration " + restoreKey + " is set. Refusing to start while the DB is being restored.");
    	}
    	    	
    	this.dbSchema = new DbSchema(config, role);
    	this.dbSchema.export(hibernateClasses);
    	
    	String url = config.getString(CONF_DB_URL, role);
    	String user = config.getString(CONF_DB_USER, role);
    	String password = config.getString(CONF_DB_PASS, role);
    	
    	this.dbSchema.migrate(url, user, password);    	

		if (password.length() < 8) {
			logger.warn("weak db passowrd for " + role + ", length " + password.length());
		}
        	
		// make sure the Flyway migrations match with the current Hibernate classes  
		String hbm2ddlAuto = "validate";
		
		logger.info("connect to db " + url);
		try {
			this.sessionFactory = buildSessionFactory(hibernateClasses, url, hbm2ddlAuto, user, password, config, role);
			logger.info("connected");
			
    	} catch (SchemaManagementException e) {
    		this.dbSchema.printSchemaError(e);
    		throw e;
    	}
    }
    
	public static SessionFactory buildSessionFactory(List<Class<?>> hibernateClasses, String url, String hbm2ddlAuto, String user, String password, Config config, String role) {    	
    	    		
		final org.hibernate.cfg.Configuration hibernateConf = new org.hibernate.cfg.Configuration();
				
		hibernateConf.setProperty(Environment.DRIVER, config.getString(CONF_DB_DRIVER, role));
		hibernateConf.setProperty(Environment.URL, url);
		hibernateConf.setProperty(Environment.USER, user);
		hibernateConf.setProperty(Environment.PASS, password);
		hibernateConf.setProperty(Environment.DIALECT, config.getString(CONF_DB_DIALECT, role));
		hibernateConf.setProperty(Environment.SHOW_SQL, config.getString(CONF_DB_SHOW_SQL, role));
		hibernateConf.setProperty(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");		
		hibernateConf.setProperty("hibernate.c3p0.min_size", config.getString(CONF_DB_C3P0_MIN_SIZE, role));
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
		return runInTransaction(runnable, getSessionFactory());
	}
	public static <T> T runInTransaction(HibernateRunnable<T> runnable, SessionFactory sessionFactory) {
		
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
