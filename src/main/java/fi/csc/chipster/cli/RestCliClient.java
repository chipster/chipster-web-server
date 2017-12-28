package fi.csc.chipster.cli;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.MediaType;

import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.WordUtils;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.rest.CredentialsProvider;
import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.servicelocator.resource.Service;
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
	
	private static final String ARG_DIR = "DIR";
	private static final String ARG_FILE = "FILE";
	private static final String ARG_FILTER = "FILTER";
	private static final String ARG_VALUE = "VALUE";
	private static final String ARG_DETAIL = "DETAIL";
	private static final String ARG_SESSION = "SESSION";
	private static final String ARG_DATASET = "DATASET";
	private static final String ARG_TOOL = "TOOL";
	private static final String ARG_HOST = "HOST";
	
	private static final String OPT_QUIET = "quiet";
	private static final String OPT_VERBOSE = "verbose";
	private static final String OPT_PASSWORD = "password";
	private static final String OPT_USERNAME = "username";
	
	private static final String CMD_RUN = "run";
	private static final String CMD_IMPORT = "import";
	private static final String CMD_EXPORT = "export";
	private static final String CMD_PARAMETER = "parameter";
	private static final String CMD_NOTES = "notes";
	private static final String CMD_NAME = "name";
	private static final String CMD_DOWNLOAD = "download";
	private static final String CMD_DETAILS = "details";
	private static final String CMD_PRINT = "print";
	private static final String CMD_SET = "set";
	private static final String CMD_UPLOAD = "upload";
	private static final String CMD_DELETE = "delete";
	private static final String CMD_LIST = "list";
	private static final String CMD_CREATE = "create";
	private static final String CMD_DATASET = "dataset";
	private static final String CMD_JOB = "job";
	private static final String CMD_TOOL = "tool";
	// this is used as command and option
	private static final String SESSION = "session";
	
	private static final String SUBSUBCOMMAND = "subsubcommand";
	private static final String SUBCOMMAND = "subcommand";
	private static Long t;
	
	private CredentialsProvider credentials;
	private Boolean verbose = false;
	private Boolean quiet = false;
	private SessionDbClient sessionDbClient;
	private ToolboxClientRest toolboxClient;
	private RestFileBrokerClient fileBrokerClient;
	private HashMap<String, Service> services;

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
	    parser.addArgument(ARG_HOST).help("hostname or IP address of the Chipster server and [:PORT]");
	    parser.addArgument("-u", "--" + OPT_USERNAME).action(Arguments.store()).help("username");
	    parser.addArgument("-p", "--" + OPT_PASSWORD).action(Arguments.store()).help("password");
	    parser.addArgument("-s", "--" + SESSION).action(Arguments.store()).help("session name or ID to work on");
	    
	    parser.addArgument("-v", "--" + OPT_VERBOSE).action(Arguments.storeTrue()).help("more verbose output");
	    parser.addArgument("-q", "--" + OPT_QUIET).action(Arguments.storeTrue()).help("quieter output");
	    
	    Subparsers subparsers = parser.addSubparsers().title("subcommands").metavar("COMMAND").dest(SUBCOMMAND);
	    Subparsers sessionSubparsers = subparsers.addParser(SESSION).help("manage sessions").addSubparsers().title("session subcommands").metavar("COMMAND").dest(SUBSUBCOMMAND);
	    Subparsers toolSubparsers    = subparsers.addParser(CMD_TOOL).help("get information about tools").addSubparsers().title("tool subcommands").metavar("COMMAND").dest(SUBSUBCOMMAND);
	    Subparsers jobSubparsers     = subparsers.addParser(CMD_JOB).help("run and follow jobs").addSubparsers().title("job subcommands").metavar("COMMAND").dest(SUBSUBCOMMAND);
	    Subparsers datasetSubparsers = subparsers.addParser(CMD_DATASET).help("manage datasets").addSubparsers().title("dataset subcommands").metavar("COMMAND").dest(SUBSUBCOMMAND);

	    // sessions
	    
	    Subparser sessionCreateParser = sessionSubparsers.addParser(CMD_CREATE).help("create new session");
	    sessionCreateParser.addArgument(ARG_SESSION).help("name of the session to be created");
	    
	    sessionSubparsers.addParser(CMD_LIST).help("list sessions");
	    sessionSubparsers.addParser(CMD_DETAILS).help("view session details");
	    
	    sessionSubparsers.addParser(CMD_DELETE).help("delete session");
	    
	    Subparser sessionExportParser = sessionSubparsers.addParser(CMD_EXPORT).help("copy the whole session to a local directory");
	    sessionExportParser.addArgument(ARG_DIR);

	    Subparser sessionImportParser = sessionSubparsers.addParser(CMD_IMPORT).help("import a session from the local directory");
	    sessionImportParser.addArgument(ARG_DIR);
	    sessionImportParser.addArgument(CMD_NAME).nargs("?").help("rename the imported session");
	    	    
	    // datasets
	    
	    datasetSubparsers.addParser(CMD_LIST).help("list all datasets in the session");
	    Subparser datasetUploadParser = datasetSubparsers.addParser(CMD_UPLOAD).help("upload a dataset");
	    datasetUploadParser.addArgument(ARG_FILE).help("file to upload or - to read from the standard in");
	    datasetUploadParser.addArgument(CMD_NAME).nargs("?").help("name for the uploaded dataset");
	    
	    Subparser datasetDownlaodParser = datasetSubparsers.addParser(CMD_DOWNLOAD).help("download a dataset");
	    datasetDownlaodParser.addArgument(ARG_DATASET).help("dataset to download");
	    datasetDownlaodParser.addArgument(ARG_FILE).nargs("?").help("destination file (dataset name by default)");
	    
	    Subparser datasetSetParser = datasetSubparsers.addParser(CMD_SET).help("edit dataset details");
	    datasetSetParser.addArgument(ARG_DATASET);
	    datasetSetParser.addArgument(ARG_DETAIL).choices(CMD_NAME, CMD_NOTES);
	    datasetSetParser.addArgument(ARG_VALUE);
	    
	    Subparser datasetPrintParser = datasetSubparsers.addParser(CMD_PRINT).help("print the dataset contents to the standard output");
	    datasetPrintParser.addArgument(ARG_DATASET);
	    
	    Subparser datasetDetailsParser = datasetSubparsers.addParser(CMD_DETAILS).help("view dataset details");
	    datasetDetailsParser.addArgument(ARG_DATASET);
	    
	    datasetSubparsers.addParser(CMD_DELETE).help("delete dataset");
	    
	    // tools
	    
	    Subparser toolListParser = toolSubparsers.addParser(CMD_LIST).help("list tools");
	    toolListParser.addArgument(ARG_FILTER).nargs("?").help("list only tools that have this string in its name");
	    
	    Subparser toolDetailsParser = toolSubparsers.addParser(CMD_DETAILS).help("view tool details");
	    toolDetailsParser.addArgument(ARG_TOOL);
	    
	    // jobs
	    
	    jobSubparsers.addParser(CMD_LIST).help("list jobs");
	    Subparser jobDetailsParser = jobSubparsers.addParser(CMD_DETAILS).help("view job details");
	    jobDetailsParser.addArgument(CMD_JOB).help("view job details");
	    
	    Subparser jobRunParser = jobSubparsers.addParser(CMD_RUN).help("start new job");
	    jobRunParser.addArgument(ARG_TOOL).help("tool to run");
	    jobRunParser.addArgument("-D", "--" + CMD_DATASET).nargs("*").action(Arguments.append()).help("input datasets");
	    jobRunParser.addArgument("-P", "--" + CMD_PARAMETER).nargs(2).metavar("PARAMETER", ARG_VALUE).action(Arguments.append()).help("parameter name and value");

	    return parser;
	}
	
	private void execute(Namespace namespace) throws RestException, IOException {
		
		verbose = namespace.getBoolean(OPT_VERBOSE);
		quiet = namespace.getBoolean(OPT_QUIET);
		
		String webUri = "http://" + namespace.getString(ARG_HOST);
		String serviceLocatorUri = getServiceLocatorUri(webUri);
		this.services = getServices(serviceLocatorUri);
		
		String username = namespace.getString(OPT_USERNAME);
		String password = namespace.getString(OPT_PASSWORD);
		
		if (username != null || password != null) {
			String authURI = getAuthUri();
			verbose("authenticating to " + authURI);
			credentials = new AuthenticationClient(authURI, username, password).getCredentials();
			time("authenticate");
		}
			
		String subcommand = namespace.getString(SUBCOMMAND);
		
		switch (subcommand) {
		case SESSION:
			executeSessionSubcommand(namespace);
			break;
		case CMD_TOOL:
			executeToolSubcommand(namespace);
			break;
		case CMD_JOB:
			executeJobSubcommand(namespace);
			break;
		case CMD_DATASET:
			executeDatasetSubcommand(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}
	
	private HashMap<String, Service> getServices(String serviceLocatorUri) {
		WebTarget serviceTarget = ClientBuilder.newClient().target(serviceLocatorUri).path("services");
		String serviceJson = serviceTarget.request(MediaType.APPLICATION_JSON).get(String.class);
		@SuppressWarnings("unchecked")
		List<Service> serviceList = RestUtils.parseJson(List.class, Service.class, serviceJson);
		
		HashMap<String, Service> services = new HashMap<>();
		for (Service service : serviceList) {
			services.put(service.getRole(), service);
		}
		return services;
	}

	private String getServiceLocatorUri(String webUri) {
		WebTarget configTarget = ClientBuilder.newClient().target(webUri).path("js/json/config.json");
		String configJson = configTarget.request(MediaType.APPLICATION_JSON).get(String.class);
		@SuppressWarnings("unchecked")
		HashMap<String, String> configMap = RestUtils.parseJson(HashMap.class, configJson);
		
		return configMap.get("serviceLocator");
	}

	private String getAuthUri() {
		return services.get("authentication-service").getPublicUri();
	}
	
	private String getSessionDbUri() {
		return services.get("session-db").getPublicUri();
	}
	
	private String getSessionDbEventsUri() {
		return services.get("session-db-events").getPublicUri();
	}
	
	private String getToolboxUri() {
		return services.get("toolbox").getPublicUri();
	}
	
	private String getFileBrokerUri() {
		return services.get("file-broker").getPublicUri();
	}

	private void verbose(String msg) {
		if (verbose) {
			System.out.println(msg);
		}
	}

	private void executeDatasetSubcommand(Namespace namespace) throws RestException, IOException {
		String subcommand = namespace.getString(SUBSUBCOMMAND);
		switch (subcommand) {
		case CMD_LIST:
			datasetList(namespace);
			break;
		case CMD_UPLOAD:
			datasetUpload(namespace);
			break;
		case CMD_SET:
			datasetSet(namespace);
			break;
		case CMD_DETAILS:
			datasetDetails(namespace);
			break;
		case CMD_PRINT:
			datasetPrint(namespace);
			break;
		case CMD_DELETE:
			datasetDelete(namespace);
			break;
		case CMD_DOWNLOAD:
			datasetDownload(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}


	private void executeJobSubcommand(Namespace namespace) throws RestException {
		String subcommand = namespace.getString(SUBSUBCOMMAND);
		switch (subcommand) {
		case CMD_LIST:
			jobList(namespace);
			break;
		case CMD_DETAILS:
			jobDetais(namespace);
			break;
		case CMD_RUN:
			jobRun(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}

	
	private void executeToolSubcommand(Namespace namespace) {
		String subcommand = namespace.getString(SUBSUBCOMMAND);
		switch (subcommand) {
		case CMD_LIST:
			toolList(namespace);
			break;
		case CMD_DETAILS:
			toolDetails(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}
	
	private void executeSessionSubcommand(Namespace namespace) throws RestException, IOException {
		String subcommand = namespace.getString(SUBSUBCOMMAND);
		switch (subcommand) {
		case CMD_CREATE:
			sessionCreate(namespace);
			break;
		case CMD_LIST:
			sessionList(namespace);
			break;
		case CMD_DETAILS:
			sessionDetails(namespace);
			break;
		case CMD_DELETE:
			sessionDelete(namespace);
			break;
		case CMD_EXPORT:
			sessionExport(namespace);
			break;
		case CMD_IMPORT:
			sessionImport(namespace);
			break;
		default:
			throw new IllegalArgumentException("unknown subcommand: " + subcommand);
		}
	}

	private void datasetDownload(Namespace namespace) throws RestException, IOException {
		Dataset dataset = getDataset(namespace);
		String filePath = namespace.getString(ARG_FILE);
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
		printKeyValue(CMD_NAME, dataset.getName());
		printKeyValue(CMD_NOTES, dataset.getNotes());
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
		String value = namespace.getString(ARG_VALUE);
		switch (namespace.getString(ARG_DETAIL)) {
		case CMD_NAME:
			dataset.setName(value);
			break;
		case CMD_NOTES:
			dataset.setNotes(value);
			break;
		}
		getSessionDbClient().updateDataset(getSessionId(namespace), dataset);
	}

	private void datasetUpload(Namespace namespace) throws RestException, FileNotFoundException {
		String filePath = namespace.getString(ARG_FILE);
		String name = namespace.getString(CMD_NAME);
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
		ToolboxTool tool = getToolboxClient().getTool(namespace.getString(ARG_TOOL));
		List<Dataset> datasets = new ArrayList<>();
		HashMap<String, String> parameters = new HashMap<>();

		if (namespace.getList(CMD_DATASET) != null) {
			for (Object innerList : namespace.getList(CMD_DATASET)) {
				for (Object dataset : (List<Object>)innerList) {
					datasets.add(getDataset("" + dataset, namespace));
				}
			}
		}
		
		if (namespace.getList(CMD_PARAMETER) != null) {
			for (Object paramObj : namespace.getList(CMD_PARAMETER)) {
				List<String> paramList = (List<String>) paramObj;
				parameters.put(paramList.get(0), paramList.get(1));
			}
		}
		
		LinkedHashSet<Input> jobInputs = new LinkedHashSet<>();
		
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
		
		LinkedHashSet<Parameter> jobParameters = new LinkedHashSet<>();
		
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
		Job job = getJob(namespace.getString(CMD_JOB), namespace);
		
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
		ToolboxTool tool = getToolboxClient().getTool(namespace.getString(ARG_TOOL));
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
		String filter = namespace.getString(ARG_FILTER);
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
		printKeyValue(CMD_NAME, session.getName());
		printKeyValue("created", session.getCreated());
		printKeyValue("accessed", session.getAccessed());
		printKeyValue("session ID", session.getSessionId());
		printKeyValue(CMD_NOTES, session.getNotes());
	}

	private Dataset getDataset(Namespace namespace) throws RestException {
		
		String str = namespace.getString(ARG_DATASET);
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
		session.setName(namespace.getString(ARG_SESSION));
		getSessionDbClient().createSession(session);
	}
	
	private void sessionExport(Namespace namespace) throws RestException, IOException {
		File dir = new File(namespace.getString(ARG_DIR));
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
		
		RestUtils.writeStringToFile(new File(dir,  "session.json"), RestUtils.asJson(session));
		
		for (Dataset dataset : session.getDatasets().values()) {
			verbose(dataset.getName());
			RestUtils.writeStringToFile(new File(datasets,  dataset.getDatasetId().toString()), RestUtils.asJson(dataset));
			// I guess we can use datasetId for the file also, because there should be no need for file deduplication within a session
			getFileBrokerClient().download(getSessionId(namespace),dataset.getDatasetId(), new File(files, dataset.getDatasetId().toString()));
			
			Files.createSymbolicLink(getUniqueFile(datasetLinks, dataset.getName()).toPath(), new File("UUID", dataset.getDatasetId().toString()).toPath());
			Files.createSymbolicLink(getUniqueFile(fileLinks, dataset.getName()).toPath(), new File("UUID", dataset.getDatasetId().toString()).toPath());
		}
		
		for (Job job : session.getJobs().values()) {
			RestUtils.writeStringToFile(new File(jobs,  job.getJobId().toString()), RestUtils.asJson(job));
			Files.createSymbolicLink(getUniqueFile(jobLinks, job.getToolId()).toPath(), new File("UUID", job.getJobId().toString()).toPath());
		}	
	}

	private void sessionImport(Namespace namespace) throws RestException, IOException {
		String name = namespace.getString(CMD_NAME);
		File dir = new File(namespace.getString(ARG_DIR));
		File jobLinks = new File(dir, "jobs");
		File datasetLinks = new File(dir, "datasets");
		File fileLinks = new File(dir, "files");
		File jobs = new File(jobLinks, "UUID");
		File datasets = new File(datasetLinks, "UUID");
		File files = new File(fileLinks, "UUID");
		
		Session session = RestUtils.parseJson(Session.class, RestUtils.readFileToString(new File(dir,  "session.json")));
		if (name != null) {
			session.setName(name);
		}	
		
		session.setSessionId(null);
		UUID sessionId = getSessionDbClient().createSession(session);
		

		HashMap<UUID, UUID> datasetIds = new HashMap<>();
		HashMap<UUID, UUID> jobIds = new HashMap<>();
		
		// post datasets
		for (File file : getUUIDFiles(datasets)) {
			Dataset dataset = RestUtils.parseJson(Dataset.class, RestUtils.readFileToString(file));
			verbose(dataset.getName());
			UUID oldId = dataset.getDatasetId();
			dataset.setDatasetId(null);
			dataset.setFile(null);
			// this will update the datasetId
			UUID newId = getSessionDbClient().createDataset(sessionId, dataset);
			getFileBrokerClient().upload(sessionId, dataset.getDatasetId(), new File(files, file.getName()));
			datasetIds.put(oldId, newId);
		}
		
		// post jobs
		for (File file : getUUIDFiles(jobs)) {
			Job job = RestUtils.parseJson(Job.class, RestUtils.readFileToString(file));
			UUID oldId = job.getJobId();
			job.setJobId(null);
			// this will update the jobId
			UUID newId = getSessionDbClient().createJob(sessionId, job);
			jobIds.put(oldId, newId);
		}
		
		// set new job ids to datasets 
		for (UUID newDatasetId : datasetIds.values()) {
			// get the latest dataset from server, because file-broker has already updated it
			Dataset dataset = getSessionDbClient().getDataset(sessionId, newDatasetId);
			UUID newJobId = jobIds.get(dataset.getSourceJob());
			dataset.setSourceJob(newJobId);
			getSessionDbClient().updateDataset(sessionId, dataset);
		}
		
		// set new dataset ids to jobs
		for (UUID newJobId : jobIds.values()) {
			Job job = getSessionDbClient().getJob(sessionId, newJobId);
			for (Input input : job.getInputs()) {
				UUID oldDatasetId = UUID.fromString(input.getDatasetId());
				UUID newDatasetId = datasetIds.get(oldDatasetId);
				input.setDatasetId(newDatasetId.toString());
			}
			getSessionDbClient().updateJob(sessionId, job);
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
			fileBrokerClient = new RestFileBrokerClient(getFileBrokerUri(), credentials);
		}
		return fileBrokerClient;
	}

	private SessionDbClient getSessionDbClient() {
		if (sessionDbClient == null) {
			sessionDbClient = new SessionDbClient(getSessionDbUri(), getSessionDbEventsUri(), credentials);
		}
		return sessionDbClient;
	}
	
	private ToolboxClientRest getToolboxClient() {
		if (toolboxClient == null) {
			toolboxClient = new ToolboxClientRest(getToolboxUri());
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
