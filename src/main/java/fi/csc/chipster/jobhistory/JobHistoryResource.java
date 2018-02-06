package fi.csc.chipster.jobhistory;

import java.util.UUID;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("jobhistory")
public class JobHistoryResource {
	
	private Config config;
	private String jobDetail;
	private HibernateUtil hibernate;
	private JobHistoryModel jobHistoyModel;
	
	public JobHistoryResource(HibernateUtil hibernate,Config config){
		this.config=config;
		this.hibernate=hibernate;
	}
	
	@GET
	@Produces("text/plain")
	public String getJobHistory(){
		return "Coming Soon";
	}
	
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Transaction
	public void postJobDetail(String toolName){
		System.out.println("Post is Working");
		
		System.out.println("Posted Data"+ toolName);
		jobHistoyModel=new JobHistoryModel();
		UUID uuid=RestUtils.createUUID();
		jobHistoyModel.setJobId(uuid);
		jobHistoyModel.setJobStatus("completed");
		jobHistoyModel.setToolName(toolName);
		
		getHibernate().session().save(jobHistoyModel);
		
		 JobHistoryModel js=getHibernate().session().get(JobHistoryModel.class, uuid);
		 System.out.println(js.getJobStatus());
	
		
		
	}
	
	
	private HibernateUtil getHibernate(){
		return hibernate;
	}
	
	
// http://127.0.0.1:8200
	// http://0.0.0.0:8200
}
