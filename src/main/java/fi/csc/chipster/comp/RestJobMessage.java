package fi.csc.chipster.comp;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.RestUtils;
import fi.csc.chipster.scheduler.offer.JobCommand;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.Parameter;

public class RestJobMessage implements GenericJobMessage {
    
    private final JobCommand jobCommand;
	private final Job job;
	
	@SuppressWarnings("unused")
    private static final Logger logger = LogManager.getLogger();

	public RestJobMessage(JobCommand jobCommand, Job job) {
		this.jobCommand = jobCommand;
		this.job = job;
	}

	@Override
	public String getToolId() {
		return job.getToolId();
	}

	@Override
	public String getJobId() {
		return job.getJobId().toString();
	}

	@Override
	public Set<String> getKeys() {
		// phenodata inputs are no longer job inputs, they are handled separately in
		// preExecute()
		HashSet<String> keys = new HashSet<>();
		for (Input input : job.getInputs()) {
			keys.add(input.getInputId());
		}
		return keys;
	}

	@Override
	public String getId(String inputName) {
		for (Input input : job.getInputs()) {
			if (input.getInputId().equals(inputName)) {
				return input.getDatasetId();
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
	 * @return parameters
	 * 
	 * @throws ParameterValidityException
	 *             if some parameter value fails check by security policy
	 */
	@Override
	public LinkedHashMap<String, Parameter> getParameters(ParameterSecurityPolicy securityPolicy, ToolDescription description)
			throws ParameterValidityException {

		// add job parameters to map to make them easier to find
		HashMap<String, Parameter> jobParameters = new HashMap<>();

		for (Parameter jobParameter : job.getParameters()) {
			jobParameters.put(jobParameter.getParameterId(), jobParameter);
		}

		// keep the order of the ToolDescription
		LinkedHashMap<String, Parameter> completedParameters = new LinkedHashMap<>();

		for (fi.csc.chipster.toolbox.sadl.SADLDescription.Parameter toolParameter : description.getParameters().values()) {
			Parameter jobParameter = jobParameters.get(toolParameter.getName().getID());
			Parameter completedParameter = new Parameter();
			completedParameter.setParameterId(toolParameter.getName().getID());

			if (jobParameter == null) {
				if (toolParameter.isOptional()) {
					logger.info("parameter '" + toolParameter.getName().getID() + "' is not set, using default value");
					completedParameter.setValue(toolParameter.getDefaultValue());
				} else {
					throw new ParameterValidityException("mandatory parameter '" + toolParameter.getName().getID() + "' is not set");
				}
			} else {
				completedParameter.setValue(jobParameter.getValue());
			}

			completedParameter.setDisplayName(toolParameter.getName().getDisplayName());
			completedParameter.setDescription(toolParameter.getDescription());
			completedParameter.setType(toolParameter.getType());

			completedParameters.put(completedParameter.getParameterId(), completedParameter);
		}

		// check that all job parameters were used
		for (Parameter param : job.getParameters()) {
			if (!completedParameters.containsKey(param.getParameterId())) {
				throw new ParameterValidityException("Parameter not found from tool. "
				+ "Job has parameter '" + param.getParameterId()
				+ "', but it's not found from tool: " + RestUtils.asJson(completedParameters.keySet()));
			}
		}

		// check safety
		return JobMessageUtils.checkParameterSafety(securityPolicy, description, completedParameters);
	}

	@Override
	public String getUsername() {
		return null;
	}

	public JobCommand getJobCommand() {
		return jobCommand;
	}

	@Override
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
