package fi.csc.chipster.rest.hibernate;

import java.io.Serializable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.cfg.Environment;
import org.hibernate.context.internal.ManagedSessionContext;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.postgresql.util.PSQLException;

import fi.csc.chipster.rest.Config;
import jakarta.persistence.EntityManager;

public class HibernateUtil {

	public static class DatabaseNotFoundException extends RuntimeException {
		public DatabaseNotFoundException(PSQLException e) {
			super(e);
		}
	}

	public static class DatabaseConnectionRefused extends RuntimeException {
		public DatabaseConnectionRefused(PSQLException e) {
			super(e);
		}
	}

	private static final String CONF_DB_C3P0_MIN_SIZE = "db-c3p0-min-size";
	private static final String CONF_DB_C3P0_MAX_SIZE = "db-c3p0-max-size";
	private static final String CONF_DB_SHOW_SQL = "db-show-sql";
	private static final String CONF_DB_DIALECT = "db-dialect";
	private static final String CONF_DB_DRIVER = "db-driver";

	public static final String CONF_DB_PASS = "db-pass";
	public static final String CONF_DB_USER = "db-user";
	public static final String CONF_DB_URL = "db-url";
	public static final String CONF_DB_EXPORT_SCHEMA = "db-export-schema";

	private static Logger logger = LogManager.getLogger();

	private SessionFactory sessionFactory;

	private Config config;

	private String role;

	private DbSchema dbSchema;

	public HibernateUtil(Config config, String role, List<Class<?>> hibernateClasses) throws InterruptedException {
		this.config = config;
		this.role = role;

		this.init(hibernateClasses);
	}

	public void init(List<Class<?>> hibernateClasses) throws InterruptedException {

		String url = config.getString(CONF_DB_URL, role);
		String user = config.getString(CONF_DB_USER, role);
		String password = config.getString(CONF_DB_PASS, role);
		String dialect = config.getString(CONF_DB_DIALECT, role);
		String driver = config.getString(CONF_DB_DRIVER, role);

		// make sure the Flyway migrations match with the current Hibernate classes
		String hbm2ddlAuto = "validate";
		Configuration hibernateConf = getHibernateConf(hibernateClasses, url, hbm2ddlAuto, user, password, config,
				role);

		try {

			// test connection first to make errors easier to catch
			testConnection(url, user, password);

			// the db is there
			this.dbSchema = new DbSchema(role);

			if (config.getBoolean(CONF_DB_EXPORT_SCHEMA, role)) {
				this.dbSchema.export(hibernateClasses, dialect, driver);
			}

			this.dbSchema.migrate(url, user, password);

			if (password.length() < 8) {
				logger.warn("weak db password for " + role + ", length " + password.length());
			}

			logger.info("connect to db " + url);
			this.sessionFactory = buildSessionFactory(hibernateConf);
			logger.info("connected");

		} catch (DatabaseConnectionRefused e) {

			throw new RuntimeException(role + " db not available\n"
					+ "Install postgres: \n"
					+ "  brew install postgresql@15\n"
					+ "  brew services start postgresql@15\n"
					+ "  createuser user\n"
					+ "  createdb auth_db\n"
					+ "  createdb session_db_db\n"
					+ "  createdb job_history_db\n" + "\n", e);

		} catch (SchemaManagementException e) {

			this.dbSchema.printSchemaError(e);
			this.dbSchema.export(hibernateClasses, dialect, driver);
			throw e;
		}
	}

	public static void testConnection(String url, String user, String password) {

		try {
			logger.info("test connection to " + url);
			Properties connectionProps = new Properties();
			connectionProps.put("user", user);
			connectionProps.put("password", password);

			Connection connection = DriverManager.getConnection(url, connectionProps);
			logger.info("test connection to " + url + ": OK");
			connection.close();

		} catch (PSQLException e) {
			if (e.getMessage().contains("database") && e.getMessage().contains("does not exist")) {

				throw new DatabaseNotFoundException(e);

			} else if (e.getMessage().toLowerCase().contains("connection") && e.getMessage().contains("refused")) {
				throw new DatabaseConnectionRefused(e);
			}
			throw new RuntimeException("failed to connect to " + url, e);
		} catch (SQLException e) {
			throw new RuntimeException("failed to connect to " + url, e);
		}
	}

