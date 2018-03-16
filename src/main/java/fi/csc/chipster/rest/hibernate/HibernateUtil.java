package fi.csc.chipster.rest.hibernate;

import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;

import fi.csc.chipster.rest.Config;

public class HibernateUtil {

	//private static Logger logger = Logger.getLogger(HibernateUtil.class.getName());
	private static Logger logger = LogManager.getLogger();
	
    private SessionFactory sessionFactory;

	private Config config;

	private String role;

    public HibernateUtil(Config config, String role) {
		this.config = config;
		this.role = role;
	}

	public void buildSessionFactory(List<Class<?>> hibernateClasses) {    	
    	
    	try {    		    	
    		
    		final org.hibernate.cfg.Configuration hibernateConf = new org.hibernate.cfg.Configuration();
    		
    		String url = config.getString(role + "-db-url");
    		String password = config.getString(role + "-db-pass");
    		
    		if (password.length() < 8) {
    			logger.warn("weak db passowrd for " + role + ", length " + password.length());
    		}
    		
    		hibernateConf.setProperty(Environment.DRIVER, config.getString(role + "-db-driver"));
    		hibernateConf.setProperty(Environment.URL, url);
    		hibernateConf.setProperty(Environment.USER, config.getString(role + "-db-user"));
    		hibernateConf.setProperty(Environment.PASS, password);
    		hibernateConf.setProperty(Environment.DIALECT, config.getString(role + "-db-dialect"));
    		hibernateConf.setProperty(Environment.SHOW_SQL, config.getString(role + "-db-show-sql"));
    		hibernateConf.setProperty(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
    		hibernateConf.setProperty("hibernate.c3p0.min_size", config.getString(role + "-db-c3p0-min-size"));
    		hibernateConf.setProperty("hibernate.hbm2ddl.auto", config.getString(role + "-db-bm2ddl-auto"));
    		
    		for (Class<?> c : hibernateClasses) {
    			hibernateConf.addAnnotatedClass(c);
    		}    		    	   
    		 
    		logger.info("connect to db " + url);

    		sessionFactory = hibernateConf.buildSessionFactory(
    				new StandardServiceRegistryBuilder()
    				.applySettings(hibernateConf.getProperties())
    				.build());
    		
    		logger.info("connected");
 
    	} catch (Throwable ex) {
    		logger.error("sessionFactory creation failed.", ex);
    		throw new ExceptionInInitializerError(ex);
    	}
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
		
		T returnObj = null;
		Session session = getSessionFactory().openSession();
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