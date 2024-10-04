package fi.csc.chipster.sessiondb.model;

import java.util.Map;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;

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
