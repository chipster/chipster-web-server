package fi.csc.chipster.jobhistory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.hibernate.Criteria;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.Job;

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
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJobHistory(){
	
			//Create CriteriaBuilder
			CriteriaBuilder builder=getHibernate().session().getCriteriaBuilder();
			//Create CriteriaQuery
			CriteriaQuery<JobHistoryModel> criteria=builder.createQuery(JobHistoryModel.class);
			//Specify criteria root
			criteria.from(JobHistoryModel.class);
			Collection<JobHistoryModel> jobHistoryList=getHibernate().session().createQuery(criteria).getResultList();
			return Response.ok(toJaxbList(jobHistoryList)).build();
		
		
		
		
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

	private GenericEntity<Collection<JobHistoryModel>> toJaxbList(Collection<JobHistoryModel> result) {
		return new GenericEntity<Collection<JobHistoryModel>>(result) {};
	}
	
	private HibernateUtil getHibernate(){
		return hibernate;
	}
	
	
// http://127.0.0.1:8200
	// http://0.0.0.0:8200
}
