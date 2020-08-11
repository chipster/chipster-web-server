package fi.csc.chipster.jobhistory;

import java.time.Instant;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.EmbeddedId;
import javax.persistence.Entity;
import javax.persistence.Index;
import javax.persistence.Lob;
import javax.persistence.Table;
import javax.xml.bind.annotation.XmlRootElement;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import fi.csc.chipster.sessiondb.model.JobIdPair;


@Entity
@Table(name="JobHistoryModel", indexes={
		@Index(columnList="created DESC", name="job_history_created_index")
})
@XmlRootElement
public class JobHistoryModel {
	@EmbeddedId // db
	@JsonUnwrapped
	private JobIdPair jobIdPair;
	private String toolId;
	private String toolName;
	private String compName;
	@Column(name="startTime")
	private Instant startTime;
	private Instant endTime;
	private Instant created;
	private String userName;
	@Lob
	private String output;
	private String jobStatus;
	private String jobStatusDetail;
	private Long memoryUsage;

	public JobHistoryModel() {

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

	public String getCompName() {
		return compName;
	}

	public void setCompName(String compName) {
		this.compName = compName;
	}

	public Instant getEndTime() {
		return endTime;
	}

	public void setEndTime(Instant endTime) {
		this.endTime = endTime;
	}

	public String getOutput() {
		return output;
	}

	public void setOutput(String output) {
		this.output = output;
	}

	public String getJobStatus() {
		return jobStatus;
	}

	public void setJobStatus(String jobStatus) {
		this.jobStatus = jobStatus;
	}

	public Instant getStartTime() {
		return startTime;
	}

	public void setStartTime(Instant startTime) {
		this.startTime = startTime;
	}

	public String getUserName() {
		return userName;
	}

	public void setUserName(String userName) {
		this.userName = userName;
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

	public String getJobStatusDetail() {
		return jobStatusDetail;
	}

	public void setJobStatusDetail(String jobStatusDetail) {
		this.jobStatusDetail = jobStatusDetail;
	}
}
