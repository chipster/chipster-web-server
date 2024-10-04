package fi.csc.chipster.sessiondb.model;

import jakarta.persistence.Lob;

import fi.csc.chipster.rest.hibernate.DeepCopyable;

public class MetadataFile implements DeepCopyable {

	public static final String METADATA_FILE_LIST_JSON_TYPE = "MetadataFileListJsonType";

	private String name;
	@Lob
	private String content;

	public MetadataFile() {
	}

	public MetadataFile(String name, String content) {
		this.name = name;
		this.content = content;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	@Override
	public Object deepCopy() {
		MetadataFile f = new MetadataFile();
		f.name = name;
		f.content = content;
		return f;
	}

}
