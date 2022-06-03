package fi.csc.chipster.sessiondb.model;

import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;

import jakarta.xml.bind.annotation.XmlRootElement;

@Entity // db
@XmlRootElement // REST
//public class Notification implements DeepCopyable {
public class Notification {

//	public static final String NOTIFICATION_CONTENT_JSON_TYPE = "NotificationContentJsonType";

	public Notification() {} // JAXB needs this
	
	@Id
    @GeneratedValue
    private Long id;
	
//	@Id // db
//	@Column( columnDefinition = "uuid", updatable = false ) // uuid instead of binary
//	private UUID notificationId;
//	private int number;
//	@Lob
////	@Column
////	@Type(type = NOTIFICATION_CONTENT_JSON_TYPE)
//	private String content;
	
//	public UUID getNotificationId() {
//		return this.notificationId;
//	}

//	public int getNumber() {
//		return number;
//	}
//
//	public void setNumber(int number) {
//		this.number = number;
//	}

//	public String getContent() {
//		return content;
//	}
//
//	public void setContent(String content) {
//		this.content = content;
//	}

//	public void setNotificationId(UUID notificationId) {
//		this.notificationId = notificationId;
//	}
	
//	@Override
//	public Object deepCopy() {
//		Notification n = new Notification();
//		n.notificationId = notificationId;
//		n.number = number;
//		n.content = content;
//		
//		return n;
//	}
}
