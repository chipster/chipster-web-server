package fi.csc.chipster.jobhistory;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.List;


import javax.websocket.MessageHandler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.glassfish.grizzly.http.server.HttpServer;
import org.glassfish.jersey.grizzly2.httpserver.GrizzlyHttpServerFactory;
import org.glassfish.jersey.server.ResourceConfig;
import org.hibernate.Session;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.hibernate.HibernateRequestFilter;
import fi.csc.chipster.rest.hibernate.HibernateResponseFilter;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.HibernateUtil.HibernateRunnable;
import fi.csc.chipster.scheduler.IdPair;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.SessionDbClient.SessionEventListener;
import fi.csc.chipster.sessiondb.SessionDbTopicConfig;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.SessionEvent;
import fi.csc.chipster.sessiondb.model.SessionEvent.ResourceType;

/**
 * Main class for Job Log Service
 * 
 *
 */
public class JobHistoryService implements SessionEventListener,MessageHandler{
	
	private Logger logger = LogManager.getLogger();
	private HttpServer httpServer;
	private HibernateUtil hibernate;
	private JobHistoryResource jobHistoryResource;
	private Config config;
	private ServiceLocatorClient serviceLocator;
	private AuthenticationClient authService;
	private SessionDbClient sessionDbClient;

	public JobHistoryService(Config config) {
		this.config=config;
	

	}
	
	
	/**
	 * Starts the Grizzly HTTP Server
	 * @throws URISyntaxException 
	 * @throws IOException
	 * @throws RestException 
	 */
	
	public void startServer() throws URISyntaxException, IOException, RestException{
		//WebSocket connection to Session DB
		String username=Role.JOB_HISTORY;
		String password=this.config.getPassword(username);
		
		this.serviceLocator=new ServiceLocatorClient(this.config);
		this.authService=new AuthenticationClient(serviceLocator, username, password);
		
		this.sessionDbClient=new SessionDbClient(serviceLocator, authService.getCredentials());
		this.sessionDbClient.subscribe(SessionDbTopicConfig.JOBS_TOPIC, this, "job-history");
		
		
		
		List<Class<?>> hibernateClasses = Arrays.asList(JobHistoryModel.class);
		// Initializing hibernate components
		hibernate = new HibernateUtil(this.config, "job-history");
		hibernate.buildSessionFactory(hibernateClasses);
		
		jobHistoryResource = new JobHistoryResource(hibernate, this.config);
		final ResourceConfig rc = RestUtils.getDefaultResourceConfig()
				.register(jobHistoryResource)
				.register(new HibernateRequestFilter(hibernate))
				.register(new HibernateResponseFilter(hibernate))
		// .register(RestUtils.getLoggingFeature("session-db"))
		;
		
		//Start the HTTP server, now the URL is hard coded, which needed to change
		httpServer = GrizzlyHttpServerFactory.createHttpServer(new URI(
				"http://127.0.0.1:8200"), rc);

		httpServer.start();
		
		
	}
	
	private HttpServer getHttpServer(){
		return httpServer;
	}
	

	public static void main(String[] args) throws URISyntaxException, IOException, RestException  {
		final JobHistoryService jobHistoryService= new JobHistoryService(new Config());
		jobHistoryService.startServer();
		RestUtils.shutdownGracefullyOnInterrupt(jobHistoryService.httpServer, "job-history");
		RestUtils.waitForShutdown("Job History service", jobHistoryService.getHttpServer());

	}


	@Override
	public void onEvent(SessionEvent e) {
		// TODO Auto-generated method stub
		logger.info("received a job event: " + e.getResourceType() + " " + e.getType());
		try {			
			if (e.getResourceType() == ResourceType.JOB) {
				handleDbEvent(e, new IdPair(e.getSessionId(), e.getResourceId()));
			}
		} catch (Exception ex) {
			logger.error("error when handling a session event", ex);
		}
		
		
	}
	
	/**
	 * Handling the events received from session-db
	 * 
	 */
	
	private void handleDbEvent(SessionEvent e,IdPair jobIdPair) throws RestException{
		System.out.println(e.getType()); 
		switch(e.getType()){
		case CREATE:
			Job job=sessionDbClient.getJob(e.getSessionId(),e.getResourceId());
			System.out.println(job.getState());
			switch(job.getState()){
			case NEW:
				//When a client adds a new job, save it the job history database
				//hibernate.getsession.save()
				saveJobHistory(job);
			break;
			default:
				break;
			}
			break;
		case UPDATE:
			job=sessionDbClient.getJob(e.getSessionId(), e.getResourceId());
			System.out.println(job.getState());
			switch (job.getState()){
			case COMPLETED:
			case FAILED:
			case FAILED_USER_ERROR:
				// update the DB entry for that Job
				System.out.println("Job failed");
				updateJobHistory(job);
				break;
			default:
				break;
			}
		default:
			break;
			
			//what to do with if the client has cancelled the job?
		
		}
	}
	
	
	private void saveJobHistory(Job job){
		JobHistoryModel jobHistory=new JobHistoryModel();
		jobHistory.setJobId(job.getJobId());
		System.out.println(job.getJobId());
		jobHistory.setToolName(job.getToolName());
		jobHistory.setStartTime(job.getStartTime());
		jobHistory.setEndTime(job.getEndTime());
		jobHistory.setOutput(job.getScreenOutput());
		jobHistory.setJobStatus(job.getStateDetail());
		
		getHibernate().runInTransaction(new HibernateRunnable<Void>() {
			@Override
			public Void run(Session hibernateSession) {
				hibernateSession.save(jobHistory);		
				JobHistoryModel js=hibernateSession.get(JobHistoryModel.class, jobHistory.getJobId());
				System.out.println(js.getJobId());
				return null;
			}
		});
		
		//getHibernate().getSessionFactory().getCurrentSession().beginTransaction();
		//getHibernate().session().save(jobHistory);
		 
		  
	}
	
	
		
	private void updateJobHistory(Job job){
		System.out.print(job.getStateDetail());
	}
	
	private HibernateUtil getHibernate(){
		return hibernate;
	}
	
	
	

}
