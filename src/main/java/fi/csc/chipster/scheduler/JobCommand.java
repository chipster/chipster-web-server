package fi.csc.chipster.scheduler;

import java.util.UUID;

import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class JobCommand {
	
	private UUID sessionId;
	private UUID jobId;
	private UUID compId;
	private Command command;
	
	public enum Command {
		SCHEDULE,
		OFFER,
		CHOOSE,
		BUSY,
		AVAILABLE,
		CANCEL, 
		RUNNING
	}	
	
	public JobCommand() {
		// for JSON parsing
	}
	
	public JobCommand(UUID sessionId, UUID jobId, UUID compId, Command command) {
		this.sessionId = sessionId;
		this.jobId = jobId;
		this.compId = compId;
		this.command = command;
	}

	public UUID getJobId() {
		return jobId;
	}

	public void setJobId(UUID jobId) {
		this.jobId = jobId;
	}

	public UUID getCompId() {
		return compId;
	}

	public void setCompId(UUID compId) {
		this.compId = compId;
	}

	public Command getCommand() {
		return command;
	}

	public void setCommand(Command command) {
		this.command = command;
	}

	public UUID getSessionId() {
		return sessionId;
	}

	public void setSessionId(UUID sessionId) {
		this.sessionId = sessionId;
	}
}
