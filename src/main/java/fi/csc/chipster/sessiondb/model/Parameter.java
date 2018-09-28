package fi.csc.chipster.sessiondb.model;

import javax.persistence.FetchType;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;

import fi.csc.microarray.description.SADLSyntax.ParameterType;

public class Parameter {

	public static final String PARAMETER_LIST_JSON_TYPE = "ParameterListJsonType";
	
	private String parameterId;
	private String displayName;
	@Lob
	private String description;
	private ParameterType type;
	private String value;
	
	@ManyToOne(fetch=FetchType.LAZY)
	@JoinColumn(name="jobId")
	private Job job;
	
	public String getParameterId() {
		return parameterId;
	}
	public void setParameterId(String parameterId) {
		this.parameterId = parameterId;
	}
	public String getDisplayName() {
		return displayName;
	}
	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}
	public String getDescription() {
		return description;
	}
	public void setDescription(String description) {
		this.description = description;
	}
	public ParameterType getType() {
		return type;
	}
	public void setType(ParameterType type) {
		this.type = type;
	}
	public String getValue() {
		return value;
	}
	public void setValue(String value) {
		this.value = value;
	}
}