	public static Configuration getHibernateConf(List<Class<?>> hibernateClasses, String url, String hbm2ddlAuto,
			String user, String password, Config config, String role) {

		final org.hibernate.cfg.Configuration hibernateConf = new org.hibernate.cfg.Configuration();

		hibernateConf.setProperty(Environment.DRIVER, config.getString(CONF_DB_DRIVER, role));
		hibernateConf.setProperty(Environment.URL, url);
		hibernateConf.setProperty(Environment.USER, user);
		hibernateConf.setProperty(Environment.PASS, password);
		hibernateConf.setProperty(Environment.DIALECT, config.getString(CONF_DB_DIALECT, role));
		hibernateConf.setProperty(Environment.SHOW_SQL, config.getString(CONF_DB_SHOW_SQL, role));
		hibernateConf.setProperty(Environment.CURRENT_SESSION_CONTEXT_CLASS, "managed");
		hibernateConf.setProperty(Environment.JDBC_TIME_ZONE, "UTC");
		hibernateConf.setProperty("hibernate.c3p0.min_size", config.getString(CONF_DB_C3P0_MIN_SIZE, role));
		hibernateConf.setProperty("hibernate.c3p0.max_size", config.getString(CONF_DB_C3P0_MAX_SIZE, role));
		hibernateConf.setProperty("hibernate.c3p0.acquireRetryAttempts", "1"); // throw on connection errors immediately
																				// in startup
		hibernateConf.setProperty("hibernate.hbm2ddl.auto", hbm2ddlAuto);
		// following two for debugging connection leaks
		// hibernateConf.setProperty("hibernate.c3p0.debugUnreturnedConnectionStackTraces",
		// "true");
		// hibernateConf.setProperty("hibernate.c3p0.unreturnedConnectionTimeout",
		// "30");

		for (Class<?> c : hibernateClasses) {
			hibernateConf.addAnnotatedClass(c);
		}

		return hibernateConf;
	}

	public static SessionFactory buildSessionFactory(Configuration hibernateConf) {
		StandardServiceRegistry registry = new StandardServiceRegistryBuilder()
				.applySettings(hibernateConf.getProperties()).build();

		return hibernateConf.buildSessionFactory(registry);
	}

	public SessionFactory getSessionFactory() {
		return sessionFactory;
	}

	/**
	 * Use only in HibernateRequestFilter or through runInTransaction() to avoid
	 * connection leaks
	 * 
	 * @return
	 */
	org.hibernate.Session beginTransactionAndBind() {
		Session session = beginTransaction(getSessionFactory());
		ManagedSessionContext.bind(session);
		return session;
	}

	private static org.hibernate.Session beginTransaction(SessionFactory sessionFactory2) {

		Session session = sessionFactory2.withOptions()
				.openSession();

		// update db only explicitly
		session.setDefaultReadOnly(true);

		// org.hibernate.Session session = getSessionFactory().getCurrentSession();
		session.beginTransaction();

		// if (isPostgres(config, role)) {
		// session.createNativeQuery("SET LOCAL synchronous_commit TO
		// OFF").executeUpdate();
		// }

		return session;
	}

	/**
	 * Use only in HibernateResponseFilter or through runInTransaction()
	 */
	void commitAndUnbind() {
		commitAndUnbind(getSessionFactory());
	}

	private static void commitAndUnbind(SessionFactory sessionFactory) {
		Session session = ManagedSessionContext.unbind(sessionFactory);
		commit(session);
	}

	private static void commit(Session session) {
		session.getTransaction().commit();
		session.close();
	}

	/**
	 * Use only in HibernateResponseFilter
	 */
	void rollbackAndUnbind() {
		Session session = ManagedSessionContext.unbind(sessionFactory);
		rollback(session);
	}

	/**
	 * Use only through runInTransaction()
	 * 
	 * @param sessionFactory
	 */
	private static void rollback(Session session) {
		if (session != null) {
			session.getTransaction().rollback();
			session.close();
		} else {
			// why the sessions is null e.g. when an exception happens when the result is
			// mapped to json
			logger.warn("cannot rollback Hibernate session, because it's null");
		}
	}

	public org.hibernate.Session session() {
		return getSessionFactory().getCurrentSession();
	}

	public <T> T runInTransaction(HibernateRunnable<T> runnable) {
		return runInTransaction(runnable, getSessionFactory());
	}

	public static <T> T runInTransaction(HibernateRunnable<T> runnable, SessionFactory sessionFactory) {
		return runInTransaction(runnable, sessionFactory, true);
	}

	public static <T> T runInTransaction(HibernateRunnable<T> runnable, SessionFactory sessionFactory, boolean bind) {

		T returnObj = null;

		Session session = beginTransaction(sessionFactory);
		if (bind) {
			ManagedSessionContext.bind(session);
		}
		try {
			returnObj = runnable.run(session);
			commit(session);
			if (bind) {
				ManagedSessionContext.unbind(sessionFactory);
			}
		} catch (Exception e) {
			rollback(session);
			if (bind) {
				ManagedSessionContext.unbind(sessionFactory);
			}
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
		// Hibernate won't notice the changes in managed objects (those that have been
		// loaded from the db in this session)
		this.session().detach(value);
		HibernateUtil.update(value, id, session());
	}

	/**
	 * Update an db object in a read-only Hibernate session
	 * 
	 * @param class1
	 * @param value
	 * @param id
	 */
	public static <T> void update(T value, Serializable id, Session session) {

		@SuppressWarnings("unchecked")
		T dbObject = (T) session.getReference(value.getClass(), id);
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
	public static <T> void delete(T value, Serializable id, Session session) {

		@SuppressWarnings("unchecked")
		T dbObject = (T) session.getReference(value.getClass(), id);
		session.setReadOnly(dbObject, false);
		session.remove(dbObject);
		session.flush();
	}

	/**
	 * Persist an db object in a read-only Hibernate session
	 * 
	 * @param class1
	 * @param value
	 * @param id
	 */
	public static void persist(Object value, Session session) {
		session.persist(value);
		session.setReadOnly(value, true);
	}
}
