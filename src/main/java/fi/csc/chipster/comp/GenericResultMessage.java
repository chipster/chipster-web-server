package fi.csc.chipster.comp;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.sessiondb.model.Parameter;

/**
 * Generic result message, independent of the communication method used.
 * 
 * @author klemela
 *
 */
public class GenericResultMessage {
	/**
	 * Logger for this class
	 */
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
		
	private String jobId;
	private JobState state;
	private String stateDetail;
	private String errorMessage;
	private String outputText;
	private String sourceCode;
	private Instant startTime;
	private Instant endTime;
	private LinkedHashMap<String, Parameter> parameters;
	
	private String versionsJson;
	

	// preserve tool's output order in job.outputs
	private final LinkedHashMap<String, String> outputIdToDatasetIdMap = new LinkedHashMap<>();
	private final Map<String, String> outputIdToDatasetNameMap = new HashMap<>();
	private final Map<String, String> outputIdToDisplayNameMap = new HashMap<>();
	
	public GenericResultMessage(String jobId, JobState state, String stateDetail, String errorMessage,
			String outputText) {
		
		this.jobId = jobId;
        this.state = state;
		this.stateDetail = stateDetail;
		this.errorMessage = errorMessage;
		this.outputText = outputText;
	}
	
	public GenericResultMessage() {
		super();
	}
	
	
	public String getVersionsJson() {
		return versionsJson;
	}

	public void setVersionsJson(String versionsJson) {
		this.versionsJson = versionsJson;
	}

	public void setParameters(LinkedHashMap<String, Parameter> parameters) {
		this.parameters = parameters;
	}

	public LinkedHashMap<String, Parameter> getParameters() {
		return parameters;
	}

	
	/**
	 * @return Return error message in case of failed job execution. 
	 * 
	 */
	public String getErrorMessage() {
		return errorMessage;
	}

	/**
	 * @see #getErrorMessage()
	 * @param errorMessage
	 */
	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	/**
	 * @return Return the exit state of the job.
	 * 
	 */
	public JobState getState() {
		return state;
	}

	/**
	 * @see #getState()
	 * @param exitState
	 */
	public void setState(JobState exitState) {
		this.state = exitState;
	}

	/**
	 * @return Returns the text output (sysout) of the job.  
	 */
	public String getOutputText() {
		return outputText;
	}

	/**
	 * @see #getOutputText()
	 */
	public void setOutputText(String output) {
		this.outputText = output;
	}

	public void setSourceCode(String sourceCode) {
		this.sourceCode = sourceCode;
	}
	
	public String getSourceCode() {
		return this.sourceCode;
	}
	
	public String getStateDetail() {
		return stateDetail;
	}

	public void setStateDetail(String stateDetail) {
		this.stateDetail = stateDetail;
	}

	public String getJobId() {
		return jobId;
	}

	public void setJobId(String jobId) {
		this.jobId = jobId;
	}
	
	public void addDataset(String outputId, String datasetId, String datasetName, String outputDisplayName) {
		outputIdToDatasetIdMap.put(outputId, datasetId);
		outputIdToDatasetNameMap.put(outputId, datasetName);
		outputIdToDisplayNameMap.put(outputId, outputDisplayName);
	}
	
	public ArrayList<String> getOutputIds() {
	    // preserve order of LinkedHashMap
	    return new ArrayList<String>(outputIdToDatasetIdMap.keySet());
	}
	
	public String getDatasetId(String outputId) {
		return outputIdToDatasetIdMap.get(outputId);
	}

	public String getDatasetName(String outputId) {
		return outputIdToDatasetNameMap.get(outputId);
	}
	
	public String getOutputDisplayName(String outputId) {
        return outputIdToDisplayNameMap.get(outputId);
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


}
	

