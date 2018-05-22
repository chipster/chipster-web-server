package fi.csc.chipster.rest.hibernate;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.h2.tools.Server;

public class DbServer {
	
	private final Logger logger = LogManager.getLogger();
	
	private Server server;
	private int port;
	private String role;

	public DbServer(String role, int port) {
		this.port = port;
		this.role = role;
	}
	
	public void start() throws SQLException, IOException {
		// get the physical path even if the database folder is a symlink
		String baseDir = new File("database").toPath().toRealPath().toString();
		server = Server.createTcpServer(
				"-tcpPort", "" + port,
				"-baseDir", baseDir
				//, "-tcpAllowOthers" // not needed in localhost
				).start();
		
		logger.info(role + "-h2 started at port " + port);
	}
	
	public void close() {		
		server.stop();
	}
	
	public static void main(String args[]) throws SQLException, IOException {
		new DbServer("test", 1521).start();
	}
}
