package fi.csc.chipster.backup;

import java.util.List;
import java.util.Optional;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.NotFoundException;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.hibernate.DbBackup;
import fi.csc.chipster.rest.hibernate.Transaction;

public class BackupAdminResource extends AdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private List<DbBackup> dbBackups;
	
	public BackupAdminResource(List<DbBackup> dbBackups) {
		super();
		
		this.dbBackups = dbBackups;
	}

	@POST
	@Path("backup/{role}")
	@RolesAllowed({Role.ADMIN})
	@Transaction
	public Response startBackup(@PathParam("role") String role, @Context SecurityContext sc) {
		
		Optional<DbBackup> dbBackupOptional = dbBackups.stream()
		.filter(b -> b.getRole().equals(role))
		.findFirst();
		
		if (dbBackupOptional.isPresent()) {
			
			new Thread(new Runnable() {
				@Override
				public void run() {				
					dbBackupOptional.get().cleanUpAndBackup();
				}			
			}).start();
			
			return Response.ok().build();		
		} else {
			throw new NotFoundException("service " + role  + " not found");
		}
		
    }
}
