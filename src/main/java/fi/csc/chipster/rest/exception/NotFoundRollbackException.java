package fi.csc.chipster.rest.exception;

import jakarta.ws.rs.core.Response;
/**
 * 
 * Default NotFoundException doesn't cause rollback.
 * 
 * Use this and respective ExceptionMapper to get rollback when throwing the exception.
 * (Each request gets transaction (if @Transaction in the request method)).
 * 
 * 
 * @author hupponen
 *
 */
@SuppressWarnings("serial")
public class NotFoundRollbackException extends jakarta.ws.rs.NotFoundException {

    /**
     * Construct a new "not found" exception.
     */
    public NotFoundRollbackException() {
        super();
    }

    /**
     * Construct a new "not found" exception.
     *
     * @param message the detail message (which is saved for later retrieval by the {@link #getMessage()} method).
     */
    public NotFoundRollbackException(final String message) {
        super(message);
    }

    /**
     * Construct a new "not found" exception.
     *
     * @param response error response.
     * @throws IllegalArgumentException in case the status code set in the response is not HTTP {@code 404}.
     */
    public NotFoundRollbackException(final Response response) {
        super(response);
    }

    /**
     * Construct a new "not found" exception.
     *
     * @param message the detail message (which is saved for later retrieval by the {@link #getMessage()} method).
     * @param response error response.
     * @throws IllegalArgumentException in case the status code set in the response is not HTTP {@code 404}.
     */
    public NotFoundRollbackException(final String message, final Response response) {
        super(message, response);
    }

    /**
     * Construct a new "not found" exception.
     *
     * @param cause the underlying cause of the exception.
     */
    public NotFoundRollbackException(final Throwable cause) {
        super(cause);
    }

    /**
     * Construct a new "not found" exception.
     *
     * @param message the detail message (which is saved for later retrieval by the {@link #getMessage()} method).
     * @param cause the underlying cause of the exception.
     */
    public NotFoundRollbackException(final String message, final Throwable cause) {
        super(message, cause);
    }

    /**
     * Construct a new "not found" exception.
     *
     * @param response error response.
     * @param cause the underlying cause of the exception.
     * @throws IllegalArgumentException in case the status code set in the response is not HTTP {@code 404}.
     */
    public NotFoundRollbackException(final Response response, final Throwable cause) {
        super(response, cause);
    }

    /**
     * Construct a new "not found" exception.
     *
     * @param message the detail message (which is saved for later retrieval by the {@link #getMessage()} method).
     * @param response error response.
     * @param cause the underlying cause of the exception.
     * @throws IllegalArgumentException in case the status code set in the response is not HTTP {@code 404}.
     */
    public NotFoundRollbackException(final String message, final Response response, final Throwable cause) {
        super(message, response, cause);
    }}

