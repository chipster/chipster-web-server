package fi.csc.chipster.filestorage;

import java.net.URI;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.exception.NotAuthorizedException;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Invocation.Builder;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;

@Path("admin")
public class FileStorageAdminClient {

	private static final Logger logger = LogManager.getLogger();

	private CredentialsProvider credentials;

	private WebTarget target;
	private WebTarget unauthenticatedTarget;

	public FileStorageAdminClient(URI url, CredentialsProvider credentials) {
		this.credentials = credentials;

		init(url);
	}

	private void init(URI url) {

		if (this.credentials != null) {
			target = AuthenticationClient.getClient(credentials.getUsername(), credentials.getPassword(), true)
					.target(url);
		}
		unauthenticatedTarget = AuthenticationClient.getClient().target(url);
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

		getJson(false, "monitoring", "backup");
	}

	public void startBackup() {

		post("backup");
	}

	public void disableBackups() {

		delete("backup", "schedule");
	}

	public void enableBackups() {

		post("backup", "schedule");
	}

	public void startCheck(Long uploadMaxHours, Boolean deleteDatasetsOfMissingFiles) {

		WebTarget checkTarget = this.target.path("admin").path("check");

		if (uploadMaxHours != null) {
			checkTarget = checkTarget.queryParam("uploadMaxHours", uploadMaxHours);
		}

		if (deleteDatasetsOfMissingFiles != null) {
			checkTarget = checkTarget.queryParam("deleteDatasetsOfMissingFiles", deleteDatasetsOfMissingFiles);
		}

		post(checkTarget);
	}

	public void deleteOldOrphans() {

		post("delete-orphans");
	}

	public String getStatus() {
		return getJson(true, "status");
	}

	public String getStorageId() {
		return getJson(true, "id");
	}

	public String getFileStats() {
		return getJson(true, "filestats");
	}

	public String getJson(boolean authenticate, String... paths) {

		WebTarget target = this.unauthenticatedTarget;

		if (authenticate) {
			if (this.target == null) {
				throw new IllegalStateException(this.getClass().getSimpleName() + " initilised without credentials");
			}
			target = this.target;
		}

		target = target.path("admin");

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

		return post(target);
	}

	private String post(WebTarget target) {

		logger.info("post " + target.getUri());

		Builder request = target.request();

		Response response = request.post(null);

		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw toException(response);
		}
		return response.readEntity(String.class);
	}

	public String delete(String... paths) {

		WebTarget target = this.target.path("admin");

		for (String path : paths) {
			target = target.path(path);
		}

		logger.info("delete " + target.getUri());

		Builder request = target.request();

		Response response = request.delete();

		if (!RestUtils.isSuccessful(response.getStatus())) {
			throw toException(response);
		}
		return response.readEntity(String.class);
	}
}
