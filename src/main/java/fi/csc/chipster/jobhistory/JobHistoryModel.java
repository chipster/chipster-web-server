package fi.csc.chipster.jobhistory;

import java.time.Instant;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Lob;
import javax.xml.bind.annotation.XmlRootElement;



@Entity
@XmlRootElement
public class JobHistoryModel {
	//UserName
	//tool name
	//comp
	//time duration
	//Start time
	//End time
	//Output
	//Status
	 
	//Filter needed 
	//Username
	//tool name
	//comp
	//time duration
	//End time
	//Output
	//Status
	
	@Id
	@Column
	private UUID jobId;
	private String toolId;
	private String toolName;
	private String compName;
	private String timeDuration;
	private Instant startTime;
	private Instant endTime;
	@Lob
	private String output;
	private String jobStatus;
	
	public JobHistoryModel(){
		
	}
	
	public UUID getJobId() {
		return jobId;
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
	public String getTimeDuration() {
		return timeDuration;
	}
	public void setTimeDuration(String timeDuration) {
		this.timeDuration = timeDuration;
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
	

}
