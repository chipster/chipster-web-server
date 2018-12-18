package fi.csc.chipster.sessionworker;

import java.io.UnsupportedEncodingException;
import java.time.Duration;

import javax.annotation.security.RolesAllowed;
import javax.mail.MessagingException;
import javax.ws.rs.BadRequestException;
import javax.ws.rs.Consumes;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.RestException;

@Path("support")
public class SupportResource {	

	private static Logger logger = LogManager.getLogger(); 
	
	private SmtpEmails emails;
	private RequestThrottle requestThrottle;
	private String supportEmail;

	private AuthenticationClient authService;
	
	private static final int MAX_EMAIL_BYTES = 100 * 1024;
	
	public SupportResource(Config config, AuthenticationClient authService) {
		this.authService = authService;
		
		String host = config.getString(Config.SMTP_HOST);
		int port = config.getInt(Config.SMTP_PORT);
		String username = config.getString(Config.SMPT_USERNAME);
		String password = config.getString(Config.SMTP_PASSWORD);
		boolean tls = config.getBoolean(Config.SMTP_TLS);
		boolean auth = config.getBoolean(Config.SMTP_AUTH);
		String from = config.getString(Config.SMTP_FROM);
		String fromName = config.getString(Config.SMTP_FROM_NAME);
		
		this.emails = new SmtpEmails(host, port, username, password, tls, auth, from, fromName);
		
		int throttleMinutes = config.getInt(Config.SUPPORT_THROTTLE_PERIOD);
		int throttleRequestCount = config.getInt(Config.SUPPORT_THROTTLE_REQEUST_COUNT);
		
		this.requestThrottle = new RequestThrottle(Duration.ofMinutes(throttleMinutes), throttleRequestCount);
		
		this.supportEmail = config.getString(Config.SUPPORT_EMAIL);
	}
	
	@POST
	@Consumes(MediaType.APPLICATION_JSON)
	@Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.CLIENT)
	@Path("request")
	@Transaction
    public Response post(SupportRequest supportRequest, @Context SecurityContext sc) throws RestException {
		
		String userId = sc.getUserPrincipal().getName();
		
		// protect email infrastructure from attacks
		Duration retryAfter = requestThrottle.throttle(userId);
		if (retryAfter.isZero()) {
			
			User user = authService.getUser(new UserId(userId));
			
			sendEmail(supportRequest, user);			

			return Response.ok().build();
		} else {
			// + 1 to round up
			long ceilSeconds = retryAfter.getSeconds() + 1;
			return Response
					.status(429)
					.header("Retry-After", ceilSeconds)
					.build();
		}
    }

	private void sendEmail(SupportRequest feedback, User user) {
		
		String userIdString = user.getUserId().toUserIdString();
		logger.info("got feedback from: " + userIdString);	    		   
	        
	    String sessionUrl = feedback.getSession();
	    if (sessionUrl == null) {
	    	sessionUrl = "[not available]";
	    }	    

	    String emailBody =
	        feedback.getMessage() + "\n\n" +
	        "userId: " + userIdString + "\n" +
	        "session: " + sessionUrl + "\n";
	    
	    String userMail = user.getMail();
	    
	    if (user.getMail() != null) {
	    	emailBody += "email (from authentication): " + userMail + "\n";	    	
	    } else if (feedback.getMail() != null) {
	    	userMail = feedback.getMail();
	    	emailBody += "email (supplied by user): " + userMail + "\n";
	    } else {
	    	userMail = null;
	    	emailBody += "email: [not available]\n";
	    }
	     
	    emailBody += "\n\n";
	    
	    emailBody += "error message: \n";
	    emailBody += feedback.getErrorMessage() + "\n\n";	    		
	    
	    String subject = "Help request from " + userIdString;
	    
	    if (emailBody.length() + subject.length() > MAX_EMAIL_BYTES) {
	    	// should be really HTTP 413, but this unusual case is not worth of new exception class
	    	logger.warn("support request from " + userIdString  + " rejected: payload too large");
	    	throw new BadRequestException("Payload Too Large");
	    }
	    
	    try {
	    	this.emails.send(subject, emailBody, supportEmail, userMail);
	    	
	    } catch (UnsupportedEncodingException | MessagingException e) {
			throw new InternalServerErrorException("sending support email failed", e);
		}	
	}
}
