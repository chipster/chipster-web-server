package fi.csc.chipster.jobhistory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.GenericEntity;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;


@Path("jobhistory")
public class JobHistoryResource {
	
	private Config config;
	private String jobDetail;
	private HibernateUtil hibernate;
	public static final String QUERY_PARAM_FILTER_ATTRIBUTE = "filter_attribute";
	public static final String QUERY_PARAM_FILTER_VALUE = "filter_value";
	
	public JobHistoryResource(HibernateUtil hibernate,Config config){
		this.config=config;
		this.hibernate=hibernate;
	}
	
	@GET
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getJobHistory(@Context UriInfo uriInfo){
		
		 MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters(); 
		 Map<String,String> parameters = new HashMap<String,String>();

		   for(String str : queryParams.keySet()){
		     parameters.put(str, queryParams.getFirst(str));
		   }
	    System.out.println("parameters"+parameters);
	    
	    CriteriaBuilder builder=getHibernate().session().getCriteriaBuilder();
		//Create CriteriaQuery
		CriteriaQuery<JobHistoryModel> criteria=builder.createQuery(JobHistoryModel.class);
		//Specify criteria root
		Root<JobHistoryModel> root=criteria.from(JobHistoryModel.class);
	
		
	
	    if(parameters.size()>0){
	    	//handle multiple filter attribute
	    	//Constructing the list of parameters
			List<Predicate> predicate=new ArrayList<Predicate>();
			
			for(String key: queryParams.keySet()){
				System.out.println(key);
				if(key!=null){
					try{
						System.out.println(parameters.get(key));
						predicate.add(builder.equal(root.get(key),parameters.get(key)));
					}catch(IllegalArgumentException e){
						
					}
					
				}
			}
			
			//Query itself
			criteria.select(root).where(predicate.toArray(new Predicate[]{}));
			Collection<JobHistoryModel> jobHistoryList=getHibernate().session().createQuery(criteria).getResultList();
			return Response.ok(toJaxbList(jobHistoryList)).build();
	    }else{   	
	    	criteria.select(root);
	    	Collection<JobHistoryModel> jobHistoryList=getHibernate().session().createQuery(criteria).getResultList();
			return Response.ok(toJaxbList(jobHistoryList)).build();
	    	
	    }
	   	

	
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
