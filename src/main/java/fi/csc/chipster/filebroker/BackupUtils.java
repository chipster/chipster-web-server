package fi.csc.chipster.filebroker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.transfer.Download;
import com.amazonaws.services.s3.transfer.TransferManager;

public class BackupUtils {
	
	@SuppressWarnings("unused")
	private final static Logger logger = LogManager.getLogger();

	public static Map<Path, InfoLine> infoFileToMap(TransferManager transferManager, String bucket, String key, Path tempDir) throws AmazonServiceException, AmazonClientException, InterruptedException, IOException {
						
		Path infoPath = tempDir.resolve(key);
		Download download = transferManager.download(bucket, key, infoPath.toFile());
		download.waitForCompletion();
					
		Map<Path, InfoLine> map = (HashMap<Path, InfoLine>) Files.lines(infoPath)
				.map(line -> InfoLine.parseLine(line))
				.collect(Collectors.toMap(info -> info.getPath(), info -> info));
		Files.delete(infoPath);
		
		return map;
	}
}
