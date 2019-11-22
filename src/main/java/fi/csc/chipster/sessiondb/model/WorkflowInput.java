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

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = super.hashCode();
		result = prime * result + ((sourceJobOutputId == null) ? 0 : sourceJobOutputId.hashCode());
		result = prime * result + ((sourceWorkflowJobId == null) ? 0 : sourceWorkflowJobId.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (!super.equals(obj))
			return false;
		if (getClass() != obj.getClass())
			return false;
		WorkflowInput other = (WorkflowInput) obj;
		if (sourceJobOutputId == null) {
			if (other.sourceJobOutputId != null)
				return false;
		} else if (!sourceJobOutputId.equals(other.sourceJobOutputId))
			return false;
		if (sourceWorkflowJobId == null) {
			if (other.sourceWorkflowJobId != null)
				return false;
		} else if (!sourceWorkflowJobId.equals(other.sourceWorkflowJobId))
			return false;
		return true;
	}

	
}
