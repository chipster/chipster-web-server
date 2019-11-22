package fi.csc.chipster.scheduler;

import fi.csc.chipster.sessiondb.model.WorkflowState;

public class WorkflowException extends Exception {

	private WorkflowState state;

	public WorkflowException(String msg, WorkflowState state) {
		super(msg);
		this.state = state;
	}

	public WorkflowState getState() {
		return state;
	}
}
