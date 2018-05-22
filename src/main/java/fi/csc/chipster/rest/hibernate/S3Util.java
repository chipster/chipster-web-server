package fi.csc.chipster.rest.hibernate;

import java.net.URL;
import java.util.List;

import org.joda.time.Duration;
import org.joda.time.Instant;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.HttpMethod;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GeneratePresignedUrlRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

public class S3Util {
	
	public static TransferManager getTransferManager(String endpoint, String region, String access, String secret, String signerOverride) {
		return TransferManagerBuilder.standard()
				.withS3Client(getClient(endpoint, region, access, secret, signerOverride))
				.build();
	}

	public static AmazonS3 getClient(String endpoint, String region, String access, String secret, String signerOverride) {

		AWSCredentials credentials = new BasicAWSCredentials(access, secret);

		ClientConfiguration clientConfig = new ClientConfiguration();
		//clientConfig.setSignerOverride("S3SignerType");
		clientConfig.setSignerOverride(signerOverride);

		AmazonS3 s3 = AmazonS3ClientBuilder.standard()
				.withClientConfiguration(clientConfig)
				//.withEndpointConfiguration(new EndpointConfiguration("object.pouta.csc.fi", "regionOne"))
				.withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
				.withCredentials(new AWSStaticCredentialsProvider(credentials))
				.build();

		return s3;
	}

	public static List<S3ObjectSummary> getObjects(TransferManager transferManager, String bucket) {
		AmazonS3 s3 = transferManager.getAmazonS3Client();
		ObjectListing listing = s3.listObjects(bucket);
		List<S3ObjectSummary> summaries = listing.getObjectSummaries();
		while (listing.isTruncated()) {
		   listing = s3.listNextBatchOfObjects (listing);
		   summaries.addAll (listing.getObjectSummaries());
		}
		return summaries;
	}

	public static URL getPresignedUrl(TransferManager transferManager, String bucket, String key, int secondsToExpire) {
        java.util.Date expiration = new java.util.Date();
        long expTimeMillis = expiration.getTime();
        expTimeMillis += 1000 * 60 * 60;
        expiration.setTime(expTimeMillis);
        
        expiration  = Instant.now().plus(Duration.standardSeconds(secondsToExpire)).toDate();

        GeneratePresignedUrlRequest generatePresignedUrlRequest = 
                new GeneratePresignedUrlRequest(bucket, key)
                .withMethod(HttpMethod.GET)
                .withExpiration(expiration);
        
        return transferManager.getAmazonS3Client().generatePresignedUrl(generatePresignedUrlRequest);
	}
}
