package fi.csc.chipster.sessiondb.model;

import java.util.Map;

import javax.persistence.CollectionTable;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.MapKeyColumn;

@Entity
public class DatasetColumn {

	@Id
	@GeneratedValue
	private int columnId;

	private String name;

	@ElementCollection(fetch = FetchType.EAGER)
	@MapKeyColumn(name = "key")
	@Column(name = "value")
	@CollectionTable(name = "ColumnMetadata", joinColumns = @JoinColumn(name = "columnId"))
	private Map<String, String> metadata;

	public String getName() {
		return name;
	}

	public void setName(String column) {
		this.name = column;
	}

	public Map<String, String> getMetadata() {
		return metadata;
	}

	public void setMetadata(Map<String, String> metadata) {
		this.metadata = metadata;
	}
}
