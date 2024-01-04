package fi.csc.chipster.sessiondb.model;

import java.time.Instant;
import java.util.UUID;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;

import org.hibernate.annotations.Type;

import com.fasterxml.jackson.databind.JsonNode;

import fi.csc.chipster.rest.hibernate.JsonNodeJsonType;
import jakarta.xml.bind.annotation.XmlRootElement;

@Entity // db
@XmlRootElement // REST
public class News {

	public News() {
	} // JAXB needs this

	@Id // db
	@Column(columnDefinition = "uuid", updatable = false) // uuid instead of binary
	private UUID newsId;
	private Instant created;
	private Instant modified;
	/*
	 * Create jsonb column
	 * 
	 * At the moment this could be kind of object structure, the backend doesn't
	 * care.
	 * Use the jsonb column type anyway to be able to query its contents later if
	 * needed.
	 */
	@Column
	@Type(type = JsonNodeJsonType.JSON_NODE_JSON_TYPE)
	private JsonNode contents;

	public UUID getNewsId() {
		return newsId;
	}

	public void setNewsId(UUID newsId) {
		this.newsId = newsId;
	}

	public Instant getCreated() {
		return created;
	}

	public void setCreated(Instant created) {
		this.created = created;
	}

	public Instant getModified() {
		return modified;
	}

	public void setModified(Instant modified) {
		this.modified = modified;
	}

	public JsonNode getContents() {
		return contents;
	}

	public void setContents(JsonNode contents) {
		this.contents = contents;
	}
}
