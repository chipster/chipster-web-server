package fi.csc.chipster.sessiondb.model;

import fi.csc.chipster.rest.hibernate.DeepCopyable;

public class Output implements DeepCopyable {
	
	public static final String OUTPUT_LIST_JSON_TYPE = "OutputListJsonType";

	private String outputId;
	private String displayName;
	private String datasetId;
		
	public String getDisplayName() {
		return displayName;
	}
	public void setDisplayName(String displayName) {
		this.displayName = displayName;
	}
	public String getDatasetId() {
		return datasetId;
	}
	public void setDatasetId(String datasetId) {
		this.datasetId = datasetId;
	}	
    public String getOutputId() {
        return outputId;
    }
    public void setOutputId(String outputId) {
        this.outputId = outputId;
    }
    @Override
    public Object deepCopy() {
        Output i = new Output();
        i.outputId = outputId;
        i.displayName = displayName;
        i.datasetId = datasetId;
        return i;
    }
}
