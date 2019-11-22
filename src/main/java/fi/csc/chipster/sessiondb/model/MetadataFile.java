package fi.csc.chipster.sessiondb.model;

import javax.persistence.Lob;

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

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + ((content == null) ? 0 : content.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
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
		MetadataFile other = (MetadataFile) obj;
		if (content == null) {
			if (other.content != null)
				return false;
		} else if (!content.equals(other.content))
			return false;
		if (name == null) {
			if (other.name != null)
				return false;
		} else if (!name.equals(other.name))
			return false;
		return true;
	}
}
