package fi.csc.chipster.rest;

public enum LogType {

	API("api"),
	ADMIN("admin");

	private String type;

	LogType(String type) {
		this.type = type;
	}

	public String getType() {
		return this.type;
	}
}
