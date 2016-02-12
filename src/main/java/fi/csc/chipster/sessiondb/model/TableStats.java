package fi.csc.chipster.sessiondb.model;

import javax.xml.bind.annotation.XmlRootElement;

@XmlRootElement
public class TableStats {

	private String name;
	private long size;
	
	public String getName() {
		return name;
	}
	public void setName(String name) {
		this.name = name;
	}
	public long getSize() {
		return size;
	}
	public void setSize(long size) {
		this.size = size;
	}
}
