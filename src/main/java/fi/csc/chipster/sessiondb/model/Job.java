package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Index;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.Table;
import javax.xml.bind.annotation.XmlRootElement;

import org.hibernate.annotations.Type;

import com.fasterxml.jackson.annotation.JsonIgnore;

import fi.csc.microarray.messaging.JobState;

@Entity // db
@XmlRootElement // rest
@Table(indexes = {
        @Index(columnList = "sessionId", name = "job_sessionid_index"),
})
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
	
	private String createdBy;
	
	@ManyToOne
	@JoinColumn(name="sessionId")
	private Session session;
	
////	@OneToMany(cascade=CascadeType.ALL, orphanRemoval=true)
////	@JoinColumn(name="jobId")
////	@LazyCollection(LazyCollectionOption.FALSE)
//	@OneToMany(mappedBy="job", cascade=CascadeType.ALL, fetch=FetchType.EAGER)
//	private List<Parameter> parameters = new ArrayList<>();
	
//	@OneToMany(cascade=CascadeType.ALL, orphanRemoval=true)
//	@JoinColumn(name="jobId")
//	@LazyCollection(LazyCollectionOption.FALSE)
////	@OneToMany(mappedBy="job", cascade=CascadeType.ALL, fetch=FetchType.EAGER)
//	private List<Input> inputs = new ArrayList<>();
	
	
	@Column
	@Type(type = Parameter.PARAMETER_LIST_JSON_TYPE)
	private List<Parameter> parameters = new ArrayList<>();
	
	@Column
	@Type(type = Input.INPUT_LIST_JSON_TYPE)
	private List<Input> inputs = new ArrayList<>();
	
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
	
	// don't parse from the JSON, because this would usually come from the client 
	// and couldn't be trusted
	@JsonIgnore
	public Session getSession() {
		return session;
	}
	
	public void setSession(Session session) {
		this.session = session;
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
}
