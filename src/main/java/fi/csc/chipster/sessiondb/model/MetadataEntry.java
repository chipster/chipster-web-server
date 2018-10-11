package fi.csc.chipster.sessiondb.model;

import fi.csc.chipster.rest.hibernate.DeepCopyable;

public class MetadataEntry implements DeepCopyable {
	
	public static final String METADATA_ENTRY_LIST_JSON_TYPE = "MetadataEntryListJsonType";
	
	private String col;
	private String key;
	private String value;
	
	public String getColumn() {
		return col;
	}
	public void setColumn(String column) {
		this.col = column;
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
	
	@Override
	public Object deepCopy() {
		MetadataEntry entry = new MetadataEntry();
		entry.key = key;
		entry.value = value;
		entry.col = col;
		return entry;
	}
}
