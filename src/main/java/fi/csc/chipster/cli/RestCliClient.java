package fi.csc.chipster.cli;

import java.util.HashMap;
import java.util.UUID;

import javax.ws.rs.WebApplicationException;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Session;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;

/**
 * Chipster command line client
 * 
 * To run this on command line:
 * - run gradle task installDist, which copies all the dependencies to one directory
 * - cd into the project's directory and run 
 * 
 *     java -cp bin:build/install/chipster-web-server/lib/* fi.csc.chipster.cli.RestCliClient -h
 * 
 * @author klemela
 */
public class RestCliClient {
	
	private static final String SESSION_ARG = "SESSION";
	private static final String QUIET = "quiet";
	private static final String VERBOSE = "verbose";
	private static final String HOST = "HOST";
	private static final String PASSWORD = "password";
	private static final String USERNAME = "username";
	private static final String RUN = "run";
	private static final String DOWNLOAD = "download";
	private static final String DETAILS = "details";
	private static final String PRINT = "print";
	private static final String SET = "set";
	private static final String UPLOAD = "upload";
	private static final String DELETE = "delete";
	private static final String LIST = "list";
	private static final String CREATE = "create";
	private static final String DATASET = "dataset";
	private static final String JOB = "job";
	private static final String TOOL = "tool";
	private static final String SESSION = "session";
	private static final String SUBSUBCOMMAND = "subsubcommand";
	private static final String SUBCOMMAND = "subcommand";
	
	private String proxy;
	private CredentialsProvider credentials;
	private Boolean verbose;
	private Boolean quiet;
	private SessionDbClient sessionDbClient;

	public static void main(String[] args) {
	    new RestCliClient(args);
	}
	
	public RestCliClient(String[] args) {
		ArgumentParser parser = getArgumentParser();
	    try {
	    	Namespace namespace = parser.parseArgs(args);
	        System.out.println(namespace);
	        execute(namespace);
	    } catch (ArgumentParserException e) {
	        parser.handleError(e);
	        System.exit(1);
	    } catch (RestException|WebApplicationException|IllegalArgumentException e) {
			System.err.println("Request failed: " + e.getMessage());
			System.exit(1);
		}
	}
	
