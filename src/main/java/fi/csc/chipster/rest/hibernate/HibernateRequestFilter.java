package fi.csc.chipster.rest.hibernate;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

import org.hibernate.Session;

@Provider
@Transaction
public class HibernateRequestFilter implements ContainerRequestFilter {
	
	public static final String PROP_HIBERNATE_SESSION = "hibernateSession";
	private HibernateUtil hibernate;

	public HibernateRequestFilter(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}

	@Override
	public void filter(ContainerRequestContext requestContext) {
		Session session = hibernate.beginTransaction();
		requestContext.setProperty(PROP_HIBERNATE_SESSION, session);
	}
}