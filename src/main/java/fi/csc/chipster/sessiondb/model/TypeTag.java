package fi.csc.chipster.sessiondb.model;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

@Entity
public class TypeTag {

	@Id 
	@GeneratedValue
	private long typeTagyId;
	
	private Long version;
	private String key;
	private String value;
	
	public TypeTag(long version, String key, String value) {
		this.version = version;
		this.key = key;
		this.value = value;
	}
	
	public TypeTag() {
		// for Jackson
	}
	
	public String getKey() {
		return key;
	}
	public void setKey(String key) {
		this.key = key;
	}
	public String getValue() {
		return value;
	}
	public void setValue(String value) {
		this.value = value;
	}
	public Long getVersion() {
		return version;
	}
	public void setVersion(Long version) {
		this.version = version;
	}
}
