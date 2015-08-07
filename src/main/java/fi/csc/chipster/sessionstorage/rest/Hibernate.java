package fi.csc.chipster.sessionstorage.rest;

import java.util.List;

import org.hibernate.SessionFactory;
import org.hibernate.cfg.Environment;
import org.hibernate.dialect.H2Dialect;

public class Hibernate {

    private static SessionFactory sessionFactory;

    public static void buildSessionFactory(List<Class<?>> hibernateClasses) {
    	try {

    		final org.hibernate.cfg.Configuration hibernateConf = new org.hibernate.cfg.Configuration();

    		String dbDriver;
    		String dbUrl;
    		String dbUsername;
    		String dbPassword;

    		// Not a real server

    		dbDriver = "org.h2.Driver";
    		dbUrl = "jdbc:h2:database/chipster-session-storage";
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

//    		final org.hibernate.service.ServiceRegistryBuilder serviceRegistryBuilder = new org.hibernate.service.ServiceRegistryBuilder();
//
//    		final org.hibernate.service.ServiceRegistry serviceRegistry = serviceRegistryBuilder
//    				.applySettings(hibernateConf.getProperties())
//    				.buildServiceRegistry();
//
//    		sessionFactory = hibernateConf.buildSessionFactory(serviceRegistry);
    		
    		sessionFactory = hibernateConf.buildSessionFactory();
 
    	} catch (Throwable ex) {
    		// Make sure you log the exception, as it might be swallowed
    		System.err.println("Initial SessionFactory creation failed." + ex);
    		throw new ExceptionInInitializerError(ex);
    	}
    }

    public static SessionFactory getSessionFactory() {
        return sessionFactory;
    }

	public static org.hibernate.Session beginTransaction() {
		org.hibernate.Session session = getSessionFactory().getCurrentSession();
		session.beginTransaction();
		return session;
	}

	public static void commit() {
		getSessionFactory().getCurrentSession().getTransaction().commit();
	}
	
	public static void rollback() {
		getSessionFactory().getCurrentSession().getTransaction().rollback();
	}

	public static org.hibernate.Session session() {
		return getSessionFactory().getCurrentSession();
	}

	public static void rollbackIfActive() {
		if (session().getTransaction().isActive()) {
			session().getTransaction().rollback();
		}		
	}
}