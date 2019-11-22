package fi.csc.chipster.sessiondb.model;

import java.util.Arrays;
import java.util.List;


public enum WorkflowState { 
	NEW, 
	RUNNING, 
	COMPLETED, 
	FAILED, // job failed
	ERROR, // something went horribly wrong, unexpected exceptions etc
	CANCELLED,
	DRAINING, 
	CANCELLING; // being cancelled at the moment
	
	static List<WorkflowState> finished = Arrays.asList(
			COMPLETED, 
			FAILED, 
			ERROR,
			CANCELLED
			);
	
	public boolean isFinished() {
		return finished.contains(this);
	}
}