package fi.csc.chipster.sessiondb.model;

import fi.csc.chipster.rest.hibernate.DeepCopyable;

public class WorkflowInput extends Input implements DeepCopyable {
	
	public static final String WORKFLOW_INPUT_LIST_JSON_TYPE = "WorkflowInputListJsonType";

	private String sourceWorkflowJobId;
	private String sourceJobOutputId;
			
	@Override
	public Object deepCopy() {
		WorkflowInput i = super.deepCopy(new WorkflowInput());
		i.sourceJobOutputId = sourceJobOutputId;
		i.sourceWorkflowJobId = sourceWorkflowJobId;
		return i;
	}

	public String getSourceJobOutputId() {
		return sourceJobOutputId;
	}

	public void setSourceJobOutputId(String sourceJobPlanOutputId) {
		this.sourceJobOutputId = sourceJobPlanOutputId;
	}

	public String getSourceWorkflowJobId() {
		return sourceWorkflowJobId;
	}

	public void setSourceWorkflowJobId(String sourceWorkflowJobId) {
		this.sourceWorkflowJobId = sourceWorkflowJobId;
	}
}
