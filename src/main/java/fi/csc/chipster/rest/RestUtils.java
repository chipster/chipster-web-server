package fi.csc.chipster.rest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnectionStatistics;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.component.Container;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.logging.LoggingFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.exception.NotFoundExceptionMapper;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.pretty.JsonPrettyPrintQueryParamContainerResponseFilter;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.microarray.description.SADLSyntax.ParameterType;
import fi.csc.microarray.messaging.JobState;

public class RestUtils {
	
	private static Logger logger = LogManager.getLogger();
	
	private static Random rand = new Random();
	
	public static ObjectMapper getObjectMapper() {
		return new ObjectMapper()
		.registerModule(new JavaTimeModule())
		.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
	}
	
	public static String asJson(Object obj) {
		return asJson(obj, false);
	}
	
	public static String asJson(Object obj, boolean pretty) {	
		// using Jackson library
		try {
			StringWriter writer = new StringWriter();
			ObjectMapper mapper = getObjectMapper();
			if (pretty) {
				mapper.writerWithDefaultPrettyPrinter().writeValue(writer, obj);
			} else {
				mapper.writeValue(writer, obj);
			}
			return writer.toString();        
		} catch (IOException e) {
			logger.error("json conversion failed", e);
			throw new InternalServerErrorException();
		}
	}
	
	public static <T> T parseJson(Class<T> obj, String json) {	
		return parseJson(obj, json, true);
	}
	
