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

	private static final String CONF_ROLE_DB_C3P0_MIN_SIZE = "-db-c3p0-min-size";
	private static final String CONF_ROLE_DB_SHOW_SQL = "-db-show-sql";
	private static final String CONF_ROLE_DB_DIALECT = "-db-dialect";
	private static final String CONF_ROLE_DB_DRIVER = "-db-driver";

	private static final String CONF_ROLE_DB_PASS = "-db-pass";
	private static final String CONF_ROLE_DB_USER = "-db-user";
	private static final String CONF_ROLE_DB_URL = "-db-url";

	private static Logger logger = LogManager.getLogger();
	
    private SessionFactory sessionFactory;

	private Config config;

	private String role;

	private DbBackup dbBackup;
	private DbSchema dbSchema;

    public HibernateUtil(Config config, String role, List<Class<?>> hibernateClasses) {
		this.config = config;
		this.role = role;
	
		this.init(hibernateClasses);
	}
    
    public void init(List<Class<?>> hibernateClasses) {
    	    	
    	this.dbSchema = new DbSchema(config, role);
    	this.dbSchema.export(hibernateClasses);

    	
    	String url = config.getString(role + CONF_ROLE_DB_URL);
    	String user = config.getString(role + CONF_ROLE_DB_USER);
    	String password = config.getString(role + CONF_ROLE_DB_PASS);
    	
    	this.dbBackup = new DbBackup(config, role, url, user, password);
    	this.dbBackup.checkRestore();
    	
    	this.dbSchema.migrate(url, user, password);

    	this.dbBackup.printTableStats(buildSessionFactory(hibernateClasses, url, "", user, password, config, role).openSession());
    	    	
		if (password.length() < 8) {
			logger.warn("weak db passowrd for " + role + ", length " + password.length());
		}
        	
		// make sure the Flyway migrations match with the current Hibernate classes  
		String hbm2ddlAuto = "update";
		
		logger.info("connect to db " + url);
		try {
			this.sessionFactory = buildSessionFactory(hibernateClasses, url, hbm2ddlAuto, user, password, config, role);
			logger.info("connected");
			
    	} catch (SchemaManagementException e) {
    		this.dbSchema.printSchemaError(e);
    		throw e;
    	}
		
		this.dbBackup.scheduleBackups(this.sessionFactory);
    }
    
	public static SessionFactory buildSessionFactory(List<Class<?>> hibernateClasses, String url, String hbm2ddlAuto, String user, String password, Config config, String role) {    	
    	    		
		final org.hibernate.cfg.Configuration hibernateConf = new org.hibernate.cfg.Configuration();
		
		hibernateConf.setProperty(Environment.DRIVER, config.getString(role + CONF_ROLE_DB_DRIVER));
		hibernateConf.setProperty(Environment.URL, url);
		hibernateConf.setProperty(Environment.USER, user);
		hibernateConf.setProperty(Environment.PASS, password);
		hibernateConf.setProperty(Environment.DIALECT, config.getString(role + CONF_ROLE_DB_DIALECT));
		hibernateConf.setProperty(Environment.SHOW_SQL, config.getString(role + CONF_ROLE_DB_SHOW_SQL));
		hibernateConf.setProperty(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");		
		hibernateConf.setProperty("hibernate.c3p0.min_size", config.getString(role + CONF_ROLE_DB_C3P0_MIN_SIZE));
		hibernateConf.setProperty("hibernate.hbm2ddl.auto", hbm2ddlAuto);
		//hibernateConf.setProperty("hibernate.naming.physical-strategy", ChipsterNamingStrategy.class.getName());
		
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
