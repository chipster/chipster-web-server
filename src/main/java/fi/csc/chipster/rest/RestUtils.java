package fi.csc.chipster.rest;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.grizzly.http.server.NetworkListener;
import org.glassfish.grizzly.http.server.accesslog.AccessLogBuilder;
import org.glassfish.grizzly.http.server.accesslog.ApacheLogFormat;
import org.glassfish.grizzly.nio.transport.TCPNIOTransport;
import org.glassfish.grizzly.threadpool.ThreadPoolConfig;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.jackson.JacksonFeature;
import org.glassfish.jersey.logging.LoggingFeature;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.ServerProperties;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.comp.JobState;
import fi.csc.chipster.rest.exception.NotFoundExceptionMapper;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.pretty.JsonPrettyPrintQueryParamContainerResponseFilter;
import fi.csc.chipster.rest.token.TokenRequestFilter;
import fi.csc.chipster.rest.websocket.PubSubConfigurator;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.servicelocator.resource.Service;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.toolbox.sadl.SADLSyntax.ParameterType;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response.ResponseBuilder;

public class RestUtils {

	private static final String CONF_SERVER_THREADS_WORKER_MAX = "server-threads-worker-max";
	private static final String CONF_SERVER_THREADS_WORKER_MIN = "server-threads-worker-min";
	private static final String CONF_SERVER_THREADS_SELECTOR = "server-threads-selector";

	public static final String PATH_ARRAY = "array";

	private static Logger logger = LogManager.getLogger();

	private static Random rand = new Random();

	private static ObjectMapper objectMapperDefault;
	private static ObjectMapper objectMapperFailOnUnknwonProperties;

