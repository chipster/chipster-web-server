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
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((description == null) ? 0 : description.hashCode());
		result = prime * result + ((displayName == null) ? 0 : displayName.hashCode());
		result = prime * result + ((parameterId == null) ? 0 : parameterId.hashCode());
		result = prime * result + ((type == null) ? 0 : type.hashCode());
		result = prime * result + ((value == null) ? 0 : value.hashCode());
		return result;
	}
	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Parameter other = (Parameter) obj;
		if (description == null) {
			if (other.description != null)
				return false;
		} else if (!description.equals(other.description))
			return false;
		if (displayName == null) {
			if (other.displayName != null)
				return false;
		} else if (!displayName.equals(other.displayName))
			return false;
		if (parameterId == null) {
			if (other.parameterId != null)
				return false;
		} else if (!parameterId.equals(other.parameterId))
			return false;
		if (type != other.type)
			return false;
		if (value == null) {
			if (other.value != null)
				return false;
		} else if (!value.equals(other.value))
			return false;
		return true;
	}
}
