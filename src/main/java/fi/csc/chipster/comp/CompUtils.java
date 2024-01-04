package fi.csc.chipster.comp;

import java.util.List;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.sessiondb.model.Job;
import fi.csc.chipster.sessiondb.model.MetadataFile;

public class CompUtils {

	private static final Logger logger = LogManager.getLogger();
	public static final String METADATA_FILE_NAME_APPLICATION_VERSIONS = "application-versions.json";

	public static void addVersionsToDbJob(GenericResultMessage result, Job dbJob)  {
		if (result.getVersionsJson() != null) {
			logger.debug("adding versions info to db job");
			MetadataFile versionsMetadataFile = new MetadataFile(METADATA_FILE_NAME_APPLICATION_VERSIONS, result.getVersionsJson());
			
			// TODO for now, just remove possible existing versions file, should not exist really
			List<MetadataFile> updatedMetadataFiles = dbJob.getMetadataFiles().stream().filter(metadataFile -> metadataFile.getName().equals(METADATA_FILE_NAME_APPLICATION_VERSIONS)).collect(Collectors.toList());
			updatedMetadataFiles.add(versionsMetadataFile);
			dbJob.setMetadataFiles(updatedMetadataFiles);
			
		}
	}
	
	public static String getMetadataFilesAsString(List<MetadataFile> metadataFiles) {
		final StringBuffer sb = new StringBuffer();
		metadataFiles.forEach((f-> sb.append(f.getName() + "\n")));
		return sb.toString();
	}

}