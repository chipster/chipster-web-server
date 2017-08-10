package fi.csc.chipster.filebroker;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.GenericAdminResource;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.rest.ServletUtils;


public class FileBrokerAdminServlet extends HttpServlet {

	@SuppressWarnings("unused")
	private static final Logger logger = LogManager.getLogger();
		
	private File storageRoot;

	private AuthenticationClient authService;

	public FileBrokerAdminServlet(File storageRoot, AuthenticationClient authService) {

		this.storageRoot = storageRoot;
		this.authService = authService;
	}
	
	@Override
	protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
		
		HashMap<String, Object> status = new HashMap<>();		
		
		if (ServletUtils.isInRole(Role.ADMIN, request, authService)) {
			status.put("volumeTotalSpace", storageRoot.getTotalSpace());
			status.put("volumeUsableSpace", storageRoot.getUsableSpace());
			status.put("volumeFreeSpace", storageRoot.getFreeSpace());
			status.put("fileCount", Files.list(Paths.get(storageRoot.getAbsolutePath())).count());
			status.put("fileTotalSize", Files.list(Paths.get(storageRoot.getAbsolutePath()))
					.mapToLong(p -> p.toFile().length()).sum());
			
			status.putAll(GenericAdminResource.getSystemStats());
		}
		
		status.put(GenericAdminResource.KEY_STATUS, GenericAdminResource.VALUE_OK);
		
		response.setContentType("application/json");
		
		PrintWriter out = response.getWriter();
		out.println(RestUtils.asJson(status));
	}		
}


