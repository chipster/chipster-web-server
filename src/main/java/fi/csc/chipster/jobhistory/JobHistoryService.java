package fi.csc.chipster.jobhistory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;

import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;

public class JobHistory {
	private static HttpServer httpServer;
	private static HibernateUtil hibernate;
	private static JobHistoryResource jobHistoryResource;
	private static Config config;

	public JobHistory() {
		

	}

	public static void main(String[] args) throws URISyntaxException,
			IOException {

		List<Class<?>> hibernateClasses = Arrays.asList(JobHistoryModel.class);
		// Initializing hibernate components
		config=new Config();
		hibernate = new HibernateUtil(config, "job-history");
		System.out.println("Hibernate" + hibernate);
		hibernate.buildSessionFactory(hibernateClasses);
		
		jobHistoryResource = new JobHistoryResource(hibernate, config);

		final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
				.register(jobHistoryResource)
				.register(new HibernateRequestFilter(hibernate))
				.register(new HibernateResponseFilter(hibernate))
		// .register(RestUtils.getLoggingFeature("session-db"))
		;

		

		httpServer = GrizzlyHttpServerFactory.createHttpServer(new URI(
				"http://127.0.0.1:8200"), rc);

		httpServer.start();
	}

}
