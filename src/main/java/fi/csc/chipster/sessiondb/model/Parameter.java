package fi.csc.chipster.sessiondb.model;

import javax.persistence.Lob;

import fi.csc.chipster.rest.hibernate.DeepCopyable;
import fi.csc.microarray.description.SADLSyntax.ParameterType;

public class Parameter implements DeepCopyable {

	public static final String PARAMETER_LIST_JSON_TYPE = "ParameterListJsonType";
	
	private String parameterId;
	private String displayName;
	@Lob
	private String description;
	private ParameterType type;
	private String value;
	
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
	@Override
	public Object deepCopy() {
		Parameter p = new Parameter();
		p.parameterId = parameterId;
		p.displayName = displayName;
		p.description = description;
		p.type = type;
		p.value = value;
		return p;
	}
}
