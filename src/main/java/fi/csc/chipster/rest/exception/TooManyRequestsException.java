package fi.csc.chipster.rest.exception;

import fi.csc.chipster.sessionworker.RequestThrottle;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;

public class TooManyRequestsException extends jakarta.ws.rs.ClientErrorException {

	public TooManyRequestsException(long retryAfterSeconds) {
		super(Response.status(Status.TOO_MANY_REQUESTS)
				.header(RequestThrottle.HEADER_RETRY_AFTER, retryAfterSeconds)
				.build());
	}
}
