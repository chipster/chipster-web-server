package fi.csc.chipster.jobhistory;

import java.time.Instant;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import jakarta.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import fi.csc.chipster.sessiondb.model.JobIdPair;


@Entity
@Table(indexes={
		@Index(columnList="created DESC", name="job_history_created_index")
})
@XmlRootElement
public class JobHistory {
	@EmbeddedId // db
	@JsonUnwrapped
	private JobIdPair jobIdPair;
	private String toolId;
	private String toolName;
	private String comp;
	@Column(name="startTime")
	private Instant startTime;
	private Instant endTime;
	private Instant created;
	private String createdBy;
	@Lob
	private String screenOutput;
	private String state;
	private String stateDetail;
	private Long memoryUsage;
	private Long storageUsage;
	private String module;

	public JobHistory() {

	}

	public String getToolId() {
		return toolId;
	}

	public void setToolId(String toolId) {
		this.toolId = toolId;
	}

	public String getToolName() {
		return toolName;
	}

	public void setToolName(String toolName) {
		this.toolName = toolName;
	}

	public Instant getEndTime() {
		return endTime;
	}

	public void setEndTime(Instant endTime) {
		this.endTime = endTime;
	}

	public Instant getStartTime() {
		return startTime;
	}

	public void setStartTime(Instant startTime) {
		this.startTime = startTime;
	}

	public JobIdPair getJobIdPair() {
		return this.jobIdPair;
	}

	public void setJobIdPair(JobIdPair jobIdPair) {
		this.jobIdPair = jobIdPair;
	}

	public void setJobIdPair(UUID sessionId, UUID jobId) {
		this.setJobIdPair(new JobIdPair(sessionId, jobId));
	}

	public Long getMemoryUsage() {
		return memoryUsage;
	}

	public void setMemoryUsage(Long memoryUsage) {
		this.memoryUsage = memoryUsage;
	}

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}

	public String getComp() {
		return comp;
	}

	public void setComp(String comp) {
		this.comp = comp;
	}

	public String getCreatedBy() {
		return createdBy;
	}

	public void setCreatedBy(String createdBy) {
		this.createdBy = createdBy;
	}

	public String getScreenOutput() {
		return screenOutput;
	}

	public void setScreenOutput(String screenOutput) {
		this.screenOutput = screenOutput;
	}

	public String getState() {
		return state;
	}

	public void setState(String state) {
		this.state = state;
	}

	public String getStateDetail() {
		return stateDetail;
	}

	public void setStateDetail(String stateDetail) {
		this.stateDetail = stateDetail;
	}

	public String getModule() {
		return module;
	}

	public void setModule(String module) {
		this.module = module;
	}

    public Long getStorageUsage() {
        return storageUsage;
    }

    public void setStorageUsage(Long storageUsage) {
        this.storageUsage = storageUsage;
    }
}
