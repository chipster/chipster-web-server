package fi.csc.chipster.sessionworker;

import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.util.Date;

import javax.annotation.security.RolesAllowed;
import javax.mail.MessagingException;
import javax.ws.rs.Consumes;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.jetty.http.HttpStatus;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.auth.model.User;
import fi.csc.chipster.auth.model.UserId;
import fi.csc.chipster.rest.Config;
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
	@Path("request")
	@RolesAllowed(Role.CLIENT)
	@Consumes(MediaType.APPLICATION_JSON)
    public Response post(SupportRequest feedback, @Context SecurityContext sc) throws RestException {
		
		String userId = sc.getUserPrincipal().getName();
		
		// protect email infrastructure from attacks
		Duration retryAfter = requestThrottle.throttle(userId);
		if (retryAfter.isZero()) {
			
			User user = authService.getUser(new UserId(userId));

			logger.info("got feedback from: " + user.getUserId().toUserIdString());
			
			String emailBody = getEmailBody(feedback, user);
			String emailSubject = getEmailSubject(feedback, user);
			String emailReplyTo = getEmailReplyTo(feedback, user);
			
			if (emailBody.length() + emailSubject.length() + emailReplyTo.length() > MAX_EMAIL_BYTES) {
		    	logger.warn("support request from " + user.getUserId().toUserIdString() + " rejected: payload too large");
		    	return Response.status(HttpStatus.PAYLOAD_TOO_LARGE_413).build();
		    }
			
			if (this.supportEmail != null && !supportEmail.isEmpty()) {							        	    	    	  
			    try {
			    	this.emails.send(emailSubject, emailBody, supportEmail, emailReplyTo);
			    	return Response.noContent().build();
			    	 
			    } catch (UnsupportedEncodingException | MessagingException e) {
					throw new InternalServerErrorException("sending support email failed", e);
				}
			} else {
				// log the message to make the development easier
				logger.warn("support-email is not configured, the feedback will be logged");
				logger.info("Subject: " + emailSubject);
				logger.info("Reply-To: " + emailReplyTo);
				logger.info("Body: \n" + emailBody);
				return Response.noContent().build();
			}

		} else {
			// + 1 to round up
			long ceilSeconds = retryAfter.getSeconds() + 1;
			return Response
					.status(HttpStatus.TOO_MANY_REQUESTS_429)
					.header("Retry-After", ceilSeconds)
					.build();
		}
    }
	
	private String getEmailBody(SupportRequest feedback, User user) {
		String userIdString = user.getUserId().toUserIdString();		   
	        
	    String sessionUrl = feedback.getSession();
	    if (sessionUrl == null) {
	    	sessionUrl = "[not available]";
	    }	    

	    String emailBody = "userId: " + userIdString + "\n";
	    
	    if (user.getName() != null) {
	    	emailBody += "name: " + user.getName() + "\n";
	    } else {
	    	emailBody += "name: [not available]\n";
	    }
	    
	    if (user.getOrganization() != null) {
	    	emailBody += "organization: " + user.getOrganization() + "\n";
	    } else {
	    	emailBody += "organization: [not available]\n";
	    }
	        
	    emailBody +=  "session: " + sessionUrl + "\n";
	    	    
	    if (user.getMail() != null) {
	    	emailBody += "email (from authentication): " + user.getMail() + "\n";	    	
	    }
	    
	    // show the email given by user if it's different
	    if (feedback.getMail() != null && !feedback.getMail().equals(user.getMail())) {
	    	emailBody += "email (given by user): " + feedback.getMail() + "\n";
	    }
	    
	    // show warning if we didn't get email address from the authentication or user has changed it
	    if (!feedback.getMail().equals(user.getMail())) {
	    	emailBody += "Privacy warning!\n"
	    			+ "User can enter any email address. Make sure you share only information about "
	    			+ "the authenticated user (userId, name, organization above) even if the email address "
	    			+ "would have someone else's name.\n";
	    }
	    
	    if (user.getMail() == null && feedback.getMail() == null) {
	    	emailBody += "email: [not available]\n";
	    }
	     
	    emailBody += "\n";
	    
	    emailBody += "message: \n" + feedback.getMessage() + "\n\n";
	    
	    emailBody += "error message: \n";
	    emailBody += feedback.getErrorMessage() + "\n\n";
	    
	    return emailBody;
	}

	private String getEmailReplyTo(SupportRequest feedback, User user) {
	    
	    if (feedback.getMail() != null) {
	    	return feedback.getMail();	    	
	    }
	    
	    return user.getMail();	   
	}

	private String getEmailSubject(SupportRequest feedback, User user) {
		
		DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd"); 
        String dateString = dateFormat.format(new Date());
        
        String subject = dateString + " Help request from " + user.getUserId().toUserIdString();

	    return subject;
	}
}
