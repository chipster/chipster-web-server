package fi.csc.chipster.rest.hibernate;

import java.net.ConnectException;
import java.util.List;
import java.util.UUID;

import javax.persistence.EntityManager;

import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.context.internal.ManagedSessionContext;
import org.hibernate.exception.GenericJDBCException;
import org.hibernate.tool.schema.spi.SchemaManagementException;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.MetadataEntry;
import fi.csc.chipster.sessiondb.model.Parameter;

public class HibernateUtil {

	private static final int DB_WARNING_DELAY = 5; // seconds
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
//    	String restoreKey = DbBackup.getRestoryKey(config, role);
//    	if (!restoreKey.isEmpty()) {
//    		throw new RuntimeException("Configuration " + restoreKey + " is set. Refusing to start while the DB is being restored.");
//    	}
//    	    	
//    	this.dbSchema = new DbSchema(config, role);
//    	this.dbSchema.export(hibernateClasses);
    	
    	String url = config. getString(CONF_DB_URL, role);
    	String user = config.getString(CONF_DB_USER, role);
    	String password = config.getString(CONF_DB_PASS, role);
    	
//    	this.dbSchema.migrate(url, user, password);    	
//
//		if (password.length() < 8) {
//			logger.warn("weak db passowrd for " + role + ", length " + password.length());
//		}
//        	
//		// make sure the Flyway migrations match with the current Hibernate classes  
//		String hbm2ddlAuto = "validate";
		String hbm2ddlAuto = "update";
//		String hbm2ddlAuto = "create";
		
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
//		hibernateConf.setProperty(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
		hibernateConf.setProperty(Environment.CURRENT_SESSION_CONTEXT_CLASS, "managed");
		hibernateConf.setProperty("hibernate.c3p0.min_size", config.getString(CONF_DB_C3P0_MIN_SIZE, role));
		hibernateConf.setProperty("hibernate.c3p0.acquireRetryAttempts", "0"); // throw on connection errors immediately in startup
		hibernateConf.setProperty("hibernate.hbm2ddl.auto", hbm2ddlAuto);
		
		for (Class<?> c : hibernateClasses) {
			hibernateConf.addAnnotatedClass(c);
		}
		
		boolean isPostgres = isPostgres(config, role);
		
		if (isPostgres) {
			hibernateConf.setProperty(Environment.DIALECT, "fi.csc.chipster.rest.hibernate.ChipsterPostgreSQL95Dialect");
		}
		
		registerTypeOverrides(hibernateConf, isPostgres);
		
		/* Allow hibernate to make inserts and updates in batches to overcome the network latency.
		 * It's crucial when e.g. a dataset may have 600 MetadataEntries. However, this doesn't
		 * help if there are other queries/updates for each row, like for getting the next sequence id 
		 * for the object or updating the object references.
		 */
//		hibernateConf.setProperty("hibernate.jdbc.batch_size", "1000");
//		hibernateConf.setProperty("hibernate.order_inserts", "true");
//		hibernateConf.setProperty("hibernate.order_updates", "true");
//		hibernateConf.setProperty("hibernate.jdbc.batch_versioned_data", "true");
		
//		hibernateConf.setProperty("hibernate.default_batch_fetch_size", "100");
				
		SessionFactory sessionFactory = null;
		
		try {
			sessionFactory = buildSessionFactory(hibernateConf);
			
		} catch (GenericJDBCException e) {
    		if (ExceptionUtils.getRootCause(e) instanceof ConnectException && config.getBoolean(Config.KEY_DB_FALLBACK, role)) {
    			// The Postgres template in OpenShift doesn't allow dashes
    			String dbName = role.replace("-", "_") + "_db";
    			
    	    	logger.warn(role + "-db not available, starting an in-memory DB "
    	    			+ "after " + DB_WARNING_DELAY + " seconds. "
    					+ "All data is lost in service restart. "
    					+ "Disable this fallback in production! (" + e.getMessage() + ")\n"
    					+ "Install postgres: \n"
    					+ "  brew install postgres\n"
    					+ "  createuser user\n"
    					+ "  createdb " + dbName + "\n");    	    
    			sessionFactory = buildSessionFactoryFallback(hibernateConf, role);	    			
    		} else {
    			throw e;
    		}
		}
		
