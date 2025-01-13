package fi.csc.chipster.backup;

import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.AdminResource;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.StatusSource;
import fi.csc.chipster.rest.hibernate.DbBackup;
import fi.csc.chipster.rest.hibernate.Transaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import jakarta.ws.rs.core.SecurityContext;

public class BackupAdminResource extends AdminResource {

	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private List<DbBackup> dbBackups;

	public BackupAdminResource(List<DbBackup> dbBackups, Config config) {
		super(config, dbBackups.toArray(new StatusSource[0]));

		this.dbBackups = dbBackups;
	}

	// unauthenticated but firewalled monitoring tap
	@GET
	@Path("monitoring/backup")
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response backupMonitoring(@Context SecurityContext sc) {
		for (DbBackup dbBackup : dbBackups) {
			if (!dbBackup.monitoringCheck()) {
				return Response.status(Status.NOT_FOUND.getStatusCode(), dbBackup.getRole() + " backup failed").build();
			}
		}
		return Response.ok().build();
	}

	@POST
	@Path("backup/{role}")
	@RolesAllowed({ Role.ADMIN })
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
			throw new NotFoundException("service " + role + " not found");
		}

	}
}
