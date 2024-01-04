package fi.csc.chipster.tools.parsers;

import fi.csc.chipster.tools.model.Region;

/**
 * This class represents a single line of Vcf file.
 * 
 * @author klemela
 */
public class TsvLine extends FileLine {

	private Region region;
	private String[] headers;
	private String[] values;

	public Region getRegion() {
		return region;
	}

	public void setRegion(Region region) {
		this.region = region;
	}

	public String[] getHeaders() {
		return headers;
	}

	public void setHeaders(String[] headers) {
		this.headers = headers;
	}

	public String[] getValues() {
		return values;
	}

	public void setValues(String[] values) {
		this.values = values;
	}
}