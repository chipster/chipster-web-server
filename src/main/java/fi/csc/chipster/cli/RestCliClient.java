package fi.csc.chipster.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.WebApplicationException;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.sessiondb.model.Session;
import fi.csc.chipster.toolbox.ToolboxClientRest;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.microarray.description.SADLDescription;
import fi.csc.microarray.description.SADLDescription.Name;
import fi.csc.microarray.messaging.JobState;
import net.sourceforge.argparse4j.ArgumentParsers;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.ArgumentParserException;
import net.sourceforge.argparse4j.inf.Namespace;
import net.sourceforge.argparse4j.inf.Subparser;
import net.sourceforge.argparse4j.inf.Subparsers;

/**
 * Chipster command line client for the Rest API
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
	
	private static final String DIR = "DIR";
	private static final String IMPORT = "import";
	private static final String EXPORT = "export";
	private static final String PARAMETER = "parameter";
	private static final String FILE = "FILE";
	private static final String FILTER = "FILTER";
	private static final String VALUE = "VALUE";
	private static final String DETAIL = "DETAIL";
	private static final String NOTES = "notes";
	private static final String NAME = "name";
	private static final String SESSION_ARG = "SESSION";
	private static final String DATASET_ARG = "DATASET";
	private static final String TOOL_ARG = "TOOL";
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
	private static Long t;
	
	private String proxy;
	private CredentialsProvider credentials;
	private Boolean verbose = false;
	private Boolean quiet = false;
	private SessionDbClient sessionDbClient;
	private ToolboxClientRest toolboxClient;
	private RestFileBrokerClient fileBrokerClient;

	public static void main(String[] args) throws IOException {
		time("");
	    new RestCliClient(args);
	}
	
	/**
	 * Print the given string and time elapsed since the previous call. Useful for quick performance checks.
	 * 
	 * @param string
	 */
	private static void time(String string) {
		if (t != null) {
			//System.out.println("## " + string + " " + (System.currentTimeMillis() - t) + "ms");
		}
		t = System.currentTimeMillis();
	}

	public RestCliClient(String[] args) throws IOException {
		ArgumentParser parser = getArgumentParser();
	    try {
	    	Namespace namespace = parser.parseArgs(args);
	    	time("parse");
	        //System.out.println(namespace);
	        execute(namespace);
	    } catch (ArgumentParserException e) {
	        parser.handleError(e);
	        if (verbose) {
				e.printStackTrace();
			}
	        System.exit(1);
	    } catch (RestException|WebApplicationException|IllegalArgumentException e) {
			System.err.println("Request failed: " + e.getMessage());
			if (verbose) {
				e.printStackTrace();
			}
			System.exit(1);
		} catch (FileNotFoundException e) {
			System.err.println("File not found: " + e.getMessage());
			if (verbose) {
				e.printStackTrace();
			}
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

	    // sessions
	    
	    Subparser sessionCreateParser = sessionSubparsers.addParser(CREATE).help("create new session");
	    sessionCreateParser.addArgument(SESSION_ARG).help("name of the session to be created");
	    
	    sessionSubparsers.addParser(LIST).help("list sessions");
	    sessionSubparsers.addParser(DETAILS).help("view session details");
	    
	    sessionSubparsers.addParser(DELETE).help("delete session");
	    
	    Subparser sessionExportParser = sessionSubparsers.addParser(EXPORT).help("copy the whole session to a local directory");
	    sessionExportParser.addArgument(DIR);

	    Subparser sessionImportParser = sessionSubparsers.addParser(IMPORT).help("import a session from the local directory");
	    sessionImportParser.addArgument(DIR);
	    sessionImportParser.addArgument(NAME).nargs("?").help("rename the imported session");
	    	    
	    // datasets
	    
	    datasetSubparsers.addParser(LIST).help("list all datasets in the session");
	    Subparser datasetUploadParser = datasetSubparsers.addParser(UPLOAD).help("upload a dataset");
	    datasetUploadParser.addArgument(FILE).help("file to upload or - to read from the standard in");
	    datasetUploadParser.addArgument(NAME).nargs("?").help("name for the uploaded dataset");
	    
	    Subparser datasetDownlaodParser = datasetSubparsers.addParser(DOWNLOAD).help("download a dataset");
	    datasetDownlaodParser.addArgument(DATASET_ARG).help("dataset to download");
	    datasetDownlaodParser.addArgument(FILE).nargs("?").help("destination file (dataset name by default)");
	    
	    Subparser datasetSetParser = datasetSubparsers.addParser(SET).help("edit dataset details");
	    datasetSetParser.addArgument(DATASET_ARG);
	    datasetSetParser.addArgument(DETAIL).choices(NAME, NOTES);
	    datasetSetParser.addArgument(VALUE);
	    
	    Subparser datasetPrintParser = datasetSubparsers.addParser(PRINT).help("print the dataset contents to the standard output");
	    datasetPrintParser.addArgument(DATASET_ARG);
	    
	    Subparser datasetDetailsParser = datasetSubparsers.addParser(DETAILS).help("view dataset details");
	    datasetDetailsParser.addArgument(DATASET_ARG);
	    
	    datasetSubparsers.addParser(DELETE).help("delete dataset");
	    
	    // tools
	    
	    Subparser toolListParser = toolSubparsers.addParser(LIST).help("list tools");
	    toolListParser.addArgument(FILTER).nargs("?").help("list only tools that have this string in its name");
	    
	    Subparser toolDetailsParser = toolSubparsers.addParser(DETAILS).help("view tool details");
	    toolDetailsParser.addArgument(TOOL_ARG);
	    
	    // jobs
	    
	    jobSubparsers.addParser(LIST).help("list jobs");
	    Subparser jobDetailsParser = jobSubparsers.addParser(DETAILS).help("view job details");
	    jobDetailsParser.addArgument(JOB).help("view job details");
	    
	    Subparser jobRunParser = jobSubparsers.addParser(RUN).help("start new job");
	    jobRunParser.addArgument(TOOL_ARG).help("tool to run");
	    jobRunParser.addArgument("-D", "--" + DATASET).nargs("*").action(Arguments.append()).help("input datasets");
	    jobRunParser.addArgument("-P", "--" + PARAMETER).nargs(2).metavar("PARAMETER", VALUE).action(Arguments.append()).help("parameter name and value");

	    return parser;
	}
	
	private void execute(Namespace namespace) throws RestException, IOException {
		
		verbose = namespace.getBoolean(VERBOSE);
		quiet = namespace.getBoolean(QUIET);
		
		proxy = "http://" + namespace.getString(HOST) + "/";
		
		String username = namespace.getString(USERNAME);
		String password = namespace.getString(PASSWORD);
		
		if (username != null || password != null) {
			String authURI = proxy + "auth/";
			verbose("authenticating to " + authURI);
			credentials = new AuthenticationClient(authURI, username, password).getCredentials();
			time("authenticate");
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

	private void executeDatasetSubcommand(Namespace namespace) throws RestException, IOException {
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
	
	private void executeSessionSubcommand(Namespace namespace) throws RestException, IOException {
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
		case EXPORT:
			sessionExport(namespace);
			break;
		case IMPORT:
			sessionImport(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}

	private void datasetDownload(Namespace namespace) throws RestException, IOException {
		Dataset dataset = getDataset(namespace);
		String filePath = namespace.getString(FILE);
		if (filePath == null) {
			filePath = dataset.getName();
		}
		File destFile = new File(filePath);
		getFileBrokerClient().download(getSessionId(namespace), dataset.getDatasetId(), destFile);
	}

	private void datasetDelete(Namespace namespace) throws RestException {
		Dataset dataset = getDataset(namespace);
		getSessionDbClient().deleteDataset(getSessionId(namespace), dataset.getDatasetId());
	}

	private void datasetPrint(Namespace namespace) throws IOException, RestException {
		Dataset dataset = getDataset(namespace);
		try (InputStream inStream = getFileBrokerClient().download(getSessionId(namespace), dataset.getDatasetId())) { 
			IOUtils.copy(inStream, System.out);
		}
	}

	private void datasetDetails(Namespace namespace) throws RestException {
		Dataset dataset = getDataset(namespace);
		printKeyValue(NAME, dataset.getName());
		printKeyValue(NOTES, dataset.getNotes());
		printKeyValue("dataset ID", dataset.getDatasetId());
		if (dataset.getFile() != null) {
			printKeyValue("size", dataset.getFile().getSize());
			printKeyValue("checksum", dataset.getFile().getChecksum());
		}
		if (dataset.getSourceJob() != null) {
			Job job = getSessionDbClient().getJob(getSessionId(namespace), dataset.getSourceJob());
			printKeyValue("source tool", job.getToolId());
			printKeyValue("source job ID", job.getJobId().toString());
		}
	}

	private void datasetSet(Namespace namespace) throws RestException {
		Dataset dataset = getDataset(namespace);
		String value = namespace.getString(VALUE);
		switch (namespace.getString(DETAIL)) {
		case NAME:
			dataset.setName(value);
			break;
		case NOTES:
			dataset.setNotes(value);
			break;
		}
		getSessionDbClient().updateDataset(getSessionId(namespace), dataset);
	}

	private void datasetUpload(Namespace namespace) throws RestException, FileNotFoundException {
		String filePath = namespace.getString(FILE);
		String name = namespace.getString(NAME);
		if (name == null) {
			if ("-".equals(filePath)) {
				name = "upload";
			} else {
				name = filePath;
			}
		}
		
		InputStream inStream;
		if ("-".equals(filePath)) {
			inStream = System.in;
		} else {
			inStream = new FileInputStream(filePath);
		}
		
		try {
			Dataset dataset = new Dataset();
			dataset.setName(name);
			getSessionDbClient().createDataset(getSessionId(namespace), dataset);
			getFileBrokerClient().upload(getSessionId(namespace), dataset.getDatasetId(), inStream);
		} finally {
			IOUtils.closeQuietly(inStream);
		}
		
	}

	private void datasetList(Namespace namespace) throws RestException {
		HashMap<UUID, Dataset> datasets = getSessionDbClient().getDatasets(getSessionId(namespace));
		time("list datasets");
		
		for (Dataset dataset : datasets.values()) {
			printFixed(dataset.getDatasetId().toString(), 36);
			printFixed(dataset.getName(), 40);
			System.out.println();
		}
	}
	
	@SuppressWarnings("unchecked")
	private void jobRun(Namespace namespace) throws RestException {
		ToolboxTool tool = getToolboxClient().getTool(namespace.getString(TOOL_ARG));
		List<Dataset> datasets = new ArrayList<>();
		HashMap<String, String> parameters = new HashMap<>();

		if (namespace.getList(DATASET) != null) {
			for (Object innerList : namespace.getList(DATASET)) {
				for (Object dataset : (List<Object>)innerList) {
					datasets.add(getDataset("" + dataset, namespace));
				}
			}
		}
		
		if (namespace.getList(PARAMETER) != null) {
			for (Object paramObj : namespace.getList(PARAMETER)) {
				List<String> paramList = (List<String>) paramObj;
				parameters.put(paramList.get(0), paramList.get(1));
			}
		}
		
		List<Input> jobInputs = new ArrayList<>();
		
		//TODO take input types into account
		
		// required inputs
		for (SADLDescription.Input toolInput : tool.getSadlDescription().getInputs()) {
			if (toolInput.isOptional()) {
				continue;
			}
			if (datasets.isEmpty()) {
				throw new IllegalArgumentException("not enough input datasets for input " + toolInput.getName().getID());
			}
			Input jobInput = new Input();
			Dataset dataset = datasets.remove(0);
			jobInput.setDatasetId(dataset.getDatasetId().toString());
			jobInput.setInputId(toolInput.getName().getID());
			jobInput.setDisplayName(dataset.getName());
			jobInputs.add(jobInput);
		}
		
		// optional inputs
		for (SADLDescription.Input toolInput : tool.getSadlDescription().getInputs()) {
			if (!toolInput.isOptional()) {
				continue;
			}
			if (datasets.isEmpty()) {
				break;
			}
			Input jobInput = new Input();
			Dataset dataset = datasets.remove(0);
			jobInput.setDatasetId(dataset.getDatasetId().toString());
			jobInput.setInputId(toolInput.getName().getID());
			jobInput.setDisplayName(dataset.getName());
			jobInputs.add(jobInput);
		}
		
		List<Parameter> jobParameters = new ArrayList<>();
		
		for (SADLDescription.Parameter toolParameter : tool.getSadlDescription().getParameters()) {
			Parameter jobParameter = new Parameter();
			String id = toolParameter.getName().getID();
			jobParameter.setParameterId(id);
			if (parameters.containsKey(id)) {
				jobParameter.setValue(parameters.get(id));
			} else {
				jobParameter.setValue(toolParameter.getDefaultValue());
			}
			jobParameters.add(jobParameter);
		}
		
		Job job = new Job();
		job.setParameters(jobParameters);
		job.setInputs(jobInputs);
		job.setToolId(tool.getSadlDescription().getName().getID());
		job.setState(JobState.NEW);
		job.setToolName(tool.getSadlDescription().getName().getDisplayName());
		
		getSessionDbClient().createJob(getSessionId(namespace), job);
	}

	private void jobDetais(Namespace namespace) throws RestException {
		Job job = getJob(namespace.getString(JOB), namespace);
		
		printKeyValue("module", job.getModule());
		printKeyValue("category", job.getToolCategory());
		printKeyValue("tool ID", job.getToolId());
		printKeyValue("tool name", job.getToolName());
		printKeyValue("description", job.getToolDescription());
		printKeyValue("state", job.getState());
		printKeyValue("state detail", job.getStateDetail());
		printKeyValue("start time", job.getStartTime());
		printKeyValue("end time", job.getEndTime());
		printKeyValue("job ID", job.getJobId());
		if (verbose) {
			if (job.getScreenOutput() != null) {
				printKeyValue("screen output", "");
				System.out.println(job.getScreenOutput());
			} else {
				printKeyValue("source code", "");
				System.out.println(job.getSourceCode());
			}
		}
		printKeyValue("parameters", "");
		for (Parameter param : job.getParameters()) {
			printKeyValue("  " + param.getParameterId(), param.getValue());
			if (verbose) {
				printKeyValue("", param.getDescription());
			}
		}
		printKeyValue("input datasets", "");
		for (Input input : job.getInputs()) {
			if (input.getDatasetId() != null) {
				Dataset dataset = getSessionDbClient().getDataset(getSessionId(namespace), UUID.fromString(input.getDatasetId()));				
				printKeyValue("  " + input.getInputId(), dataset.getName());
			} else {
				printKeyValue("  " + input.getInputId(), "");
			}
			if (verbose && input.getDescription() != null) {
				printKeyValue("", input.getDescription());
			}
		}
	}

	private void jobList(Namespace namespace) throws RestException {
		HashMap<UUID, Job> jobs = getSessionDbClient().getJobs(getSessionId(namespace));
		
		for (Job job : jobs.values()) {
			if (!verbose && (
					job.getState() == JobState.COMPLETED ||
					job.getState() == JobState.CANCELLED ||
					job.getState() == JobState.ERROR ||
					job.getState() == JobState.FAILED ||
					job.getState() == JobState.FAILED_USER_ERROR)) {
					continue;
			}
			printFixed("" + job.getJobId(), 36);
			printFixed("" + job.getToolId(), 30);
			printFixed("" + job.getState(), 24);
			printFixed("" + job.getStateDetail(), 24);
			System.out.println();
		}
	}

	private UUID getSessionId(Namespace namespace) throws RestException {
		return getSession(namespace).getSessionId();
	}

	private void toolDetails(Namespace namespace) {
		ToolboxTool tool = getToolboxClient().getTool(namespace.getString(TOOL_ARG));
		printKeyValue("module", tool.getModule());
		printKeyValue("tool ID", tool.getSadlDescription().getName().getID());
		printKeyValue("name", tool.getSadlDescription().getName().getDisplayName());
		printKeyValue("description", tool.getSadlDescription().getDescription());
		
		printKeyValue("parameters", "");
		for (SADLDescription.Parameter param : tool.getSadlDescription().getParameters()) {			
			printKeyValue("  parameter ID", param.getName().getID());
			printKeyValue("  name", param.getName().getDisplayName());
			printKeyValue("  description", param.getDescription());
			printKeyValue("  type", param.getType());
			printKeyValue("  default value", param.getDefaultValue());
			if (param.getSelectionOptions() != null) {
				printKeyValue("  options", "");
				for (Name option : param.getSelectionOptions()) {
					printFixed("    " + option.getID(), 40);
					printFixed(option.getDisplayName(), 30);
					System.out.println();
				}
			}
			if (param.getFrom() != null) {
				printKeyValue("  min", param.getFrom());
			}
			if (param.getTo() != null) {
				printKeyValue("  max", param.getTo());
			}
			System.out.println();
		}
		printKeyValue("input datasets", "");
		
		for (SADLDescription.Input input : tool.getSadlDescription().getInputs()) {
			printFixed("  " + input.getName().getID(), 30);
			printFixed(input.getName().getDisplayName(), 30);
			printFixed(input.getType().getName(), 10);
			printFixed(input.isOptional() ? "optional" : "", 10);
			
			if (verbose) {
				printKeyValue("  description", input.getDescription());
			}
			
			System.out.println();
		}
		
		printKeyValue("output datasets", "");
		
		for (SADLDescription.Output output : tool.getSadlDescription().getOutputs()) {
			printFixed("  " + output.getName().getDisplayName(), 30);
			printFixed(output.isOptional() ? "optional" : "", 10);
			//printFixed(output.getDescription(), 30);
			System.out.println();
		}
		

		if (verbose) {
			printKeyValue("source", "");
			System.out.println(tool.getSource());
		}
	}

	private void toolList(Namespace namespace) {
		String filter = namespace.getString(FILTER);
		HashMap<String, SADLDescription> tools = getToolboxClient().getTools();
		
		for (SADLDescription tool : tools.values()) {
			if (filter != null && (
					!tool.getName().getID().toLowerCase().contains(filter.toLowerCase()) || 
					!tool.getName().getDisplayName().toLowerCase().contains(filter.toLowerCase()))) {
				continue;
			}
			//printFixed(tool.Module(), 12);
			printFixed(tool.getName().getID(), 40);
			printFixed(tool.getName().getDisplayName(), 40);
			System.out.println();
		}
	}
	
	private void sessionDelete(Namespace namespace) throws RestException {
		Session session = getSession(namespace);
		getSessionDbClient().deleteSession(session.getSessionId());
	}
	
	private void sessionDetails(Namespace namespace) throws RestException {
		Session session = getSession(namespace);
		printKeyValue(NAME, session.getName());
		printKeyValue("created", session.getCreated());
		printKeyValue("accessed", session.getAccessed());
		printKeyValue("session ID", session.getSessionId());
		printKeyValue(NOTES, session.getNotes());
	}

	private Dataset getDataset(Namespace namespace) throws RestException {
		
		String str = namespace.getString(DATASET_ARG);
		return getDataset(str, namespace);
	}
		
	private Dataset getDataset(String str, Namespace namespace) throws RestException {

		if (str != null) {	
			HashMap<UUID, Dataset> datasets = getSessionDbClient().getDatasets(getSessionId(namespace));
			time("list datasets");
			for (Dataset dataset : datasets.values()) {
				if (str.equals(dataset.getName())) {
					return dataset;
				}
			}
			for (UUID uuid : datasets.keySet()) {
				if (uuid.toString().startsWith(str)) {
					return datasets.get(uuid);
				}
			}
		}
		throw new IllegalArgumentException("dataset not found");
	}
	
	private Job getJob(String str, Namespace namespace) throws RestException {
		
		if (str != null) {		
			HashMap<UUID, Job> jobs = getSessionDbClient().getJobs(getSessionId(namespace));
			time("list datasets");
			for (Job job : jobs.values()) {
				if (str.equals(job.getToolName())) {
					return job;
				}
				//check that this was the only match
			}
			for (UUID uuid : jobs.keySet()) {
				if (uuid.toString().startsWith(str)) {
					return jobs.get(uuid);
				}
			}
		}
		throw new IllegalArgumentException("job not found");
	}

	private Session getSession(Namespace namespace) throws RestException {
		
		String str = namespace.getString(SESSION);
		
		if (str != null) {			
			HashMap<UUID, Session> sessions = getSessionDbClient().getSessions();
			time("list sessions");
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
	
	private void sessionExport(Namespace namespace) throws RestException, IOException {
		File dir = new File(namespace.getString(DIR));
		File jobLinks = new File(dir, "jobs");
		File datasetLinks = new File(dir, "datasets");
		File fileLinks = new File(dir, "files");
		File jobs = new File(jobLinks, "UUID");
		File datasets = new File(datasetLinks, "UUID");
		File files = new File(fileLinks, "UUID");
		
		dir.mkdirs();
		jobs.mkdirs();
		datasets.mkdirs();
		files.mkdirs();
		
		Session session = getSessionDbClient().getSession(getSessionId(namespace));
		
		FileUtils.writeStringToFile(new File(dir,  "session.json"), RestUtils.asJson(session));
		
		for (Dataset dataset : session.getDatasets().values()) {
			verbose(dataset.getName());
			FileUtils.writeStringToFile(new File(datasets,  dataset.getDatasetId().toString()), RestUtils.asJson(dataset));
			// I guess we can use datasetId for the file also, because there should be no need for file deduplication within a session
			getFileBrokerClient().download(getSessionId(namespace),dataset.getDatasetId(), new File(files, dataset.getDatasetId().toString()));
			
			Files.createSymbolicLink(getUniqueFile(datasetLinks, dataset.getName()).toPath(), new File("UUID", dataset.getDatasetId().toString()).toPath());
			Files.createSymbolicLink(getUniqueFile(fileLinks, dataset.getName()).toPath(), new File("UUID", dataset.getDatasetId().toString()).toPath());
		}
		
		for (Job job : session.getJobs().values()) {
			FileUtils.writeStringToFile(new File(jobs,  job.getJobId().toString()), RestUtils.asJson(job));
			Files.createSymbolicLink(getUniqueFile(jobLinks, job.getToolId()).toPath(), new File("UUID", job.getJobId().toString()).toPath());
		}	
	}

	private void sessionImport(Namespace namespace) throws RestException, IOException {
		String name = namespace.getString(NAME);
		File dir = new File(namespace.getString(DIR));
		File jobLinks = new File(dir, "jobs");
		File datasetLinks = new File(dir, "datasets");
		File fileLinks = new File(dir, "files");
		File jobs = new File(jobLinks, "UUID");
		File datasets = new File(datasetLinks, "UUID");
		File files = new File(fileLinks, "UUID");
		
		Session session = RestUtils.parseJson(Session.class, FileUtils.readFileToString(new File(dir,  "session.json")));
		if (name != null) {
			session.setName(name);
		}	
		
		session.setSessionId(null);
		UUID sessionId = getSessionDbClient().createSession(session);
		
		for (File file : getUUIDFiles(datasets)) {
			Dataset dataset = RestUtils.parseJson(Dataset.class, FileUtils.readFileToString(file));
			verbose(dataset.getName());
			dataset.setDatasetId(null);
			dataset.setFile(null);
			getSessionDbClient().createDataset(sessionId, dataset);
			getFileBrokerClient().upload(sessionId, dataset.getDatasetId(), new File(files, file.getName()));
		}
		
		for (File file : getUUIDFiles(jobs)) {
			Job job = RestUtils.parseJson(Job.class, FileUtils.readFileToString(file));
			job.setJobId(null);
			getSessionDbClient().createJob(sessionId, job);
		}	
	}
	
	/**
	 * Create a unique filename in the given directory
	 * 
	 * Return the original name, if it doesn't exist yet. If it does, add an index between the basename and 
	 * file extension and increase it until the name is unique.
	 * 
	 * @param dir
	 * @param name
	 * @return
	 */
	private File getUniqueFile(File dir, String name) {
		String basename = FilenameUtils.getBaseName(name);
		// including the ".", if exists
		String extension = name.substring(basename.length());

		for (int i = 2; new File(dir, name).exists(); i++) {
			name = basename + "-" + i + extension;
		}
		return new File(dir, name);
	}
	
	/**
	 * List all the filenames in the directory and return all that can be parsed to a UUID 
	 * 
	 * @param dir
	 * @return
	 */
	private List<File> getUUIDFiles(File dir) {
		List<File> files = new ArrayList<>();
		for (File file : dir.listFiles()) {
			try {
				// try to parse
				UUID.fromString(file.getName());
				files.add(file);
			} catch (IllegalArgumentException e){
				System.err.println("skpping file " + file.getName());
			}
		}
		return files;
	}

	private RestFileBrokerClient getFileBrokerClient() {
		if (fileBrokerClient == null) {
			fileBrokerClient = new RestFileBrokerClient(proxy + "filebroker", credentials);
		}
		return fileBrokerClient;
	}

	private SessionDbClient getSessionDbClient() {
		if (sessionDbClient == null) {
			sessionDbClient = new SessionDbClient(proxy + "sessiondb/", proxy + "sessiondbevents/", credentials);
		}
		return sessionDbClient;
	}
	
	private ToolboxClientRest getToolboxClient() {
		if (toolboxClient == null) {
			toolboxClient = new ToolboxClientRest(proxy + "toolbox/");
		}
		return toolboxClient;
	}
	
	private void printFixed(String string, int length) {
		System.out.print(StringUtils.rightPad(string, length) + "  ");
	}
	
	private void printKeyValue(String key, Object value) {
		String margin = StringUtils.rightPad("", 22);
		String wrappedValue = WordUtils.wrap("" + value, 60).replace("\n", "\n" + margin);
		System.out.println(StringUtils.rightPad(key, 20) + "  " + wrappedValue);
	}
}
