package fi.csc.chipster.sessiondb;

import fi.csc.chipster.sessiondb.model.File;

public class FileUtils {
	public static boolean isEmpty(File file) {
		return file == null || (file.getFileId() == null && file.getChecksum() == null && file.getSize() == -1);
	}
}