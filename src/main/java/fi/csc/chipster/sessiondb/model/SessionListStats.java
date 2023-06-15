package fi.csc.chipster.sessiondb.model;

import jakarta.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class SessionListStats {

	private long size;
	
	public long getSize() {
		return size;
	}
	public void setSize(long size) {
		this.size = size;
	}
}
