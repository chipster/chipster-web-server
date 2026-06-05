package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.xml.bind.annotation.XmlRootElement;

@Entity // db
@XmlRootElement // rest
@Table(indexes = { @Index(columnList = "sessionId", name = "label_sessionid_index"), })
public class Label {

	public static final int MAX_NAME_LENGTH = 30;
	public static final int MAX_LABELS_PER_SESSION = 100;

	@EmbeddedId // db
	@JsonUnwrapped
	private LabelIdPair labelIdPair;

	private String name;
	private String color;
	private Instant created;

	public Label() {
	} // JAXB needs this

	public UUID getLabelId() {
		if (labelIdPair == null) {
			return null;
		}
		return labelIdPair.getLabelId();
	}

	public UUID getSessionId() {
		if (labelIdPair == null) {
			return null;
		}
		return labelIdPair.getSessionId();
	}

	public LabelIdPair getLabelIdPair() {
		return labelIdPair;
	}

	public void setLabelIdPair(LabelIdPair labelIdPair) {
		this.labelIdPair = labelIdPair;
	}

	public void setLabelIdPair(UUID sessionId, UUID labelId) {
		setLabelIdPair(new LabelIdPair(sessionId, labelId));
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getColor() {
		return color;
	}

	public void setColor(String color) {
		this.color = color;
	}

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}
}
