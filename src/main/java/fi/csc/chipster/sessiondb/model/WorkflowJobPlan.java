package fi.csc.chipster.sessiondb.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.persistence.Column;

import org.hibernate.annotations.Type;

import fi.csc.chipster.rest.hibernate.DeepCopyable;

public class WorkflowJobPlan implements DeepCopyable {
	
	public static final String WORKFLOWJOBPLAN_LIST_JSON_TYPE = "WorkFlowJobPlanListJsonType";

	private UUID workflowJobPlanId;
	private String toolId;
	private String toolCategory;
	private String toolName;
	private String module;

	@Column
	@Type(type = Parameter.PARAMETER_LIST_JSON_TYPE)
	private List<Parameter> parameters = new ArrayList<>();

	@Column	
	@Type(type = Input.INPUT_LIST_JSON_TYPE)
	private List<Input> inputs = new ArrayList<>();

	@Column
	@Type(type = MetadataFile.METADATA_FILE_LIST_JSON_TYPE)
	private List<MetadataFile> metadataFiles = new ArrayList<>();

	public String getToolId() {
		return toolId;
	}

	public void setToolId(String toolId) {
		this.toolId = toolId;
	}

	public String getToolCategory() {
		return toolCategory;
	}

	public void setToolCategory(String toolCategory) {
		this.toolCategory = toolCategory;
	}

	public String getToolName() {
		return toolName;
	}

	public void setToolName(String toolName) {
		this.toolName = toolName;
	}

	public String getModule() {
		return module;
	}

	public void setModule(String module) {
		this.module = module;
	}

	public List<Parameter> getParameters() {
		return parameters;
	}

	public void setParameters(List<Parameter> parameters) {
		this.parameters = parameters;
	}

	public List<Input> getInputs() {
		return inputs;
	}

	public void setInputs(List<Input> inputs) {
		this.inputs = inputs;
	}

	public List<MetadataFile> getMetadataFiles() {
		return metadataFiles;
	}

	public void setMetadataFiles(List<MetadataFile> metadataFiles) {
		this.metadataFiles = metadataFiles;
	}
	
	public UUID getWorkflowJobPlanId() {
		return workflowJobPlanId;
	}

	public void setWorkflowJobPlanId(UUID workflowJobPlanId) {
		this.workflowJobPlanId = workflowJobPlanId;
	}
	
	@Override
	public Object deepCopy() {
		WorkflowJobPlan p = new WorkflowJobPlan();
		p.inputs = inputs.stream().map(i -> (Input)i.deepCopy()).collect(Collectors.toList());
		p.parameters = parameters.stream().map(param -> (Parameter)param.deepCopy()).collect(Collectors.toList());
		p.metadataFiles = metadataFiles.stream().map(m -> (MetadataFile)m.deepCopy()).collect(Collectors.toList());
		p.module = module;
		p.toolCategory = toolCategory;
		p.toolId = toolId;
		p.toolName = toolName;
		p.workflowJobPlanId = workflowJobPlanId;
		return p;
	}
}
