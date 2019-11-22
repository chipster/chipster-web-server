package fi.csc.chipster.sessiondb.model;

import javax.persistence.Lob;

import fi.csc.chipster.rest.hibernate.DeepCopyable;

public class Input implements DeepCopyable {
	
	public static final String INPUT_LIST_JSON_TYPE = "InputListJsonType";

	private String inputId;
	private String displayName;
	@Lob
	private String description;
	private String type;
	private String datasetId;
		
	public String getInputId() {
		return inputId;
	}
	public void setInputId(String id) {
		this.inputId = id;
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
	public String getType() {
		return type;
	}
	public void setType(String type) {
		this.type = type;
	}
	public String getDatasetId() {
		return datasetId;
	}
	public void setDatasetId(String datasetId) {
		this.datasetId = datasetId;
	}
	@Override
	public Object deepCopy() {
		return deepCopy(new Input());
		
	}
	public <T extends Input> T deepCopy(T i) {
		i.setInputId(inputId);
		i.setDisplayName(displayName);
		i.setDescription(description);
		i.setType(type);
		i.setDatasetId(datasetId);
		return i;
	}
	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((datasetId == null) ? 0 : datasetId.hashCode());
		result = prime * result + ((description == null) ? 0 : description.hashCode());
		result = prime * result + ((displayName == null) ? 0 : displayName.hashCode());
		result = prime * result + ((inputId == null) ? 0 : inputId.hashCode());
		result = prime * result + ((type == null) ? 0 : type.hashCode());
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
		Input other = (Input) obj;
		if (datasetId == null) {
			if (other.datasetId != null)
				return false;
		} else if (!datasetId.equals(other.datasetId))
			return false;
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
		if (inputId == null) {
			if (other.inputId != null)
				return false;
		} else if (!inputId.equals(other.inputId))
			return false;
		if (type == null) {
			if (other.type != null)
				return false;
		} else if (!type.equals(other.type))
			return false;
		return true;
	}
	
}