	public static ObjectMapper getObjectMapper(boolean failOnUnknownProperties) {
		if (objectMapperDefault == null || objectMapperFailOnUnknwonProperties == null) {

			// separate instance, because configuration may not be thread safe
			objectMapperDefault = getNewObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
					false);
			objectMapperFailOnUnknwonProperties = getNewObjectMapper()
					.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
		}
		if (failOnUnknownProperties) {
			return objectMapperFailOnUnknwonProperties;
		}
		return objectMapperDefault;
	}

	public static ObjectMapper getNewObjectMapper() {
		return new ObjectMapper().registerModule(new JavaTimeModule())
				.configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false);
	}

	public static String asJson(Object obj) {
		return asJson(obj, false);
	}

	public static String asJson(Object obj, boolean pretty) {
		// using Jackson library
		try {
			StringWriter writer = new StringWriter();
			ObjectMapper mapper = getObjectMapper(false);
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
			StringReader reader = new StringReader(json);
			ObjectMapper mapper = getObjectMapper(failOnUnknownProperties);
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
	public static List parseJson(Class<? extends Collection> collectionType, Class<?> itemType, String json,
			boolean failOnUnknownProperties) {
		// using Jackson library
		try {
			StringReader reader = new StringReader(json);
			ObjectMapper mapper = getObjectMapper(failOnUnknownProperties);
			return mapper.readValue(reader, mapper.getTypeFactory().constructCollectionType(collectionType, itemType));
		} catch (IOException e) {
			logger.error("json parsing failed", e);
			throw new InternalServerErrorException();
		}
	}
	
	public static HashMap<String, Object> parseJsonToMap(String json) throws JsonMappingException, JsonProcessingException {
		ObjectReader reader = new ObjectMapper().readerFor(Map.class);

		return reader.readValue(json);
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
		// s.setSessionId(createUUID());
		s.setName("session" + rand.nextInt(1000));
		s.setCreated(Instant.now());
		s.setAccessed(Instant.now());

		return s;
	}

	public static Dataset getRandomDataset() {

		Dataset d = new Dataset();
		// d.setDatasetId(createUUID());
		d.setName("dataset" + RestUtils.createUUID());
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
		// j.setJobId(createUUID());
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

	public static Job getRandomRunningJob() {
		Job j = getRandomJob();
		j.setState(JobState.RUNNING);
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

	public static void waitForShutdown(String name, Server server) {
		System.out.println(name + " started");
		try {
			server.join();

		} catch (InterruptedException e) {
			logger.error(name + " failed", e);
		} finally {
			server.destroy();
		}
	}

	public static ResourceConfig getDefaultResourceConfig(ServiceLocatorClient serviceLocator) {

		ResourceConfig rc = new ResourceConfig()
				/*
				 * Disable auto discovery so that we can decide what we want to register and
				 * what not.
				 * 
				 * Register JacksonFeature without exception mappers, because by default it
				 * annoyingly swallows response's JsonMappingExceptions. Our more verbose (in
				 * loggging) GeneralExceptoinMapper will take care of hiding details from the
				 * client.
				 */
				.property(CommonProperties.FEATURE_AUTO_DISCOVERY_DISABLE, true)
				/*
				 * Disable WADL
				 * 
				 * We don't use it at the moment.
				 */
				.property(ServerProperties.WADL_FEATURE_DISABLE, true)
				.register(JacksonFeature.withoutExceptionMappers()).register(JavaTimeObjectMapperProvider.class)
				// register all exception mappers
				.packages(NotFoundExceptionMapper.class.getPackage().getName())
				// enable the RolesAllowed annotation
				.register(RolesAllowedDynamicFeature.class)
				.register(JsonPrettyPrintQueryParamContainerResponseFilter.class);

		if (serviceLocator != null) {
			CORSResponseFilter cors = new CORSResponseFilter(serviceLocator);
			// add CORS headers
			rc.register(cors);
		} else {
			logger.info("CORS headers disabled because ServiceLocatorClient is null");
		}

		return rc;
	}

	public static void shutdown(String name, HttpServer httpServer) {

		if (httpServer == null) {
			logger.warn("can't shutdown " + name + ", the server is null");
			return;
		}
		GrizzlyFuture<HttpServer> future = httpServer.shutdown();
		try {
			// wait for server to shutdown, otherwise the next test set will print ugly log
			// messages
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

	public static void configureFilename(ResponseBuilder response, String name) {
		response.header("Content-Disposition", "inline; filename=\"" + name + "\"");
	}

	public static void configureForDownload(ResponseBuilder response, String name) {
		response.header("Content-Disposition", "attachment; filename=\"" + name + "\"");
	}

	public static void configureForDownload(HttpServletResponse response, String name) {
		response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
	}

	public static LoggingFeature getLoggingFeature(String string) {
		return new LoggingFeature(java.util.logging.Logger.getLogger("session-db"), Level.INFO,
				LoggingFeature.Verbosity.PAYLOAD_TEXT, LoggingFeature.DEFAULT_MAX_ENTITY_SIZE);
	}

	public static HttpServer startAdminServer(String role, Config config, AuthenticationClient authService,
			ServiceLocatorClient serviceLocator, StatusSource... stats) throws IOException {
		return startAdminServer(new AdminResource(stats), null, role, config, authService, serviceLocator);
	}

	public static HttpServer startAdminServer(Object adminResource, HibernateUtil hibernate, String role, Config config,
			AuthenticationClient authService, ServiceLocatorClient serviceLocatorClient) throws IOException {
		TokenRequestFilter tokenRequestFilter = new TokenRequestFilter(authService);
		// allow unauthenticated health checks
		tokenRequestFilter.addAllowedRole(Role.UNAUTHENTICATED);
		return startAdminServer(adminResource, hibernate, role, config, tokenRequestFilter, serviceLocatorClient);
	}

	public static HttpServer startAdminServer(Object adminResource, HibernateUtil hibernate, String role, Config config,
			Object authResource, ServiceLocatorClient serviceLocator) throws IOException {

		final ResourceConfig rc = RestUtils.getDefaultResourceConfig(serviceLocator).register(authResource)
				.register(adminResource);

		if (hibernate != null) {
			rc.register(new HibernateRequestFilter(hibernate)).register(new HibernateResponseFilter(hibernate));
		}

		URI baseUri = URI.create(config.getAdminBindUrl(role));
		HttpServer server = GrizzlyHttpServerFactory.createHttpServer(baseUri, rc, false);

		configureGrizzlyThreads(server, role + "-admin", true, config);
		RestUtils.configureGrizzlyRequestLog(server, role, LogType.ADMIN);

		server.start();

		return server;
	}

	public static JerseyStatisticsSource createJerseyStatisticsSource(ResourceConfig rc) {

		JerseyStatisticsSource listener = new JerseyStatisticsSource();

		rc.register(listener).property(ServerProperties.MONITORING_STATISTICS_ENABLED, true);

		return listener;
	}

	public static StatusSource createStatisticsListener(Server server) {

		ConnectionStatistics connectionStats = new ConnectionStatistics();
		server.addBeanToAllConnectors(connectionStats);

		StatisticsHandler requestStats = new StatisticsHandler();
		requestStats.setHandler(server.getHandler());
		server.setHandler(requestStats);

		return new JettyStatisticsSource(connectionStats, requestStats);
	}

	/**
	 * Configure Jetty to handle SIGINT gracefully
	 * 
	 * Eclipse doesn't allow sending SIGINT, so testing is little bit complicated -
	 * Start all necessary services to own processes - Start a long request, e.g.
	 * wget --limit-rate 1M, or add Thread.sleep() to the resource method - Send
	 * SIGINT, i.e. kill -2 $(ps aux | grep -v grep | grep FileBroker | tr -s " " |
	 * cut -d " " -f 2) - Check that new wget fails - Check that the old wget
	 * completes and the service terminates
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

					final int pollInterval = 1000; // ms
					final int logInterval = 1; // pollIntervals
					final int timeout = 60 * 60; // seconds

					int connections = 0;
					Instant waitStart = Instant.now();

					shutdownWait: while (true) {
						for (int i = 0; i < logInterval; i++) {
							connections = Arrays.asList(server.getConnectors()).stream()
									.mapToInt(con -> con.getConnectedEndPoints().size()).sum();

							if (connections == 0) {
								break shutdownWait;
							} else if (Instant.now().isAfter(waitStart.plusSeconds(timeout))) {
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
	 * Note: Make sure other shutdown hooks won't ruin the processing of the
	 * requests. For
	 * some reason the logging stops working too, so these are difficult to debug.
	 * Debugger seems to still work and probably System.out in ExceptionMappers
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

	public static Long getLengthInBytes(String str) {
		if (str != null) {
			try {
				return (long) str.getBytes(StandardCharsets.UTF_8.name()).length;
			} catch (UnsupportedEncodingException e) {
				throw new RuntimeException(e);
			}
		}
		return null;
	}

	public static String readFileToString(File file) throws IOException {
		return FileUtils.readFileToString(file, StandardCharsets.UTF_8.name());
	}

	public static void writeStringToFile(File file, String str) throws IOException {
		FileUtils.writeStringToFile(file, str, StandardCharsets.UTF_8.name());
	}

	public static ObjectNode getArrayResponse(String arrayKey, String itemKey, List<UUID> ids) {
		JsonNodeFactory factory = new JsonNodeFactory(false);
		ObjectNode json = factory.objectNode();
		ArrayNode datasetsArray = factory.arrayNode();
		json.set(arrayKey, datasetsArray);
		for (UUID id : ids) {
			ObjectNode obj = datasetsArray.addObject();
			obj.put(itemKey, id.toString());
		}
		return json;
	}

	public static void configureJettyThreads(Server server, String name) {
		configureJettyThreads(server, name, null);
	}
	
	/**
	 * Configure Jetty threads
	 * 
	 * Threads are given a name to make debugging easier.
	 * 
	 * @param server
	 * @param name
	 * @param isAdminAPI
	 */
	public static void configureJettyThreads(Server server, String name, Config config) {

		// no need to configure thread counts, because there are no Jetty admin APIs so
		// far

		if (server.isStarted()) {
			// didn't test if it's possible to change the thread pool of running Jetty
			throw new IllegalStateException("jetty " + name + " shouldn't be running when configuring threads");
		}

		// give name for the thread pool to make debugging easier
		ThreadPool pool = server.getThreadPool();
		if (pool instanceof QueuedThreadPool) {
			QueuedThreadPool qtp = ((QueuedThreadPool) pool);
			qtp.setName("jetty-qtp-" + name);
			
			if (config != null) {
			
				String configWorkerThreadsMin = config.getString(CONF_SERVER_THREADS_WORKER_MIN, name);
				String configWorkerThreadsMax = config.getString(CONF_SERVER_THREADS_WORKER_MAX, name);
				
				if (!configWorkerThreadsMin.isEmpty()) {
					qtp.setMinThreads(Integer.parseInt(configWorkerThreadsMin));
				}
				
				if (!configWorkerThreadsMax.isEmpty()) {
					qtp.setMaxThreads(Integer.parseInt(configWorkerThreadsMax));
				}
			}
			
			logger.info(name + " configured to use " + qtp.getMinThreads() + "-" + qtp.getMaxThreads() + " threads");
		}
	}


	/**
	 * Configure Grizzly threads
	 * 
	 * Threads are given a name to make debugging easier. Admin APIs get a lower
	 * number of threads, mostly for the same reason.
	 * 
	 * @param server
	 * @param name
	 * @param isAdminAPI
	 */
	public static void configureGrizzlyThreads(HttpServer server, String name, boolean isAdminAPI, Config config) {

		if (server.isStarted()) {
			/*
			 * It's possible to reconfigure running instances like this:
			 * 
			 * GrizzlyExecutorService workerPool = (GrizzlyExecutorService)
			 * listener.getTransport().getWorkerThreadPool();
			 * workerPool.reconfigure(workerConfig);
			 * 
			 * But that doesn't seem to get rid of old kernel/selector pools.
			 */
			throw new IllegalStateException("grizzly " + name + " shouldn't be running when configuring threads");
		}

		NetworkListener listener = server.getListeners().iterator().next();
		TCPNIOTransport transport = listener.getTransport();
	
		ThreadPoolConfig workerConfig = transport.getWorkerThreadPoolConfig();
		workerConfig.setPoolName("grizzly-worker-" + name);
		/*
		 * Reset the thread factory
		 * 
		 * The default worker config has a thread factory which has its own thread
		 * names. When it's set to null the thread names are created from the pool name.
		 */
		workerConfig.setThreadFactory(null);
		
		// use the default, because transport.getKernelThreadPoolConfig() is null until
		// the server is started
		int defaultKernelThreads = transport.getSelectorRunnersCount();
		
		int kernelThreads = defaultKernelThreads;		
		int workerThreadsCore = workerConfig.getCorePoolSize();
		int workerThreadsMax = workerConfig.getMaxPoolSize();
		
		if (isAdminAPI) {
			// two threads should still reveal simplest concurrency issues
			kernelThreads = 2;
			workerThreadsCore = 2;
			workerThreadsMax = 16;
		}
		
		if (config != null) {
			String configKernelThreads = config.getString(CONF_SERVER_THREADS_SELECTOR, name);
			String configWorkerThreadsCore = config.getString(CONF_SERVER_THREADS_WORKER_MIN, name);
			String configWorkerThreadsMax = config.getString(CONF_SERVER_THREADS_WORKER_MAX, name);
			
			if (!configKernelThreads.isEmpty()) {
				kernelThreads = Integer.parseInt(configKernelThreads);
			}
			
			if (!configWorkerThreadsCore.isEmpty()) {
				workerThreadsCore = Integer.parseInt(configWorkerThreadsCore);
			}
			
			if (!configWorkerThreadsMax.isEmpty()) {
				workerThreadsMax = Integer.parseInt(configWorkerThreadsMax);
			}
		}

		workerConfig.setCorePoolSize(workerThreadsCore);
		workerConfig.setMaxPoolSize(workerThreadsMax);

		transport.setWorkerThreadPoolConfig(workerConfig);

		ThreadPoolConfig kernelConfig = ThreadPoolConfig.defaultConfig();
		kernelConfig.setCorePoolSize(kernelThreads);
		kernelConfig.setMaxPoolSize(kernelThreads);
		kernelConfig.setPoolName("grizzly-kernel-" + name);

		transport.setSelectorRunnersCount(kernelThreads);
		transport.setKernelThreadPoolConfig(kernelConfig);

		logger.info(name + " configured to use " + kernelConfig.getCorePoolSize() + " selector threads and "
				+ workerConfig.getCorePoolSize() + "-" + workerConfig.getMaxPoolSize() + " worker threads");
	}

	public static void configureGrizzlyRequestLog(HttpServer httpServer, String name, LogType type) {
		try {
			AccessLogBuilder builder = new AccessLogBuilder("logs/" + name + "." + type.getType() + ".request.log");
			builder.rotatedDaily();
			builder.rotationPattern("yyyy-MM-dd");
			builder.format(ApacheLogFormat.COMBINED_FORMAT + " \"%{" + PubSubConfigurator.X_FORWARDED_FOR + "}i\"");
			builder.instrument(httpServer.getServerConfiguration());
		} catch (Exception e) {
			logger.error("failed to setup access log", e);
		}
	}
}
