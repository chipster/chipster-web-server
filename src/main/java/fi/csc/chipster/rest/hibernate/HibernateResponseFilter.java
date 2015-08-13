package fi.csc.chipster.rest.hibernate;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;

import org.glassfish.jersey.server.ContainerResponse;

@Provider
@Transaction
public class HibernateResponseFilter implements ContainerResponseFilter {

	private Hibernate hibernate;

	public HibernateResponseFilter(Hibernate hibernate) {
		this.hibernate = hibernate;
	}

	@Override
	public void filter(ContainerRequestContext requestContext,
			ContainerResponseContext responseContext) {
				
		if (responseContext instanceof ContainerResponse) {
			ContainerResponse response = (ContainerResponse) responseContext;
			
			if (response.isMappedFromException()) {
				hibernate.rollback();
				return;
			}
		}
		hibernate.commit();
	}
}