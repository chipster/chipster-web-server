package fi.csc.chipster.comp;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import fi.csc.chipster.scheduler.JobCommand;
import fi.csc.chipster.sessiondb.model.Input;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.MetadataEntry;
import fi.csc.chipster.sessiondb.model.Parameter;
import fi.csc.chipster.toolbox.ToolboxTool;
import fi.csc.microarray.comp.ToolDescription;
import fi.csc.microarray.comp.ToolDescription.ParameterDescription;
import fi.csc.microarray.description.SADLDescription;
import fi.csc.microarray.description.SADLDescription.Output;
import fi.csc.microarray.messaging.message.GenericJobMessage;
import fi.csc.microarray.messaging.message.GenericResultMessage;
import fi.csc.microarray.messaging.message.JobMessage.ParameterSecurityPolicy;
import fi.csc.microarray.messaging.message.JobMessage.ParameterValidityException;
import fi.csc.microarray.messaging.message.JobMessageUtils;

public class RestJobMessage implements GenericJobMessage {
	
	private JobCommand jobCommand;
	private Job job;
	private HashMap<String, List<MetadataEntry>> metadata;
	private ToolboxTool tool;

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
		HashSet<String> keys = new HashSet<>();
		for (Input input : job.getInputs()) {
			// phenodata is handled separately in preExecute()
			if (!RestPhenodataUtils.FILE_PHENODATA.equals(input.getInputId()) && 
					!RestPhenodataUtils.FILE_PHENODATA2.equals(input.getInputId())) {
				keys.add(input.getInputId());
			}
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
				return input.getDisplayName();
			}
		}
		return null;
	}

	/**
	 * Gets parameters in the order they are defined in the ToolDescription.
	 * Parameters are given by the user and hence  
	 * safety policy is required to get access to them.
	 * 
	 * @param securityPolicy security policy to check parameters against, cannot be null
	 * @param description description of the tool, cannot be null
	 * 
	 * @throws ParameterValidityException if some parameter value fails check by security policy 
	 */
	public List<String> getParameters(ParameterSecurityPolicy securityPolicy, ToolDescription description) throws ParameterValidityException {
		
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

	public void setMetadata(HashMap<String, List<MetadataEntry>> metadataMap, ToolboxTool toolboxTool) {
		this.metadata = metadataMap;
		this.tool = toolboxTool;
	}
	
	@Override
	public void preExecute(File jobWorkDir) {
		
		/* This should really be in the OnDiskCompJobBase, but
		 * at the moment it's still in the old code base and can't use the 
		 * Dataset classes from the new repository.
		 */

		boolean phenodata = false;
		boolean phenodata2 = false;
		
		// Check inputs from the tool description, because it's not in the job inputs. 
		// Job inputs contain only bound datasets and phenodata isn't anymore a dataset in the client
		for (SADLDescription.Input input : tool.getSadlDescription().getInputs()) {
			if (RestPhenodataUtils.FILE_PHENODATA.equals(input.getName().getID())) {
				phenodata = true;
			}
			if (RestPhenodataUtils.FILE_PHENODATA2.equals(input.getName().getID())) {
				phenodata2 = true;
			}
		}
		
		try {
			RestPhenodataUtils.writePhenodata(jobWorkDir, metadata, phenodata, phenodata2);
		} catch (IOException e) {
			throw new IllegalStateException("failed to write the phenodata", e);
		}
	}

	public boolean hasPhenodataOutput() {
		for (Output output : tool.getSadlDescription().getOutputs()) {
			if (RestPhenodataUtils.FILE_PHENODATA.equals(output.getName().getID()) || 
					RestPhenodataUtils.FILE_PHENODATA2.equals(output.getName().getID())) {
				return true;
			}
		}
		return false;
	}
}