	private ArgumentParser getArgumentParser() {
		ArgumentParser parser = ArgumentParsers.newArgumentParser("chipster-cli");
	    parser.addArgument(HOST).help("hostname or IP address of the Chipster server and [:PORT]");
	    parser.addArgument("-u", "--" + USERNAME).action(Arguments.store()).help("username");
	    parser.addArgument("-p", "--" + PASSWORD).action(Arguments.store()).help("password");
	    parser.addArgument("-s", "--" + SESSION).action(Arguments.store()).help("session name or ID to work on");
	    
	    parser.addArgument("-v", "--" + VERBOSE).action(Arguments.storeTrue()).help("more verbose output");
	    parser.addArgument("-q", "--" + QUIET).action(Arguments.storeTrue()).help("quieter output");
	    
	    Subparsers subparsers = parser.addSubparsers().title("subcommands").metavar("COMMAND").dest(SUBCOMMAND);
	    Subparsers sessionSubparsers = subparsers.addParser(SESSION).help("manage sessions").addSubparsers().title("session subcommands").metavar("COMMAND").dest(SUBSUBCOMMAND);
	    Subparsers toolSubparsers    = subparsers.addParser(TOOL).help("get information about tools").addSubparsers().title("tool subcommands").metavar("COMMAND").dest(SUBSUBCOMMAND);
	    Subparsers jobSubparsers     = subparsers.addParser(JOB).help("run and follow jobs").addSubparsers().title("job subcommands").metavar("COMMAND").dest(SUBSUBCOMMAND);
	    Subparsers datasetSubparsers = subparsers.addParser(DATASET).help("manage datasets").addSubparsers().title("dataset subcommands").metavar("COMMAND").dest(SUBSUBCOMMAND);

	    Subparser sessionCreateParser = sessionSubparsers.addParser(CREATE).help("create new session");
	    sessionCreateParser.addArgument(SESSION_ARG).help("name of the session to be created");
	    sessionSubparsers.addParser(LIST).help("list sessions");
	    sessionSubparsers.addParser(DETAILS).help("view session details");
	    sessionSubparsers.addParser(DELETE).help("delete session");
	    	    
	    datasetSubparsers.addParser(LIST).help("list all datasets in the session");
	    Subparser datasetUploadParser = datasetSubparsers.addParser(UPLOAD).help("upload a dataset");
	    datasetUploadParser.addArgument("FILE").type(Arguments.fileType().acceptSystemIn().verifyCanRead()).help("file to upload or - to read from the standard in");
	    Subparser datasetDownlaodParser = datasetSubparsers.addParser(DOWNLOAD).help("download a dataset");
	    datasetDownlaodParser.addArgument("DATASET").help("dowload dataset to a file");
	    
	    Subparser datasetSetParser = datasetSubparsers.addParser(SET).help("edit dataset details");
	    datasetSetParser.addArgument("DATASET");
	    datasetSetParser.addArgument("DETAIL");
	    datasetSetParser.addArgument("VALUE");
	    datasetSubparsers.addParser(PRINT).help("print the dataset contents to the standard output");
	    datasetSubparsers.addParser(DETAILS).help("view dataset details");
	    datasetSubparsers.addParser(DELETE).help("delete dataset");
	    
	    Subparser toolListParser = toolSubparsers.addParser(LIST).help("list tools");
	    toolListParser.addArgument("FILTER").nargs("?").help("list only tools that have this string in its name");
	    Subparser toolDetailsParser = toolSubparsers.addParser(DETAILS).help("view tool details");
	    toolDetailsParser.addArgument("TOOL");
	    
	    jobSubparsers.addParser(LIST).help("list jobs");
	    jobSubparsers.addParser(DETAILS).help("view job details");
	    Subparser jobRunParser = jobSubparsers.addParser(RUN).help("start new job");
	    jobRunParser.addArgument("TOOL").help("tool to run");
	    jobRunParser.addArgument("--dataset").nargs("*").action(Arguments.append()).help("input datasets");
	    jobRunParser.addArgument("--parameter").nargs(2).metavar("PARAMETER", "VALUE").action(Arguments.append()).help("parameter name and value");

	    return parser;
	}
	
