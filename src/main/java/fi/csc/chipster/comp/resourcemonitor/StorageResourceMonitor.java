package fi.csc.chipster.comp.resourcemonitor;

import java.io.File;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class StorageResourceMonitor {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	private File jobDataDir;
	private Long maxStorage;
	private Long currentStorage;
	
	public StorageResourceMonitor(File jobDataDir) {
		this.jobDataDir = jobDataDir;
	}
	public Long getMaxStorage() {
		return maxStorage;
	}

	public Long getCurrentStorage() {
		return currentStorage;
	}
	
	public void update() throws IOException {
				
		this.currentStorage = FileUtils.sizeOfDirectory(this.jobDataDir);
		
		if (this.maxStorage == null || currentStorage > this.maxStorage) {
			this.maxStorage = currentStorage;
		}
	}
}