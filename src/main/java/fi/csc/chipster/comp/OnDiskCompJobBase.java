package fi.csc.chipster.comp;

import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jose.shaded.gson.Gson;
import com.nimbusds.jose.shaded.gson.GsonBuilder;

import fi.csc.chipster.comp.ToolDescription.InputDescription;
import fi.csc.chipster.comp.ToolDescription.OutputDescription;
import fi.csc.chipster.rest.RestUtils;

/**
 * Provides functionality for transferring input files from file broker to job
 * work directory and output files from job work directory to file broker.
 *
 */
public abstract class OnDiskCompJobBase extends CompJob {

	
	class VersionJson {
	    @SuppressWarnings("unused")
		private final String application;
	    @SuppressWarnings("unused")
		private final String version;

	    public VersionJson(String application, String version) {
	        this.application = application;
	        this.version = version;
	    }

	    // Add getters and setters if needed
	}
	
	
	private static final Logger logger = LogManager.getLogger();

	private static final String JOB_DATA_DIR_NAME = "data";
	private static final String JOB_TOOLBOX_DIR_NAME = "toolbox";
	private static final String JOB_INFO_DIR_NAME = "info";
	private static final String JOB_VERSIONS_DIR_NAME = "versions";

	protected File jobDir;
	protected File jobDataDir;
	protected File jobToolboxDir;
	protected File jobInfoDir;
	protected File jobVersionsDir;

	
	
	
	
	@Override
	public void construct(GenericJobMessage inputMessage, ToolDescription toolDescription, ResultCallback resultHandler,
			int jobTimeout) {
		super.construct(inputMessage, toolDescription, resultHandler, jobTimeout);
		this.jobDir = new File(resultHandler.getWorkDir(), getId());
		this.jobDataDir = new File(this.jobDir, JOB_DATA_DIR_NAME);
		this.jobToolboxDir = new File(this.jobDir, JOB_TOOLBOX_DIR_NAME);
		this.jobInfoDir = new File(this.jobDir, JOB_INFO_DIR_NAME);
		this.jobVersionsDir = new File(this.jobInfoDir, JOB_VERSIONS_DIR_NAME);
	}

    /**
     * Copy input files from file broker to job work directory.
     * 
     * @throws JobCancelledException
     * 
     */
    @Override
    protected void preExecute() throws JobCancelledException {
        cancelCheck();
        super.preExecute();

        updateState(JobState.RUNNING, "transferring input data");

		// create directories for the job
		if (!this.jobDir.mkdir()) {
			this.setErrorMessage("Creating job working directory failed.");
			updateState(JobState.ERROR);
			return;
		}
		if (!this.jobInfoDir.mkdir()) {
			this.setErrorMessage("Creating job info directory failed.");
			updateState(JobState.ERROR);
			return;
		}
		if (!this.jobVersionsDir.mkdir()) {
			this.setErrorMessage("Creating job versions directory failed.");
			updateState(JobState.ERROR);
			return;
		}

		
		// get input files and toolbox
		try {
			// input files
			getInputFiles();

            // toolbox
            if (!this.jobToolboxDir.mkdir()) {
                throw new IOException("Creating job toolbox dir failed.");
            }
            resultHandler.getToolboxClient().getToolboxModules(this.jobToolboxDir);

        } catch (Exception e) {
            this.setErrorMessage("Transferring input data and tools to computing service failed.");
            this.setOutputText(Exceptions.getStackTrace(e));
            logger.error("transferring input data and tools failed", e);
            updateState(JobState.ERROR);
        }
    }

