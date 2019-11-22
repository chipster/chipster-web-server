package fi.csc.chipster.scheduler;

import fi.csc.chipster.sessiondb.model.WorkflowState;

public class BindException extends WorkflowException {

	public BindException(String msg) {
		super(msg, WorkflowState.FAILED);
	}
}
