package fi.csc.chipster.rest.hibernate;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

@Provider
@Transaction
public class HibernateRequestFilter implements ContainerRequestFilter {
	
	private HibernateUtil hibernate;

	public HibernateRequestFilter(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}

	@Override
	public void filter(ContainerRequestContext requestContext) {
		
		hibernate.beginTransaction();
	}
}