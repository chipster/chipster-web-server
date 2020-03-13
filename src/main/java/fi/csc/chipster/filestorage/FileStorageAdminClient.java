package fi.csc.chipster.filestorage;

import java.net.URI;

import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.InternalServerErrorException;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.Path;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.Invocation.Builder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;

@Path("admin")
public class FileStorageAdminClient {

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();

	private CredentialsProvider credentials;	
	private WebTarget target;
	
	
	public FileStorageAdminClient(URI url, CredentialsProvider credentials) {
		this.credentials = credentials;
		
		init(url);
	}
	
	private void init(URI url) {

		target = AuthenticationClient.getClient(credentials.getUsername(), credentials.getPassword(), true).target(url);
	}
	
	/**
	 * Check that previous backup was successful
	 * 
	 * If not, throw an error.
	 * 
	 * @param storageId
	 * @param sc
	 */
	public void checkBackup() {
		
		getJson("monitoring", "backup");
	}

	public void startBackup() {
		
		post("backup");		
    }
	
	public void startCheck() {
		post("check");
    }
	
	public void deleteOldOrphans() {
		
		post("delete-orphans");		
    }
		
	public String getJson(String... paths) {
		
		WebTarget target = this.target.path("admin");
		
		for (String path : paths) {
			target = target.path(path);
		}
				
		Builder request = target.request();
		
		logger.info("get " + target.getUri());
				
		Response response = request.get(Response.class);
		
		if (!RestUtils.isSuccessful(response.getStatus())) {
			
			throw toException(response);
		}
		return response.readEntity(String.class);
	}
	
	public static WebApplicationException toException(Response response) {
		int statusCode = response.getStatus();
		String msg = response.readEntity(String.class);
		if (statusCode == HttpServletResponse.SC_FORBIDDEN) {
			return new ForbiddenException(msg);
		} else if (statusCode == HttpServletResponse.SC_UNAUTHORIZED) {
			return new NotAuthorizedException(msg);
		} else if (statusCode == HttpServletResponse.SC_NOT_FOUND) {
			return new NotFoundException(msg);
		} else {
			return new InternalServerErrorException(statusCode + " " + msg);
		}
	}


	public String post(String... paths) {
		
		WebTarget target = this.target.path("admin");
		
		for (String path : paths) {
			target = target.path(path);
		}
				
		logger.info("post " + target.getUri());
		
		Builder request = target.request();		
				
		Response response = request.post(null);
		
		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw toException(response);
		}
		return response.readEntity(String.class);
	}
}