		return sessionFactory;
	}
	
    private static SessionFactory buildSessionFactoryFallback(Configuration hibernateConf, String role) {

		try {
			// wait little bit to make the log message above more visible
			Thread.sleep(DB_WARNING_DELAY * 1000);
			String url = "jdbc:h2:mem:" + role + "-db";
			
			hibernateConf.setProperty(Environment.DRIVER, "org.h2.Driver");
			hibernateConf.setProperty(Environment.URL, url);
			hibernateConf.setProperty(Environment.USER, "user");
			hibernateConf.setProperty(Environment.PASS, "");
			hibernateConf.setProperty(Environment.DIALECT, ChipsterH2Dialect.class.getName());
			hibernateConf.setProperty("hibernate.hbm2ddl.auto", "create");
			
			registerTypeOverrides(hibernateConf, false);
			
			logger.info("connect to db " + url);
			SessionFactory sessionFactory = buildSessionFactory(hibernateConf);
			logger.info("connected");
			return sessionFactory;
			
		} catch (InterruptedException e) {
			logger.error(e);
		}
		return null;
	}
    
	private static void registerTypeOverrides(Configuration hibernateConf, boolean isPostgres) {

		// store these child objects as json
        hibernateConf.registerTypeOverride(new ListJsonType<MetadataEntry>(!isPostgres, MetadataEntry.class), new String[] {MetadataEntry.METADATA_ENTRY_LIST_JSON_TYPE});
        hibernateConf.registerTypeOverride(new ListJsonType<Parameter>(!isPostgres, Parameter.class), new String[] {Parameter.PARAMETER_LIST_JSON_TYPE});
        hibernateConf.registerTypeOverride(new ListJsonType<Input>(!isPostgres, Input.class), new String[] {Input.INPUT_LIST_JSON_TYPE});	
	}

	private static SessionFactory buildSessionFactory(Configuration hibernateConf) {
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
				.applySettings(hibernateConf.getProperties())
				.build();

		return hibernateConf.buildSessionFactory(registry);
	}

	public static boolean isPostgres(Config config, String role) {
		return config.getString(CONF_DB_DIALECT, role).toLowerCase().contains("postgres");
	}

	public SessionFactory getSessionFactory() {
        return sessionFactory;
    }
	
	public org.hibernate.Session beginTransaction() {
		return beginTransaction(getSessionFactory());
	}

	public static org.hibernate.Session beginTransaction(SessionFactory sessionFactory2) {			
		
		Session session = sessionFactory2
				.withOptions()
//				.interceptor(new LoggingInterceptor())
				.openSession();
		
		ManagedSessionContext.bind(session);
		
		// update db only explicitly
		session.setDefaultReadOnly(true);		
		
//		org.hibernate.Session session = getSessionFactory().getCurrentSession();
		session.beginTransaction();
		
//		if (isPostgres(config, role)) {
//			session.createNativeQuery("SET LOCAL synchronous_commit TO OFF").executeUpdate();
//		}
		
		
		return session;
	}
	
	public void commit() {
		commit(getSessionFactory());
	}

	public static void commit(SessionFactory sessionFactory) {
		sessionFactory.getCurrentSession().getTransaction().commit();
		Session session = ManagedSessionContext.unbind(sessionFactory);
		session.close();
	}
	
	public void rollback() {
		rollback(getSessionFactory());		
	}
	
	public static void rollback(SessionFactory sessionFactory) {
		sessionFactory.getCurrentSession().getTransaction().rollback();
		Session session = ManagedSessionContext.unbind(sessionFactory);
		session.close();
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
		
		Session session = beginTransaction(sessionFactory);
		try {
			returnObj = runnable.run(session);
			commit(sessionFactory);
		} catch (Exception e) {
			rollback(sessionFactory);
			logger.error("transaction failed", e);
		}
		return returnObj;
	}
	
	public interface HibernateRunnable<T> {
		public T run(Session hibernateSession);
	}

	public static EntityManager getEntityManager(org.hibernate.Session hibernateSession) {
		return hibernateSession.unwrap(EntityManager.class);
	}
	
	public EntityManager getEntityManager() {
		return this.session().unwrap(EntityManager.class);
	}

	/**
	 * Update an db object in a read-only Hibernate session
	 * 
	 * @param class1
	 * @param value
	 * @param id
	 * @param session 
	 */
	public <T> void update(T value, UUID id) {
		
		HibernateUtil.update(value, id, session());
	}
	
	/**
	 * Update an db object in a read-only Hibernate session
	 * 
	 * @param class1
	 * @param value
	 * @param id
	 */
	public static <T> void update(T value, UUID id, Session session) {
		
		@SuppressWarnings("unchecked")
		T dbObject = (T) session.load(value.getClass(), id);
		session.setReadOnly(dbObject, false);
		session.merge(value);
		session.flush();
		session.setReadOnly(dbObject, true);
	}
	
	/**
	 * Delete an db object in a read-only Hibernate session
	 * 
	 * @param class1
	 * @param value
	 * @param id
	 */
	public static <T> void delete(T value, UUID id, Session session) {
		
		@SuppressWarnings("unchecked")
		T dbObject = (T) session.load(value.getClass(), id);
		session.setReadOnly(dbObject, false);
		session.delete(dbObject);
		session.flush();
	}
}
