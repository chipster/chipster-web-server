package fi.csc.chipster.sessiondb.model;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.xml.bind.annotation.XmlRootElement;
import javax.xml.bind.annotation.XmlTransient;

import fi.csc.microarray.messaging.JobState;

@Entity // db
@XmlRootElement // rest
public class Job {
	 
	@Id // db
	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
	private UUID jobId;
	private String toolId;
	private JobState state;
	private String toolCategory;
	private String toolName;
	@Lob
	private String toolDescription;
	private LocalDateTime startTime;
	private LocalDateTime endTime;
	private String module;
	@Lob
	private String sourceCode;
	@Lob
	private String screenOutput;
	@Lob
	private String stateDetail;
	
	@XmlTransient
	@ManyToOne
	@JoinColumn(name="sessionId")
	private Session session;
	
	@OneToMany(cascade=CascadeType.ALL, fetch=FetchType.EAGER)
	@JoinColumn(name="jobId")
	private Set<Parameter> parameters = new HashSet<>();
	
	@OneToMany(cascade=CascadeType.ALL, fetch=FetchType.EAGER)
	@JoinColumn(name="jobId")
	private Set<Input> inputs = new HashSet<>();
	
	public UUID getJobId() {
		return this.jobId;
	}
	public void setJobId(UUID jobId) {
		this.jobId = jobId;
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
	public LocalDateTime getStartTime() {
		return startTime;
	}
	public void setStartTime(LocalDateTime startTime) {
		this.startTime = startTime;
	}
	public LocalDateTime getEndTime() {
		return endTime;
	}
	public void setEndTime(LocalDateTime endTime) {
		this.endTime = endTime;
	}
	public JobState getState() {
		return state;
	}
	public void setState(JobState state) {
		this.state = state;
	}
	public Set<Parameter> getParameters() {
		return parameters;
	}
	public void setParameters(Set<Parameter> parameters) {
		this.parameters = parameters;
	}
	
	public Set<Input> getInputs() {
		return inputs;
	}
	
	public void setInputs(LinkedHashSet<Input> inputs) {
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
	
	public Session getSession() {
		return session;
	}
	public void setSession(Session session) {
		this.session = session;
	}
}
