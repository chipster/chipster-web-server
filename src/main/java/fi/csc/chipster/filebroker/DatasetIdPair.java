package fi.csc.chipster.filebroker;

import java.util.UUID;

public class DatasetIdPair {

    private UUID sessionId;
    private UUID datasetId;

    public DatasetIdPair(UUID sessionId, UUID datasetId) {
        this.sessionId = sessionId;
        this.datasetId = datasetId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getDatasetId() {
        return datasetId;
    }

}
