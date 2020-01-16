package fi.csc.chipster.sessionworker.xml;

public class DataManager {
	
	public static enum StorageMethod {
		
		LOCAL_ORIGINAL(true, true),
		LOCAL_TEMP(true, true),
		LOCAL_SESSION_ZIP(true, false),
		REMOTE_ORIGINAL(false, true);
		
		// Groups that describe how fast different methods are to access.
		// Keep these up-to-date when you add methods!
		public static StorageMethod[] LOCAL_FILE_METHODS = {LOCAL_ORIGINAL, LOCAL_TEMP};
		public static StorageMethod[] REMOTE_FILE_METHODS = {REMOTE_ORIGINAL};
		public static StorageMethod[] OTHER_SLOW_METHODS = {LOCAL_SESSION_ZIP};
		
		private boolean isLocal;
		private boolean isRandomAccess;
		
		StorageMethod(boolean isLocal, boolean isRandomAccess) {
			this.isLocal = isLocal;
			this.isRandomAccess = isRandomAccess;
		}

		public boolean isLocal() {
			return isLocal;
		}

		public boolean isRandomAccess() {
			return isRandomAccess;
		}
		
		/**
		 * Returns value of given name, so that old names are first converted to new naming scheme and
		 * then used to call {@link Enum#valueOf(Class, String)}.
		 * 
		 * @param name name or old name of StorageMethod enum value
		 * 
		 * @return StorageMethod enum value
		 */
		public static StorageMethod valueOfConverted(String name) {
			if ("REMOTE_STORAGE".equals(name)) {
				name = "REMOTE_ORIGINAL";
			} else if ("LOCAL_USER".equals(name)) {
				name = "LOCAL_ORIGINAL";
			} else if ("LOCAL_SESSION".equals(name)) {
				name = "LOCAL_SESSION_ZIP";
			}

			return valueOf(name);
		}	
	}
}
