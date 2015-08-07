package fi.csc.chipster.sessionstorage.rest;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Date;
import java.util.Random;

import com.fasterxml.jackson.core.JsonGenerationException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fi.csc.chipster.sessionstorage.model.Dataset;
import fi.csc.chipster.sessionstorage.model.Job;
import fi.csc.chipster.sessionstorage.model.Session;

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
		// FIXME replace with UUID
		Random rand = new Random();
		return "" + rand.nextInt(1000);
	}
	
    public static Session getRandomSession() {
    	Random rand = new Random();
    	
    	Session s = new Session();
//    	List<Dataset> datasets = new ArrayList<>();
//    	datasets.add(getRandomDataset());
//    	datasets.add(getRandomDataset());
//    	s.setDatasets(datasets);    	
    	
    	//s.setDatasets(Arrays.asList(new String[] { "d" + rand.nextInt(99), "d" + rand.nextInt(99), "d" + rand.nextInt(99) }));
    	s.setId("s" + rand.nextInt(99));
    	//s.setJobs(Arrays.asList(new String[] { "j" + rand.nextInt(99), "j" + rand.nextInt(99), "j" + rand.nextInt(99) }));
    	s.setName("dataset" + s.getId());
    	s.setOwner("me");
    	
    	return s;
    }
    
    public static Dataset getRandomDataset() {
    	
    	Dataset d = new Dataset();
    	d.setAccessed(new Date());
    	d.setChecksum("xyz");
    	d.setCreated(new Date());
    	d.setId("d" + RestUtils.createId());
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
		j.setJobId("j" + createId());
		j.setStartTime(new Date());
		j.setToolCategory("utilities");
		j.setToolDescription("very important tool");
		j.setToolId("UtilTool.py");
		j.setToolName("Utility tool");
		return j;
	}
}
