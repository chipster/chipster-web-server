package fi.csc.chipster.filebroker.s3storageclient;

import java.util.HashMap;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.model.HeadBucketRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import fi.csc.chipster.rest.RestUtils;
import jakarta.ws.rs.NotFoundException;

public class S3StorageAdminClient {

    private Logger logger = LogManager.getLogger();

    private S3StorageClient s3StorageClient;

    public S3StorageAdminClient(S3StorageClient s3StorageClient) {
        this.s3StorageClient = s3StorageClient;

    }

    public String getFileStats(String storageId) {

        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        ObjectListing objectListing = this.s3StorageClient.getTransferManager().getAmazonS3Client()
                .listObjects(bucket);

        long files = 0;
        long bytes = 0;

        do {
            List<S3ObjectSummary> summaries = objectListing.getObjectSummaries();

            for (S3ObjectSummary summary : summaries) {

                files++;
                bytes += summary.getSize();
            }

            objectListing = this.s3StorageClient.getTransferManager().getAmazonS3Client()
                    .listNextBatchOfObjects(objectListing);

        } while (objectListing.isTruncated());

        HashMap<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("storageId", storageId);
        jsonMap.put("fileCount", files);
        jsonMap.put("fileBytes", bytes);
        jsonMap.put("status", null);

        return RestUtils.asJson(jsonMap);
    }

    public String getStatus(String storageId) {

        String bucket = this.s3StorageClient.storageIdToBucket(storageId);

        try {
            // check if bucket exists
            final HeadBucketRequest request = new HeadBucketRequest(bucket);
            this.s3StorageClient.getTransferManager().getAmazonS3Client().headBucket(request);

            HashMap<String, Object> jsonMap = new HashMap<>();
            // jsonMap.put("status", null);

            return RestUtils.asJson(jsonMap);

        } catch (AmazonServiceException e) {
            if (e.getStatusCode() == 404 | e.getStatusCode() == 403 || e.getStatusCode() == 301) {
                throw new NotFoundException();
            }
            throw e;
        } catch (Exception e) {
            logger.error("failed to check bucket status", e);
            throw e;
        }
    }

    public String getStorageId(String storageId) {

        HashMap<String, Object> jsonMap = new HashMap<>();
        jsonMap.put("storageId", null);

        return RestUtils.asJson(jsonMap);
    }
}
