package fi.csc.chipster.sessiondb.model;

public class MetadataEntry {
	
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
}
