package fi.csc.chipster.sessiondb.model;

public enum SessionState {
	// don't change the order, saved as int in the DB
	IMPORT,
	TEMPORARY_UNMODIFIED,
	READY,
	DELETE,
	TEMPORARY_MODIFIED
}
