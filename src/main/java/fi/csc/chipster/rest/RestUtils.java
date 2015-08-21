package fi.csc.chipster.rest;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.InternalServerErrorException;

import com.fasterxml.jackson.databind.ObjectMapper;

import fi.csc.chipster.sessionstorage.model.Dataset;
import fi.csc.chipster.sessionstorage.model.File;
import fi.csc.chipster.sessionstorage.model.Job;
import fi.csc.chipster.sessionstorage.model.Parameter;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.microarray.description.SADLSyntax.ParameterType;
import fi.csc.microarray.messaging.JobState;

public class RestUtils {
	
	private static Logger logger = Logger.getLogger(RestUtils.class.getName());
	
	public static String asJson(Object obj) {	
		// using Jackson library
		try {
			StringWriter writer = new StringWriter();
			ObjectMapper mapper = new ObjectMapper();
			mapper.writeValue(writer, obj);
			return writer.toString();        
		} catch (IOException e) {
			logger.log(Level.SEVERE, "json conversion failed", e);
			throw new InternalServerErrorException();
		}
	}
	
	public static <T> T parseJson(Class<T> obj, String json) {	
		// using Jackson library
		try {
			StringReader reader= new StringReader(json);
			ObjectMapper mapper = new ObjectMapper();
			return mapper.readValue(reader, obj);
		} catch (IOException e) {
			logger.log(Level.SEVERE, "json parsing failed", e);
			throw new InternalServerErrorException();
		} 
	}
	
	public static Date toDate(LocalDateTime dateTime) {
		return Date.from(dateTime.atZone(ZoneId.systemDefault()).toInstant());
	}
	
	public static LocalDateTime toLocalDateTime(Date date) {
		return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault());
	}
	

	public static String createId() {
		// FIXME secure UUID generation
		return UUID.randomUUID().toString();
	}
	
    public static Session getRandomSession() {
    	
    	Session s = new Session();    	    	
    	s.setSessionId(createId());
    	s.setName("session" + s.getSessionId());
    	s.setOwner("me");
    	s.setCreated(LocalDateTime.now());
    	s.setAccessed(LocalDateTime.now());
    	
    	return s;
    }
    
    public static Dataset getRandomDataset() {
    	
    	Dataset d = new Dataset();
    	d.setDatasetId(createId());
    	d.setName("dataset" + d.getDatasetId());
    	d.setSourceJob("j" + RestUtils.createId());
    	d.setX(100);
    	d.setY(100);
    	
    	File f = new File();
    	f.setFileId(createId());
    	f.setChecksum("xyz");
    	f.setSize(0);
    	d.setFile(f);
    	
    	return d;
    }

	public static Job getRandomJob() {
		Job j = new Job();
		j.setEndTime(LocalDateTime.now());
		j.setJobId(createId());
		j.setState(JobState.COMPLETED);
		j.setStartTime(LocalDateTime.now());
		j.setToolCategory("utilities");
		j.setToolDescription("very important tool");
		j.setToolId("UtilTool.py");
		j.setToolName("Utility tool");
		
		List<Parameter> p = new ArrayList<>();
		p.add(getRandomParameter());
		p.add(getRandomParameter());
		j.setParameters(p);
		return j;
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
}
