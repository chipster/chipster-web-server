package fi.csc.chipster.filebroker;

import javax.annotation.security.RolesAllowed;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.StatusSource;

public class FileBrokerAdminResource extends AdminResource {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	private StorageBackup backup;
	
	public FileBrokerAdminResource(StatusSource stats, StorageBackup backup) {
		super(stats);
		
		this.backup = backup;
	}

	@POST
	@Path("backup")
	@RolesAllowed({Role.ADMIN})
	public Response startBackup(@Context SecurityContext sc) {
		new Thread(new Runnable() {
			@Override
			public void run() {
				backup.backupNow();
			}			
		}).start();
		
		return Response.ok().build();
    }
}