	private void execute(Namespace namespace) throws RestException {
		
		verbose = namespace.getBoolean(VERBOSE);
		quiet = namespace.getBoolean(QUIET);
		
		proxy = "http://" + namespace.getString(HOST) + "/";
		
		String username = namespace.getString(USERNAME);
		String password = namespace.getString(PASSWORD);
		
		if (username != null || password != null) {
			String authURI = proxy + "auth/";
			verbose("authenticating to " + authURI);
			credentials = new AuthenticationClient(authURI, username, password).getCredentials();		
		}
			
		String subcommand = namespace.getString(SUBCOMMAND);
		
		switch (subcommand) {
		case SESSION:
			executeSessionSubcommand(namespace);
			break;
		case TOOL:
			executeToolSubcommand(namespace);
			break;
		case JOB:
			executeJobSubcommand(namespace);
			break;
		case DATASET:
			executeDatasetSubcommand(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}

	private void verbose(String msg) {
		if (verbose) {
			System.out.println(msg);
		}
	}

	private void executeDatasetSubcommand(Namespace namespace) {
		String subcommand = namespace.getString(SUBSUBCOMMAND);
		switch (subcommand) {
		case LIST:
			datasetList(namespace);
			break;
		case UPLOAD:
			datasetUpload(namespace);
			break;
		case SET:
			datasetSet(namespace);
			break;
		case DETAILS:
			datasetDetails(namespace);
			break;
		case PRINT:
			datasetPrint(namespace);
			break;
		case DELETE:
			datasetDelete(namespace);
			break;
		case DOWNLOAD:
			datasetDownload(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}


	private void executeJobSubcommand(Namespace namespace) throws RestException {
		String subcommand = namespace.getString(SUBSUBCOMMAND);
		switch (subcommand) {
		case LIST:
			jobList(namespace);
			break;
		case DETAILS:
			jobDetais(namespace);
			break;
		case RUN:
			jobRun(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}

	
	private void executeToolSubcommand(Namespace namespace) {
		String subcommand = namespace.getString(SUBSUBCOMMAND);
		switch (subcommand) {
		case LIST:
			toolList(namespace);
			break;
		case DETAILS:
			toolDetails(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}
	
	private void executeSessionSubcommand(Namespace namespace) throws RestException {
		String subcommand = namespace.getString(SUBSUBCOMMAND);
		switch (subcommand) {
		case CREATE:
			sessionCreate(namespace);
			break;
		case LIST:
			sessionList(namespace);
			break;
		case DETAILS:
			sessionDetails(namespace);
			break;
		case DELETE:
			sessionDelete(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}

	private void datasetDownload(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}

	private void datasetDelete(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}

	private void datasetPrint(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}

	private void datasetDetails(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}

	private void datasetSet(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}

	private void datasetUpload(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}

	private void datasetList(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}
	
	private void jobRun(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}

	private void jobDetais(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}

	private void jobList(Namespace namespace) throws RestException {
		HashMap<UUID, Job> jobs = getSessionDbClient().getJobs(getSession(namespace).getSessionId());
		
		for (Job job : jobs.values()) {
			printFixed("" + job.getJobId(), 36);
			printFixed("" + job.getToolId(), 30);
			printFixed("" + job.getState(), 24);
			printFixed("" + job.getStateDetail(), 24);
			System.out.println();
		}
	}

	private void toolDetails(Namespace namespace) {
		// TODO Auto-generated method stub
		
	}

	private void toolList(Namespace namespace) {
		// TODO Auto-generated method stub
	}
	
	private void sessionDelete(Namespace namespace) throws RestException {
		Session session = getSession(namespace);
		getSessionDbClient().deleteSession(session.getSessionId());
	}
	
	private void sessionDetails(Namespace namespace) throws RestException {
		Session session = getSession(namespace);
		printKeyValue("name", session.getName());
		printKeyValue("created", session.getCreated());
		printKeyValue("accessed", session.getAccessed());
		printKeyValue("sessionId", session.getSessionId());
		printKeyValue("notes", session.getNotes());
	}

	private Session getSession(Namespace namespace) throws RestException {
		
		String str = namespace.getString(SESSION);
		
		if (str != null) {			
			HashMap<UUID, Session> sessions = getSessionDbClient().getSessions();
			for (Session session : sessions.values()) {
				if (str.equals(session.getName())) {
					return session;
				}
			}
			for (UUID uuid : sessions.keySet()) {
				if (uuid.toString().startsWith(str)) {
					return sessions.get(uuid);
				}
			}
		}
		throw new IllegalArgumentException("session not found");
	}

	private void sessionList(Namespace namespace) throws RestException {
		SessionDbClient sessionDb = getSessionDbClient();
		for (Session session :  sessionDb.getSessions().values()) {
			if (quiet) {
				System.out.println(session.getName());
			} else {
				printFixed("" + session.getSessionId(), 36);
				printFixed(session.getName(), 30);
				printFixed("" + session.getCreated(), 24);
				printFixed("" + session.getAccessed(), 24);
				System.out.println();
			}
		}
	}
	
	private void sessionCreate(Namespace namespace) throws RestException {
		Session session = new Session();
		session.setName(namespace.getString(SESSION_ARG));
		getSessionDbClient().createSession(session);
	}

	private SessionDbClient getSessionDbClient() {
		if (sessionDbClient == null) {
			sessionDbClient = new SessionDbClient(proxy + "sessiondb/", proxy + "sessiondbevents/", credentials);
		}
		return sessionDbClient;
	}
	
	private void printFixed(String string, int length) {
		System.out.print(StringUtils.rightPad(string, length) + "  ");
	}
	
	private void printKeyValue(String key, Object value) {
		String margin = StringUtils.rightPad("", 20);
		String wrappedValue = WordUtils.wrap("" + value, 60).replace("\n", "\n" + margin);
		System.out.println(StringUtils.rightPad(key, 20) + wrappedValue);
	}
}
