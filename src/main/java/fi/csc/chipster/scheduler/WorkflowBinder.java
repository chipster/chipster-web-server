package fi.csc.chipster.scheduler;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.WorkflowInput;
import fi.csc.chipster.sessiondb.model.WorkflowJob;
import fi.csc.microarray.messaging.JobState;

public class WorkflowBinder {
	
	private static Logger logger = LogManager.getLogger();
	
	public static boolean updateInputBindings(List<WorkflowJob> workflowJobs, HashMap<UUID, Dataset> sessionDatasets, HashMap<UUID, Job> sessionJobs) throws RestException, BindException {
		
		boolean isChanged = false;
		
		for (WorkflowJob workflowJob : workflowJobs) {
			
			logger.info("job " + workflowJob.getToolId() + " has " + workflowJob.getInputs().size() + " input(s)");
			
			for (WorkflowInput input : workflowJob.getInputs()) {
				
				logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': check binding");
				
				if (updateBinding(input, workflowJobs, workflowJob, sessionDatasets, sessionJobs)) {
					isChanged = true;
				}
			}
		}
		
		return isChanged;
	}
		
	public static boolean updateBinding(WorkflowInput input, List<WorkflowJob> workflowJobs, WorkflowJob workflowJob, HashMap<UUID, Dataset> sessionDatasets,
			HashMap<UUID, Job> sessionJobs) throws BindException {
				
		Dataset sourceDataset = getSourceDataset(input, sessionDatasets);
		WorkflowJob sourceWorkflowJob = getSourceWorkflowJob(input, workflowJobs);
		Job sourceJob = getSourceJob(input, sessionJobs, workflowJobs);
		
		if (sourceDataset != null) {
			
			// input is already bound to dataset
			logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "':  bound to dataset " + input.getDatasetId());
		
		} else if (sourceWorkflowJob != null && sourceJob == null) {
			logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': waiting for workflow job " + input.getSourceWorkflowJobId() + " to start");
			
		} else if (sourceWorkflowJob != null && sourceJob != null) { 	
																
			logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': is bound to job " + sourceWorkflowJob.getJobId() + " output '" + input.getSourceJobOutputId() + "'");
						
			if (sourceJob.getState() == JobState.COMPLETED) {
				
				Dataset dataset = getSourceDataset(input, sourceJob, sessionDatasets);
				
				logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': bind dataset " + dataset.getName());
				input.setDatasetId(dataset.getDatasetId().toString());
				input.setDisplayName(dataset.getName());
				
				return true;
				
			} else {
				logger.info("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': waiting for job " + sourceJob.getToolId() + " (" + sourceJob.getState() + ")");
			}
		} else {
			throw new BindException("job " + workflowJob.getToolId() + " input '" + input.getInputId() + "': is not bound to anything");			
		}
		
		return false;
	}
	

	public static Dataset getSourceDataset(WorkflowInput input, Job sourceJob, HashMap<UUID, Dataset> sessionDatasets) throws BindException {
		
		List<Dataset> sourceDatasets = sessionDatasets.values().stream()
				.filter(d -> sourceJob.getJobId().equals(d.getSourceJob()))
				.filter(d -> input.getSourceJobOutputId().equals(d.getSourceJobOutputId()))
				.collect(Collectors.toList());
		
		if (sourceDatasets.isEmpty()) {
			throw new BindException("the job " + sourceJob.getToolId() + " has completed, but its output '" + input.getSourceJobOutputId() + "' dataset cannot be found");
		} else if (sourceDatasets.size() > 1) {
			throw new BindException("the job " + sourceJob.getToolId() + " has more than one datasets for output '" + input.getSourceJobOutputId() + "'");
		} else {
			// the job output is ready, we can bind the dataset
			return sourceDatasets.get(0);
		}
	}
	
	public static Job getSourceJob(WorkflowInput input, HashMap<UUID, Job> sessionJobs, List<WorkflowJob> workflowJobs) throws BindException {
		WorkflowJob sourceWorkflowJob = getSourceWorkflowJob(input, workflowJobs);
		
		if (sourceWorkflowJob != null && sourceWorkflowJob.getJobId() != null) {
			
			Job sourceJob = sessionJobs.get(sourceWorkflowJob.getJobId());
			
			if (sourceJob == null) {
				throw new BindException("job " + sourceWorkflowJob.getJobId() + " is not found from the session");
			}
			return sourceJob;
		}
		return null;
	}

	public static Dataset getSourceDataset(WorkflowInput input, HashMap<UUID, Dataset> sessionDatasets) throws BindException {
		
		if (input.getDatasetId() != null) {
			Dataset dataset = sessionDatasets.get(UUID.fromString(input.getDatasetId()));
			
			if (dataset == null) {
				throw new BindException(" input '" + input.getInputId() + "' is bound to non existing dataset " + input.getDatasetId());
			} else {
				return dataset;
			}
		}
		return null;
	}
	
	public static WorkflowJob getSourceWorkflowJob(WorkflowInput input, List<WorkflowJob> workflowJobs) throws BindException {
		
		if (input.getSourceWorkflowJobId() != null) {
			Optional<WorkflowJob> sourceWorkflowJobOptional = workflowJobs.stream()
					.filter(j -> UUID.fromString(input.getSourceWorkflowJobId()).equals(j.getWorkflowJobId()))
					.findFirst();
		
			if (sourceWorkflowJobOptional.isEmpty()) {
				throw new BindException("input '" + input.getInputId() + "': waiting workflow job " + input.getSourceWorkflowJobId() + " which doesn't exist");
			}
			
			return sourceWorkflowJobOptional.get();
		}
		return null;
	}
}
