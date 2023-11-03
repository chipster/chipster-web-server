package fi.csc.chipster.comp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.comp.ToolDescription.ParameterDescription;
import fi.csc.chipster.scheduler.offer.JobCommand;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Parameter;

public class RestJobMessage implements GenericJobMessage {
    
    private JobCommand jobCommand;
	private Job job;
	
	@SuppressWarnings("unused")
    private static Logger logger = LogManager.getLogger();

	public RestJobMessage(JobCommand jobCommand, Job job) {
		this.jobCommand = jobCommand;
		this.job = job;
	}

	public String getToolId() {
		return job.getToolId();
	}

	public String getJobId() {
		return job.getJobId().toString();
	}

	public Set<String> getKeys() {
		// phenodata inputs are no longer job inputs, they are handled separately in
		// preExecute()
		HashSet<String> keys = new HashSet<>();
		for (Input input : job.getInputs()) {
			keys.add(input.getInputId());
		}
		return keys;
	}

	public String getId(String inputName) {
		for (Input input : job.getInputs()) {
			if (input.getInputId().equals(inputName)) {
				return input.getDatasetId().toString();
			}
		}
		return null;
	}

	@Override
	public String getName(String inputName) {
	        
        for (Input input : job.getInputs()) {
            if (input.getInputId().equals(inputName)) {
                return input.getDatasetName();
            }
        }
        
        return null;
	}

	/**
	 * Gets parameters in the order they are defined in the ToolDescription.
	 * Parameters are given by the user and hence safety policy is required to get
	 * access to them.
	 * 
	 * @param securityPolicy
	 *            security policy to check parameters against, cannot be null
	 * @param description
	 *            description of the tool, cannot be null
	 * 
	 * @throws ParameterValidityException
	 *             if some parameter value fails check by security policy
	 */
	public List<String> getParameters(ParameterSecurityPolicy securityPolicy, ToolDescription description)
			throws ParameterValidityException {

		// get parameter values in the order of the ToolDescription
		// consider refactoring so that we would simply return the map
		HashMap<String, String> parameterIdToValueMap = new HashMap<String, String>();
		List<String> parameterValues = new ArrayList<String>();

		for (Parameter param : job.getParameters()) {
			parameterIdToValueMap.put(param.getParameterId(), param.getValue());
		}

		for (ParameterDescription paramDescription : description.getParameters()) {
			if (parameterIdToValueMap.containsKey(paramDescription.getName())) {
				parameterValues.add(parameterIdToValueMap.get(paramDescription.getName()));
			} else {
				throw new IllegalArgumentException("didn't find a value for parameter " + paramDescription.getName());
			}
		}

		// check safety
		return JobMessageUtils.checkParameterSafety(securityPolicy, description, parameterValues);
	}

	@Override
	public String getUsername() {
		return null;
	}

	public JobCommand getJobCommand() {
		return jobCommand;
	}

	public UUID getSessionId() {
		return jobCommand.getSessionId();
	}

	@Override
	public void preExecute(File jobWorkDir) {
		// FIXME add phenodata content validation
		job.getMetadataFiles().forEach(phenodata -> {
			try {
				if (validatePhenodataFilename(phenodata.getName())) {
					Files.write(new File(jobWorkDir, phenodata.getName()).toPath(), phenodata.getContent().getBytes());
				} else {
					throw new RuntimeException("Illegal phenodata file name: " + phenodata.getName());
				}
			} catch (IOException e) {
				throw new IllegalStateException("failed to write the phenodata", e);
			}
		});
	}

	private boolean validatePhenodataFilename(String name) {
		return name != null && !name.isEmpty() && name.matches("^[\\w\\-_\\.]*$") && !name.matches("\\.\\.");

	}
}
