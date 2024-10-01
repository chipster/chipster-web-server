package fi.csc.chipster.rest.hibernate;

import org.glassfish.jersey.server.ContainerResponse;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.Provider;

/**
 * Commit or rollback all transactions
 * 
 * ResponseFilters are executed before json serialization in
 * MessageBodyWriter
 * (https://eclipse-ee4j.github.io/jersey.github.io/documentation/latest31x/filters-and-interceptors.html#d0e10213),
 * so all DB content must have been loaded before this is
 * called. Otherwise the json serialization will show an error
 * "JsonMappingException: failed to lazily initialize a collection of ..., could
 * not initialize proxy - no Session".
 * 
 * Possible solutions:
 * 
 * - Use FetchType.EAGER, but it can cause performance issues
 * - Load all necessary values by using them or with Hibernate.initialize()
 * - Find out how @Transactional is implemented in other frameworks
 * - Find a way to run this later. Maybe replace Jackson MessageBodyWriter
 * (https://github.com/FasterXML/jackson-1/blob/master/src/jaxrs/java/org/codehaus/jackson/jaxrs/JacksonJsonProvider.java)?
 * Or handle this on Servlet/Server level?
 */
@Provider
@Transaction
public class HibernateResponseFilter implements ContainerResponseFilter {

	private HibernateUtil hibernate;

	public HibernateResponseFilter(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}

	@Override
	public void filter(ContainerRequestContext requestContext,
			ContainerResponseContext responseContext) {

		if (requestContext.getProperty(HibernateRequestFilter.PROP_HIBERNATE_SESSION) == null) {
			// nothing to do if HibernateRequestFilter didn't run, e.g. on
			// AuthenticationRequestFilter errors
			return;
		}

		if (responseContext instanceof ContainerResponse) {
			ContainerResponse response = (ContainerResponse) responseContext;

			if (response.isMappedFromException()) {
				hibernate.rollbackAndUnbind();
				return;
			}
		}
		hibernate.commitAndUnbind();
	}
}