	public static <T> T parseJson(Class<T> obj, String json, boolean failOnUnknownProperties) {	
		// using Jackson library
		try {
			StringReader reader= new StringReader(json);
			ObjectMapper mapper = getObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failOnUnknownProperties);
			return mapper.readValue(reader, obj);
		} catch (IOException e) {
			logger.error("json parsing failed", e);
			throw new InternalServerErrorException();
		} 
	}
	
	@SuppressWarnings("rawtypes")
	public static List parseJson(Class<? extends Collection> collectionType, Class<?> itemType, String json) {
		return parseJson(collectionType, itemType, json, true);
	}
		
	
	@SuppressWarnings("rawtypes")
	public static List parseJson(Class<? extends Collection> collectionType, Class<?> itemType, String json, boolean failOnUnknownProperties) {
		// using Jackson library
		try {
			StringReader reader= new StringReader(json);
			ObjectMapper mapper = getObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failOnUnknownProperties);
			return mapper.readValue(reader, mapper.getTypeFactory().constructCollectionType(collectionType, itemType));
		} catch (IOException e) {
			logger.error("json parsing failed", e);
			throw new InternalServerErrorException();
		}
	}
	
	public static String createId() {
		return createUUID().toString();
	}
	
	public static UUID createUUID() {
		// cryptographically strong pseudo random UUID (type 4)
		return UUID.randomUUID();
	}
	
    public static Session getRandomSession() {
    	
    	Session s = new Session();    	    	
    	//s.setSessionId(createUUID());
    	s.setName("session" + rand.nextInt(1000));
    	s.setCreated(Instant.now());
    	s.setAccessed(Instant.now());
    	
    	return s;
    }
    
    public static Dataset getRandomDataset() {
    	
    	Dataset d = new Dataset();
    	//d.setDatasetId(createUUID());
    	d.setName("dataset" + d.getDatasetId());
    	d.setSourceJob(RestUtils.createUUID());
    	d.setX(100);
    	d.setY(100);
    	
//    	File f = new File();
//    	f.setFileId(createUUID());
//    	f.setChecksum("xyz");
//    	f.setSize(0);
//    	d.setFile(f);
    	
    	return d;
    }

	public static Job getRandomJob() {
		Job j = new Job();
		j.setEndTime(Instant.now());
		//j.setJobId(createUUID());
		j.setState(JobState.NEW);
		j.setStartTime(Instant.now());
		j.setToolCategory("utilities");
		j.setToolDescription("very important tool");
		j.setToolId("UtilTool.py");
		j.setToolName("Utility tool");
		
		ArrayList<Parameter> p = new ArrayList<>();
		p.add(getRandomParameter());
		p.add(getRandomParameter());
		j.setParameters(p);
		
		return j;
	}
	
	public static Job getRandomCompletedJob() {
		Job j = getRandomJob();
		j.setState(JobState.COMPLETED);
		return j;
	}

	public static Input getRandomInput(UUID datasetId) {
		Input i = new Input();
		i.setInputId("inFile");
		i.setDisplayName("Input file");
		i.setDescription("Input file to process");
		i.setType("GENERIC");
		i.setDatasetId(datasetId.toString());
		return i;
	}

	private static Parameter getRandomParameter() {
		Parameter p = new Parameter();
		p.setParameterId(createId());
		p.setDisplayName("Tool parameter");
		p.setDescription("Desckription of the tool parameter");
		p.setType(ParameterType.STRING);
		p.setValue("Parameter value");
		return p;
	}

	public static String basename(String path) {
		return new java.io.File(path).getName();
	}

	public static Service getRandomService() {
		Service s = new Service();
		s.setRole(Role.SESSION_DB);
		s.setServiceId(createId());
		s.setUri("http://localhost:8080/sessionstorage");
		return s;
	}

	public static void waitForShutdown(String name, HttpServer server) {
		System.out.println(name + " started");
        try {
			Thread.currentThread().join();
			
		} catch (InterruptedException e) {
			logger.error(name + " failed", e);
			} finally {
	        GrizzlyFuture<HttpServer> future = server.shutdown();
	        try {
				future.get();
			} catch (InterruptedException | ExecutionException e) {
				logger.warn(name + " shutdown failed", e);
			}
		}
	}

	public static ResourceConfig getDefaultResourceConfig() {
		return new ResourceConfig()
				/*
				 * Disable auto discovery so that we can decide what we want to register
				 * and what not. Don't register JacksonFeature, because it will register
				 * JacksonMappingExceptionMapper, which annoyingly swallows response's
				 * JsonMappingExceptions. Register directly the JacksonJaxbJsonProvider
				 * which is enough for the actual JSON conversion (see the code of
				 * JacksonFeature).
				 */
				.property(CommonProperties.FEATURE_AUTO_DISCOVERY_DISABLE, true)
				.register(JacksonJaxbJsonProvider.class)
				.register(JavaTimeObjectMapperProvider.class)
				 // register all exception mappers
				.packages(NotFoundExceptionMapper.class.getPackage().getName())
				// add CORS headers
				.register(CORSResponseFilter.class)
				// enable the RolesAllowed annotation
				.register(RolesAllowedDynamicFeature.class)
				.register(JsonPrettyPrintQueryParamContainerResponseFilter.class); 
	}

	public static void shutdown(String name, HttpServer httpServer) {
		
		if (httpServer == null) {
			logger.warn("can't shutdown " + name + ", the server is null");
			return;
		}
		GrizzlyFuture<HttpServer> future = httpServer.shutdown();
		try {
			// wait for server to shutdown, otherwise the next test set will print ugly log messages
			try {
				future.get(3, TimeUnit.SECONDS);
			} catch (TimeoutException e) {
				logger.warn(name + " server didn't stop gracefully");
				httpServer.shutdownNow();
			}
		} catch (InterruptedException | ExecutionException e) {
			logger.warn("failed to shutdown the server " + name, e);
		}
	}

	public static boolean isSuccessful(int status) {
		return status >= 200 && status < 300;
	}	
	
	public static String getHostname() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			return "unknown";
		}
	}

	public static void configureForDownload(ResponseBuilder response, String name) {
		response.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
	}

	public static void configureForDownload(HttpServletResponse response, String name) {
		response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");		
	}

	public static LoggingFeature getLoggingFeature(String string) {
		return new LoggingFeature(
				java.util.logging.Logger.getLogger("session-db"), Level.INFO, 
				LoggingFeature.Verbosity.PAYLOAD_TEXT, LoggingFeature.DEFAULT_MAX_ENTITY_SIZE);
	}
	
	public static HttpServer startAdminServer(String role, Config config, AuthenticationClient authService, StatusSource... stats) {
		return startAdminServer(new AdminResource(stats), null, role, config, authService);
	}
	
	public static HttpServer startAdminServer(Object adminResource, HibernateUtil hibernate,
			String role, Config config, AuthenticationClient authService) {
		TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
		// allow unauthenticated health checks
		tokenRequestFilter.authenticationRequired(false, false);
		return startAdminServer(adminResource, hibernate, role, config, tokenRequestFilter);
	}

	public static HttpServer startAdminServer(Object adminResource, HibernateUtil hibernate,
			String role, Config config, Object authResource) {
		
        final ResourceConfig rc = RestUtils.getDefaultResourceConfig()        	
            	.register(authResource)
            	.register(adminResource);
        
        if (hibernate != null) {
            	rc.register(new HibernateRequestFilter(hibernate))
            	.register(new HibernateResponseFilter(hibernate));
        }

    	URI baseUri = URI.create(config.getAdminBindUrl(role));
        return GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
	}
	
	public static HttpServer startUnauthenticatedAdminServer(Object adminResource, 
			String role, Config config) {
		
        final ResourceConfig rc = RestUtils.getDefaultResourceConfig()        	
            	.register(adminResource);

    	URI baseUri = URI.create(config.getAdminBindUrl(role));
        return GrizzlyHttpServerFactory.createHttpServer(baseUri, rc);
	}

	public static JerseyStatisticsSource createJerseyStatisticsSource(ResourceConfig rc) {
		
		JerseyStatisticsSource listener = new JerseyStatisticsSource();
		
		rc.register(listener)
		.property(ServerProperties.MONITORING_STATISTICS_ENABLED, true);
		
		return listener;
	}

	public static StatusSource createStatisticsListener(Server server) {
				
		ServerConnectionStatistics.addToAllConnectors(server);
		
        ConnectionStatistics connectionStats = null;
        
        if (server.getConnectors().length == 1) {
        	Connector connector = server.getConnectors()[0];
        	if (connector instanceof Container) {
        		connectionStats = ((Container)connector).getBean(ConnectionStatistics.class);
        	} else {
        		throw new NotImplementedException("only Containers supported");
        	}
        } else {
        	throw new NotImplementedException("server has multiple connectors");
        }
		
		StatisticsHandler requestStats = new StatisticsHandler();
		requestStats.setHandler(server.getHandler());
        server.setHandler(requestStats);
        
        return new JettyStatisticsSource(connectionStats, requestStats);
	}

	/**
	 * Configure Jetty to handle SIGINT gracefully
	 * 
	 * Eclipse doesn't allow sending SIGINT, so testing is little bit complicated
	 * - Start all necessary services to own processes
	 * - Start a long request, e.g. wget --limit-rate 1M, or add Thread.sleep() to the resource method
	 * - Send SIGINT, i.e. kill -2 $(ps aux | grep -v grep | grep FileBroker | tr -s " " | cut -d " " -f 2)
	 * - Check that new wget fails
	 * - Check that the old wget completes and the service terminates
	 * 
	 * @param server
	 * @param timeout
	 * @param name
	 */
	public static void shutdownGracefullyOnInterrupt(Server server, int timeout, String name) {
		Runtime.getRuntime().addShutdownHook(new Thread() {
    	    public void run() {
    	        logger.info(name + " interrupted");    	        
    	        try {
    	        	logger.info(name + " stops accepting new connections");
    	        	for (Connector con : Arrays.asList(server.getConnectors())) {
    	        		con.shutdown();
    	        	}
					
    	        	final int pollInterval = 1000; //ms
    	        	final int logInterval = 1; // pollIntervals
    	        	final int timeout = 60 * 60; // seconds
    	        	
    	        	int connections = 0;
    	        	Instant waitStart = Instant.now();
    	        	
    	        	shutdownWait:while (true) {
    	        		for (int i = 0; i < logInterval; i++) {
	    	        		connections = Arrays.asList(server.getConnectors()).stream()
	    	        		.mapToInt(con -> con.getConnectedEndPoints().size())
	    	        		.sum();
	    	        	
	    	        		if (connections == 0) {
	    	        			break shutdownWait;
	    	        		} else if (Instant.now().isAfter(waitStart.plusSeconds(timeout))){
	    	        			System.err.println(name + " shutdown wait timeout reached");
	    	        			break shutdownWait;
	    	        		} else {
	    	        			Thread.sleep(pollInterval);
	    	        		}
    	        		}
    	        		// logger won't work anymore
    	        		System.out.println(name + " waiting for remaining " + connections + " connections to complete");
    	        	}
    	        	System.out.println(name + " stopping");
				} catch (Exception e) {
					e.printStackTrace();
				}
    	    }
    	});
	}

	/**
	 * Configure Grizzly to handle SIGINT gracefully
	 * 
	 * Note: Make sure other shutdown hooks won't ruin the processing of the requests. For example
	 * an embedded H2 database will shutdown by default. For some reason the logging stops working too,
	 * so these are difficult to debug. Debugger seems to still work and probably System.out in ExceptionMappers
	 * would too.
	 * 
	 * See the Jetty version above for the testing procedure
	 * 
	 * @param server
	 * @param timeout
	 * @param name
	 */
	public static void shutdownGracefullyOnInterrupt(HttpServer server, int timeout, String name) {
		Runtime.getRuntime().addShutdownHook(new Thread() {
    	    public void run() {
    	        logger.info(name + " interrupted");    	        
    	        GrizzlyFuture<HttpServer> shutdownFuture = server.shutdown(timeout, TimeUnit.SECONDS);
    	        logger.info(name + " stops accepting new connections");
    	        System.out.println(name + " waiting for the remaining connections to complete");
    	        try {
					shutdownFuture.get();
				} catch (InterruptedException | ExecutionException e) {
					e.printStackTrace();
				}
    	        System.out.println(name + " stopping");
    	    }
		});
	}

	public static void shutdownGracefullyOnInterrupt(HttpServer server, String name) {
		shutdownGracefullyOnInterrupt(server, 10, name);
	}

	public static void shutdownGracefullyOnInterrupt(Server server, String name) {
		shutdownGracefullyOnInterrupt(server, 10, name);
	}

	public static String toString(InputStream inputStream) throws IOException {
		return IOUtils.toString(inputStream, StandardCharsets.UTF_8.name());
	}

	public static InputStream toInputStream(String str) throws IOException {
		return IOUtils.toInputStream(str, StandardCharsets.UTF_8.name());
	}

	public static String readFileToString(File file) throws IOException {
		return FileUtils.readFileToString(file, StandardCharsets.UTF_8.name());
	}

	public static void writeStringToFile(File file, String str) throws IOException {
		FileUtils.writeStringToFile(file, str, StandardCharsets.UTF_8.name());
	}
}