    /**
     * Copy output files from job work dir to file broker.
     * 
     * @throws JobCancelledException
     */
    @Override
    protected void postExecute() throws JobCancelledException {
        // update job state on the client side
        updateState(JobState.RUNNING, "transferring output data");
        cancelCheck();

        // get phenodata file // FIXME add support for multiple phenodata outputs
        File phenodataFile = null;
        for (OutputDescription outputDescription : toolDescription.getOutputFiles()) {
            if (outputDescription.isMeta()) {
                phenodataFile = new File(jobDataDir, outputDescription.getFileName().getID());
            }
        }

        // pass output files to result message
        List<OutputDescription> outputFiles = toolDescription.getOutputFiles();
        for (OutputDescription fileDescription : outputFiles) {
            cancelCheck();

            // single file description can also describe several files
            File[] describedFiles;

            if (fileDescription.getFileName().isSpliced()) {
                // it is a set of files
                String prefix = fileDescription.getFileName().getPrefix();
                String postfix = fileDescription.getFileName().getPostfix();
                String regex = prefix + ".*" + postfix;
                describedFiles = OnDiskCompJobBase.findFiles(jobDataDir, regex);

                // if output is required there should be at least one
                if (!fileDescription.isOptional() && describedFiles.length == 0) {
                    logger.error("required output file set not found");
                    this.setErrorMessage(
                            "Required output file set " + fileDescription.getFileName().getID() + " is missing.");
                    updateState(JobState.ERROR);
                    return;
                }
            } else {
                // it is a single file
                String outputName = fileDescription.getFileName().getID();
                describedFiles = new File[] { new File(jobDataDir, outputName) };
            }

			// parse a file containing file names for the client
			String outputsFilename = "chipster-outputs.tsv";
			LinkedHashMap<String, String> nameMap;
			try {
				nameMap = ToolUtils.parseOutputDescription(new File(jobDataDir, outputsFilename));
			} catch (IOException | CompException e) {
				logger.warn("couldn't parse " + outputsFilename);
				this.setErrorMessage("could not parse " + outputsFilename);
				this.setOutputText(Exceptions.getStackTrace(e));
				updateState(JobState.ERROR);
				return;
			}

			// add all described files to the result message
			for (File outputFile : describedFiles) {
				// copy file to file broker
				try {
					File phenodataFileForThisOutput = 
							outputFile.getName().endsWith(".tsv") ||
							outputFile.getName().endsWith(".shared") ||
							outputFile.getName().endsWith(".Rda") ? phenodataFile : null;
					
					String nameInClient = nameMap.get(outputFile.getName());
					String nameInSessionDb = nameInClient != null ? nameInClient : outputFile.getName();
					String dataId = resultHandler.getFileBrokerClient().addFile(
							UUID.fromString(inputMessage.getJobId()), inputMessage.getSessionId(), outputFile, nameInSessionDb, fileDescription.isMeta(), phenodataFileForThisOutput);
					// put dataId to result message. Preserve the order of outputs in tool
					this.addOutputDataset(outputFile.getName(), dataId, nameInClient, fileDescription.getFileName().getDisplayName());
					logger.debug("transferred output file: " + fileDescription.getFileName());

				} catch (FileNotFoundException e) {
					// required output file not found
					if (!fileDescription.isOptional()) {
						logger.error("required output file not found", e);
						this.setErrorMessage("Required output file is missing.");
						this.appendOutputText(Exceptions.getStackTrace(e));
						updateState(JobState.ERROR);
						return;
					}

				} catch (NotEnoughDiskSpaceException nedse) {
					logger.warn("not enough disk space for result file in filebroker");
					this.setErrorMessage(
							"There was not enough disk space for the result file in the Chipster server. Please try again later.");
					updateState(JobState.FAILED_USER_ERROR, "not enough disk space for results");
					return;
				}

				catch (Exception e) {
					// TODO continue or return? also note the super.postExecute()
					logger.error("could not put file to file broker", e);
					this.setErrorMessage("Could not send output file.");
					this.setOutputText(Exceptions.getStackTrace(e));
					updateState(JobState.ERROR);
					return;
				}
			}
			
		}

		
		// add versions data to result message
		String versionsJson; 
		try { 			
			versionsJson = this.getVersionsJson(); 
		} catch (Exception e) {
			String m = "failed to read versions file ";
			logger.error(m);
			this.setErrorMessage(m);
			this.setOutputText(Exceptions.getStackTrace(e));
			updateState(JobState.ERROR);
			return;
		}
		
		if (versionsJson != null && versionsJson.length() > 0) {
			this.addVersions(versionsJson);
		}

		super.postExecute();
	}

	
	protected String getVersionsJson() {

		FileFilter fileFilter = file -> file.isFile() && file.getName().endsWith(".txt");

		File[] files = this.jobVersionsDir.listFiles(fileFilter);

		List<VersionJson> jsonList = new ArrayList<>();

		if (files != null) {
			jsonList = Stream.of(files).sorted(Comparator.comparingLong(File::lastModified)).map(file -> {
					String fileContent;
					try {
						fileContent = readFileContent(file);
					} catch (IOException e) {
						String m = "failed to read version file " + file.getName();
						logger.warn(m);
						throw new RuntimeException(e);
					}
					return new VersionJson(file.getName().replaceAll("\\.txt$", ""), fileContent);
			}).filter(Objects::nonNull).collect(Collectors.toList());
		}

		if (jsonList.size() < 1) {
			return null;
		} else {
			Gson gson = new GsonBuilder().create();
			return gson.toJson(jsonList);
		}
	}
	
	/**
	 * Clear job working directory.
	 * 
	 */
	@Override
	protected void cleanUp() {
		try {
			// sweep job working directory
			if (resultHandler.shouldSweepWorkDir()) {
//				OnDiskCompJobBase.delTree(jobDir);
			}
		} catch (Exception e) {
			logger.error("Error when cleaning up job work dir.", e);
		} finally {
			super.cleanUp();
		}
	}

