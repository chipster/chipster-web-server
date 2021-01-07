package fi.csc.chipster.rest.hibernate;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;

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
		Session session = hibernate.beginTransactionAndBind();
		requestContext.setProperty(PROP_HIBERNATE_SESSION, session);
	}
}