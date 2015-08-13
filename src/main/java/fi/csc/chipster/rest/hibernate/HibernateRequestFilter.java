package fi.csc.chipster.rest.hibernate;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.ext.Provider;

@Provider
@Transaction
public class HibernateRequestFilter implements ContainerRequestFilter {
	
	private Hibernate hibernate;

	public HibernateRequestFilter(Hibernate hibernate) {
		this.hibernate = hibernate;
	}

	@Override
	public void filter(ContainerRequestContext requestContext) {
		
		System.out.println("** Hibernate begin transaction");
		hibernate.beginTransaction();
	}
}