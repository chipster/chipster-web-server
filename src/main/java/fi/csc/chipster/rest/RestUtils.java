package fi.csc.chipster.rest;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.core.Response.ResponseBuilder;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.GrizzlyFuture;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.CommonProperties;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.server.filter.RolesAllowedDynamicFeature;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.jaxrs.json.JacksonJaxbJsonProvider;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.exception.LocalDateTimeContextResolver;
import fi.csc.chipster.rest.exception.NotFoundExceptionMapper;
import fi.csc.chipster.rest.pretty.JsonPrettyPrintQueryParamContainerResponseFilter;
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
		ObjectMapper mapper = new LocalDateTimeContextResolver().getContext(null);
		return mapper;
	}
	
	public static String asJson(Object obj) {
		return asJson(obj, false);
	}
	
	public static String asJson(Object obj, boolean pretty) {	
		// using Jackson library
		try {
			StringWriter writer = new StringWriter();
			// support for LocalDateTime
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
			// support for LocalDateTime
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
			// support for LocalDateTime
			ObjectMapper mapper = getObjectMapper();
			mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, failOnUnknownProperties);
			return mapper.readValue(reader, mapper.getTypeFactory().constructCollectionType(collectionType, itemType));
		} catch (IOException e) {
			logger.error("json parsing failed", e);
			throw new InternalServerErrorException();
		}
	}
	
	public static Date toDate(LocalDateTime dateTime) {
		if (dateTime != null) {
			return Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
		} else {
			return null;
		}
	}
	
	public static LocalDateTime toLocalDateTime(Date date) {
		if (date != null) {
			return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
		} else {
			return null;
		}
	}
	

	public static String createId() {
		return createUUID().toString();
	}
	
	public static UUID createUUID() {
		// FIXME secure UUID generation
		return UUID.randomUUID();
	}
	
    public static Session getRandomSession() {
    	
    	Session s = new Session();    	    	
    	//s.setSessionId(createUUID());
    	s.setName("session" + rand.nextInt(1000));
    	s.setCreated(LocalDateTime.now());
    	s.setAccessed(LocalDateTime.now());
    	
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
		j.setEndTime(LocalDateTime.now());
		//j.setJobId(createUUID());
		j.setState(JobState.NEW);
		j.setStartTime(LocalDateTime.now());
		j.setToolCategory("utilities");
		j.setToolDescription("very important tool");
		j.setToolId("UtilTool.py");
		j.setToolName("Utility tool");
		
		List<Parameter> p = new ArrayList<>();
		p.add(getRandomParameter());
		p.add(getRandomParameter());
		j.setParameters(p);
		
		List<Input> i = new ArrayList<>();
		i.add(getRandomInput());
		i.add(getRandomInput());
		j.setInputs(i);
		
		return j;
	}

	private static Input getRandomInput() {
		Input i = new Input();
		i.setInputId("inFile");
		i.setDisplayName("Input file");
		i.setDescription("Input file to process");
		i.setType("GENERIC");
		i.setDatasetId("apsodifupoiwuerpoiu");
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
				 // register all exception mappers
				.packages(NotFoundExceptionMapper.class.getPackage().getName())
				//.register(LocalDateTimeContextResolver.class)
				// add CORS headers
				.register(CORSResponseFilter.class)
				// enable the RolesAllowed annotation
				.register(RolesAllowedDynamicFeature.class)
				.register(JsonPrettyPrintQueryParamContainerResponseFilter.class); 
	}

	public static void shutdown(String name, HttpServer httpServer) {
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
}
