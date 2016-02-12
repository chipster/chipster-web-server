package fi.csc.chipster.sessiondb.resource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.criterion.Projections;
import org.hibernate.metadata.ClassMetadata;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import fi.csc.chipster.sessiondb.model.TableStats;

@Path("admin")
public class SessionDbAdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private HibernateUtil hibernate;

	public SessionDbAdminResource(HibernateUtil hibernate) {
		this.hibernate = hibernate;
	}
	
	@GET
    @Path("tables")
    @Produces(MediaType.APPLICATION_JSON)
	@RolesAllowed(Role.SESSION_DB)
    @Transaction
    public Response get(@PathParam("id") UUID authorizationId, @Context SecurityContext sc) throws IOException {
    	return Response.ok(getTableStats(hibernate.session())).build();    	
    }
	
	public ArrayList<TableStats> getTableStats(Session hibernateSession) {
		ArrayList<TableStats> tables = new ArrayList<>();
		
		Map<String, ClassMetadata> classMetadata = hibernateSession.getSessionFactory().getAllClassMetadata();

		for (String className : classMetadata.keySet()) {
			Number size = (Number) hibernateSession.createCriteria(className).setProjection(Projections.rowCount()).uniqueResult();;
			TableStats table = new TableStats();
			table.setName(className.substring(className.lastIndexOf(".") + 1));
			table.setSize((long) size);
			tables.add(table);
		}
		
		return tables;
	}
}
