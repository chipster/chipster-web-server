package fi.csc.chipster.sessionstorage.rest;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fi.csc.chipster.sessionstorage.model.Dataset;
import fi.csc.chipster.sessionstorage.model.Job;
import fi.csc.chipster.sessionstorage.model.Parameter;
import fi.csc.chipster.sessionstorage.model.Session;
import fi.csc.microarray.description.SADLSyntax.ParameterType;
import fi.csc.microarray.messaging.JobState;

public class RestUtils {
	/**
	 * Converts an object to json for debug messages. In case of errors, returns the
	 * stack trace.
	 * 
	 * @param obj
	 * @return
	 */
	public static String asJsonDebug(Object obj) {	
		try {
			return asJson(obj);        
		} catch (IOException e) {
			StringWriter trace = new StringWriter();
			e.printStackTrace(new PrintWriter(trace));
			return trace.toString();
		}
	}
	
	public static String asJson(Object obj) throws JsonGenerationException, JsonMappingException, IOException {	
		// using Jackson library
		StringWriter writer = new StringWriter();
		ObjectMapper mapper = new ObjectMapper();
		mapper.writeValue(writer, obj);
		return writer.toString();        
	}
	

	public static String createId() {
		// FIXME secure UUID generation
		return UUID.randomUUID().toString();
	}
	
    public static Session getRandomSession() {
    	
    	Session s = new Session();    	    	
    	s.setId(createId());
    	s.setName("session" + s.getId());
    	s.setOwner("me");
    	
    	return s;
    }
    
    public static Dataset getRandomDataset() {
    	
    	Dataset d = new Dataset();
    	d.setAccessed(new Date());
    	d.setChecksum("xyz");
    	d.setCreated(new Date());
    	d.setId(createId());
    	d.setName("dataset" + d.getId());
    	d.setSize(0);
    	d.setSourceJob("j" + RestUtils.createId());
    	d.setX(100);
    	d.setY(100);
    	
    	return d;
    }

	public static Job getRandomJob() {
		Job j = new Job();
		j.setEndTime(new Date());
		j.setJobId(createId());
		j.setState(JobState.COMPLETED);
		j.setStartTime(new Date());
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
		p.setId(createId());
		p.setDisplayName("Tool parameter");
		p.setDescription("Desckription of the tool parameter");
		p.setType(ParameterType.STRING);
		p.setValue("Parameter value");
		return p;
	}
}
