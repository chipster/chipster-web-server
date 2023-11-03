package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import jakarta.xml.bind.annotation.XmlRootElement;

import org.hibernate.annotations.Type;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import fi.csc.chipster.comp.JobState;

@Entity // db
@XmlRootElement // rest
@Table(indexes = { @Index(columnList = "sessionId", name = "job_sessionid_index"), })
public class Job {

	@EmbeddedId // db
	@JsonUnwrapped
	private JobIdPair jobIdPair;

	private String toolId;
	private JobState state;
	private String toolCategory;
	private String toolName;
	@Lob
	private String toolDescription;
	private Instant created;
	private Instant startTime;
	private Instant endTime;
	private String module;
	@Lob
	private String sourceCode;
	@Lob
	private String screenOutput;
	@Lob
	private String stateDetail;
	private Long memoryUsage;
	private Long storageUsage;
	private Long memoryLimit;
	private Integer cpuLimit;

	private String createdBy;
	private String comp;

	@Column
	@Type(type = Parameter.PARAMETER_LIST_JSON_TYPE)
	private List<Parameter> parameters = new ArrayList<>();

	@Column
	@Type(type = Input.INPUT_LIST_JSON_TYPE)
	private List<Input> inputs = new ArrayList<>();
	
	@Column
    @Type(type = Output.OUTPUT_LIST_JSON_TYPE)
    private List<Output> outputs = new ArrayList<>();


	@Column
	@Type(type = MetadataFile.METADATA_FILE_LIST_JSON_TYPE)
	private List<MetadataFile> metadataFiles = new ArrayList<>();

	public UUID getJobId() {
		if (jobIdPair == null) {
			return null;
		}
		return this.jobIdPair.getJobId();
	}

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

	public String getToolDescription() {
		return toolDescription;
	}

	public void setToolDescription(String toolDescription) {
		this.toolDescription = toolDescription;
	}

	public Instant getStartTime() {
		return startTime;
	}

	public void setStartTime(Instant startTime) {
		this.startTime = startTime;
	}

	public Instant getEndTime() {
		return endTime;
	}

	public void setEndTime(Instant endTime) {
		this.endTime = endTime;
	}

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}

	public JobState getState() {
		return state;
	}

	public void setState(JobState state) {
		this.state = state;
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

	public String getModule() {
		return module;
	}

	public void setModule(String module) {
		this.module = module;
	}

	public String getSourceCode() {
		return sourceCode;
	}

	public void setSourceCode(String sourceCode) {
		this.sourceCode = sourceCode;
	}

	public String getScreenOutput() {
		return screenOutput;
	}

	public void setScreenOutput(String screenOutput) {
		this.screenOutput = screenOutput;
	}

	public String getStateDetail() {
		return stateDetail;
	}

	public void setStateDetail(String stateDetail) {
		this.stateDetail = stateDetail;
	}

	public UUID getSessionId() {
		if (jobIdPair == null) {
			return null;
		}
		return jobIdPair.getSessionId();
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public Long getMemoryUsage() {
		return memoryUsage;
	}

	public void setMemoryUsage(Long memoryUsage) {
		this.memoryUsage = memoryUsage;
	}

	public JobIdPair getJobIdPair() {
		return jobIdPair;
	}

	public void setJobIdPair(JobIdPair jobIdPair) {
		this.jobIdPair = jobIdPair;
	}

	public void setJobIdPair(UUID sessionId, UUID jobId) {
		setJobIdPair(new JobIdPair(sessionId, jobId));
	}

	public List<MetadataFile> getMetadataFiles() {
		return metadataFiles;
	}

	public void setMetadataFiles(List<MetadataFile> metadataFiles) {
		this.metadataFiles = metadataFiles;
	}

	public String getComp() {
		return comp;
	}

	public void setComp(String comp) {
		this.comp = comp;
	}

	public Long getStorageUsage() {
		return storageUsage;
	}

	public void setStorageUsage(Long storageUsage) {
		this.storageUsage = storageUsage;
	}

	public Long getMemoryLimit() {
		return memoryLimit;
	}

	public void setMemoryLimit(Long memoryLimit) {
		this.memoryLimit = memoryLimit;
	}

	public Integer getCpuLimit() {
		return cpuLimit;
	}

	public void setCpuLimit(Integer cpuLimit) {
		this.cpuLimit = cpuLimit;
	}

    public List<Output> getOutputs() {
        return outputs;
    }

    public void setOutputs(List<Output> outputs) {
        this.outputs = outputs;
    }
}