    private void getInputFiles()
            throws Exception, JobCancelledException, IOException, FileBrokerException {
        LinkedHashMap<String, String> nameMap = new LinkedHashMap<>();

        if (!this.jobDataDir.mkdir()) {
            throw new IOException("Creating job data dir failed.");
        }
        
        Set<String> boundInputs = new HashSet<>();
        
        /* Check inputs
         * 
         * Bind all inputs in the job message to inputs in the tool
         * and check that all non-optional inputs are found. 
         * 
         * The same check is done already in the app too, but checking it here makes the error
         * messages clearer, in case we get invalid jobs from replay-test, CLI client or Rest API.
         */
        for (InputDescription input : toolDescription.getInputFiles()) {

            if (input.getFileName().isSpliced()) {
                // it is a set of files
                String prefix = input.getFileName().getPrefix();
                String postfix = input.getFileName().getPostfix();

                // if input is required there should be at least one
                
                boolean found = false;

                for (String messageInput : inputMessage.getKeys()) {              
                    if (messageInput.startsWith(prefix) && messageInput.endsWith(postfix)) {
                        // found
                        boundInputs.add(messageInput);
                        found = true;
                    }
                }

                if (!found && !input.isOptional()) {
                    logger.error("required input file set not found");
                    this.setErrorMessage(
                        "The tool has required input set " + input.getFileName().getPrefix() + "{...}"
                        + input.getFileName().getPostfix() + " but the job didn't have any input files for it: "
                        + RestUtils.asJson(inputMessage.getKeys().toArray()));
                                                        
                    updateState(JobState.ERROR);
                    return;
                }

            } else {
                // it is a single file
                String inputName = input.getFileName().getID();

                if (inputMessage.getKeys().contains(inputName)) {
                    boundInputs.add(inputName);

                } else if (!input.isOptional()){
                    logger.error("required input file not found");
                    this.setErrorMessage(
                        "The tool has required input " + input.getFileName().getID()
                        + " but the job didn't have any input files for it: "
                        + RestUtils.asJson(inputMessage.getKeys().toArray()));
                    updateState(JobState.ERROR);
                    return; 
                }                
            }
        }
        
        // check that there was an input in the tool for all inputs in the message
        for (String messageInput : inputMessage.getKeys()) {
            if (!boundInputs.contains(messageInput)) {
                
                logger.error("job input " + messageInput + " was not found from the tool");
                this.setErrorMessage(
                        "Job input " + messageInput + " was not found from the tool.");
                updateState(JobState.ERROR);
                return; 
            }
        }

        for (String fileName : inputMessage.getKeys()) {

            cancelCheck();

            // get url and output file
            String dataId = inputMessage.getId(fileName);
            File localFile = new File(jobDataDir, fileName);

            // make local file available, by downloading, copying or symlinking
            resultHandler.getFileBrokerClient().getFile(inputMessage.getSessionId(), dataId,
                    new File(jobDataDir, fileName));
            logger.debug("made available local file: " + localFile.getName() + " " + localFile.length());

            nameMap.put(fileName, inputMessage.getName(fileName));
        }

        ToolUtils.writeInputDescription(new File(jobDataDir, "chipster-inputs.tsv"), nameMap);

        inputMessage.preExecute(jobDataDir);
    }

    /**
     * Deletes a file or a directory recursively. Deletes directory links, does not go 
     * into them recursively.
     * 
     * @param dir directory or file to be deleted
     * @return true if deleting was successful, false file does not exist or deleting it failed
     * @throws IOException 
     */
    public static boolean delTree(File dir) throws IOException {

        // Just try to delete the file first
        // Will work for normal files, empty dirs and links (dir or file)
        // Avoids need for dealing with links later on
        if (dir.delete()) {
            return true;
        } 

        // Directory
        else if (dir.isDirectory()) {
            for (File file : dir.listFiles()) {
                delTree(file);
            }

            // Dir should be empty now
            return dir.delete();
        } 

        // Could not delete, not a directory, no can do
        else {
            return false;
        }
    }

    /**
     * Find files in a given directory whose filenames match given regex.
     * 
     * @param dir
     * @param regex
     * @return File[]
     */    
    public static File[] findFiles(File dir, String regex) {

        class RegexFileFilter implements FilenameFilter {
            private final String regex;

            public RegexFileFilter(String regex) {
                this.regex = regex;
            }

            @Override
            public boolean accept(File dir, String name) {
                return name.matches(regex);
            }
	        
	    }
	    
	    return dir.listFiles(new RegexFileFilter(regex));
	}
	
	public File getJobDataDir() {
		return this.jobDataDir;
	}

    private static String readFileContent(File file) throws IOException {
        byte[] encodedBytes = Files.readAllBytes(file.toPath());
        return new String(encodedBytes, StandardCharsets.UTF_8);
    }

}
