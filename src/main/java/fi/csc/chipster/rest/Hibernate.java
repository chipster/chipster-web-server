package fi.csc.chipster.rest;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.H2Dialect;

public class Hibernate {

	private static Logger logger = Logger.getLogger(Hibernate.class.getName());
	
    private SessionFactory sessionFactory;

    public void buildSessionFactory(List<Class<?>> hibernateClasses, String dbName) {
    	
    	
    	try {    		
    		
        	@SuppressWarnings("unused")
			org.jboss.logging.Logger logger = org.jboss.logging.Logger.getLogger("org.hibernate");
            java.util.logging.Logger.getLogger("org.hibernate").setLevel(java.util.logging.Level.WARNING);
    		
    		final org.hibernate.cfg.Configuration hibernateConf = new org.hibernate.cfg.Configuration();

    		String dbDriver;
    		String dbUrl;
    		String dbUsername;
    		String dbPassword;

    		// Not a real server

    		dbDriver = "org.h2.Driver";
    		dbUrl = "jdbc:h2:database/" + dbName;
    		dbUsername = "sa";
    		dbPassword = "";

    		hibernateConf.setProperty(Environment.DRIVER, dbDriver);
    		hibernateConf.setProperty(Environment.URL, dbUrl);
    		hibernateConf.setProperty(Environment.USER, dbUsername);
    		hibernateConf.setProperty(Environment.PASS, dbPassword);
    		hibernateConf.setProperty(Environment.DIALECT, H2Dialect.class.getName());
    		hibernateConf.setProperty(Environment.SHOW_SQL, "true");
    		hibernateConf.setProperty(Environment.CURRENT_SESSION_CONTEXT_CLASS, "thread");
    		// check schema
//    		hibernateConf.setProperty("hibernate.hbm2ddl.auto", "validate");
    		// simple schema updates (but hibernate docs don't recommend for production use)
//    		hibernateConf.setProperty("hibernate.hbm2ddl.auto", "update");    		
    		// drop old table and create new one
    		hibernateConf.setProperty("hibernate.hbm2ddl.auto", "create");
    		
    		for (Class<?> c : hibernateClasses) {
    			hibernateConf.addAnnotatedClass(c);
    		}    		    	   
    		
    		sessionFactory = hibernateConf.buildSessionFactory(
    				new StandardServiceRegistryBuilder()
    				.applySettings(hibernateConf.getProperties())
    				.build());
 
    	} catch (Throwable ex) {
    		logger.log(Level.SEVERE, "sessionFactory creation failed.", ex);
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
}