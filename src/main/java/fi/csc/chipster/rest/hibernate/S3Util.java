package fi.csc.chipster.rest.hibernate;

import java.net.URI;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import javax.net.ssl.SSLContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.Config;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsResponse;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.transfer.s3.S3TransferManager;
import software.amazon.awssdk.transfer.s3.model.DownloadFileRequest;
import software.amazon.awssdk.transfer.s3.model.FileDownload;
import software.amazon.awssdk.transfer.s3.model.FileUpload;
import software.amazon.awssdk.transfer.s3.model.UploadFileRequest;

/**
 * Utilities for using S3
 * 
 */
public class S3Util {

	private final static Logger logger = LogManager.getLogger();
	private static final String CONF_TLS_VERSION = "tls-version";

	public static S3TransferManager getTransferManager(S3AsyncClient s3AsyncClient) {

		S3TransferManager tm = S3TransferManager.builder()
				.s3Client(s3AsyncClient)
				.build();

		// builder.setDisableParallelDownloads(true);

		return tm;

	}

	public static S3AsyncClient getClient(String endpoint, String region, String access, String secret,
			String signerOverride, boolean pathStyleAccess) {

		AwsCredentials credentials = AwsBasicCredentials.create(access, secret);

		// default timeouts in
		// https://github.com/aws/aws-sdk-java-v2/blob/a0c8a0af1fa572b16b5bd78f310594d642324156/http-client-spi/src/main/java/software/amazon/awssdk/http/SdkHttpConfigurationOption.java#L134

		// S3AsyncClient s3 = S3AsyncClient.crtBuilder()
		// .region(Region.of(region))
		// .endpointOverride(URI.create(endpoint))
		// .credentialsProvider(StaticCredentialsProvider.create(credentials))
		// .forcePathStyle(pathStyleAccess)
		// .minimumPartSizeInBytes(4l * 1024 * 1024 * 1024)
		// .thresholdInBytes(4l * 1024 * 1024 * 1024)
		// .build();

		S3AsyncClient s3 = S3AsyncClient.builder()
				.region(Region.of(region))
				.endpointOverride(URI.create(endpoint))
				.credentialsProvider(StaticCredentialsProvider.create(credentials))
				.forcePathStyle(pathStyleAccess)
				// .minimumPartSizeInBytes(4l * 1024 * 1024 * 1024)
				// .thresholdInBytes(4l * 1024 * 1024 * 1024)
				.build();

		// S3AsyncClient.builder()
		// .serviceConfiguration(
		// S3Configuration.builder()
		// .chunkedEncodingEnabled(true)
		// .build())
		// .endpointOverride(new URI(endpoint))
		// .region(Region.US_EAST_1)
		// .overrideConfiguration(
		// ClientOverrideConfiguration.builder()
		// .putAdvancedOption(SdkAdvancedClientOption.SIGNER, AwsS3V4Signer.create())
		// .executionAttributes(ExecutionAttributes.builder()
		// .put(S3SignerExecutionAttribute.ENABLE_PAYLOAD_SIGNING, true).build())
		// .build())
		// .build();

		return s3;
	}

	public static List<S3Object> getObjects(S3AsyncClient s3Client, String bucket) {

		ListObjectsRequest listObjects = ListObjectsRequest
				.builder()
				.bucket(bucket)
				.build();

		CompletableFuture<ListObjectsResponse> res = s3Client.listObjects(listObjects);
		return res.join().contents();
	}

	/**
	 * Configure TLS version
	 * 
	 * Option for disabling TLSv1.3, because downloads from ha-proxy (a3s.fi) fail
	 * at key update after 128 GiB.
	 * 
	 * Unfortunately AWS SDK expects this to be changed globally:
	 * https://docs.aws.amazon.com/sdk-for-java/latest/developer-guide/security-java-tls.html
	 * .
	 * 
	 * This has to be called early enough (before any use of SSLContext?) to have an
	 * effect.
	 * 
	 * @param config
	 * @param role
	 * @throws KeyManagementException
	 * @throws NoSuchAlgorithmException
	 */
	public static void configureTLSVersion(Config config, String role)
			throws KeyManagementException, NoSuchAlgorithmException {

		String tlsVersion = config.getString(CONF_TLS_VERSION, role);

		if (tlsVersion != null && !tlsVersion.isEmpty()) {

			logger.info("configure TLS version " + tlsVersion);

			// start with -Djavax.net.debug=ssl:handshake to check results
			System.setProperty("jdk.tls.client.protocols", tlsVersion);
		}
	}

	public static void checkTLSVersion(Config config, String role) throws NoSuchAlgorithmException {

		String configuredTlsVersion = config.getString(CONF_TLS_VERSION, role);
		String[] enabledVersions = SSLContext.getDefault().getDefaultSSLParameters().getProtocols();

		String joindedEnabledVersions = String.join(",", enabledVersions);

		// doesn't care about array order. May show error if multiple versions are
		// configured
		if (configuredTlsVersion != null && !configuredTlsVersion.isEmpty()
				&& !configuredTlsVersion.equals(joindedEnabledVersions)) {

			logger.error("TLS version was configured too late (most likely this has to be fixed in source code)");
			logger.error("TLS versions in configuration: " + configuredTlsVersion);
			logger.error("TLS versions in use:           " + joindedEnabledVersions);
			try {
				// make error more visible
				Thread.sleep(10_000);
			} catch (InterruptedException e) {
				// don't care
			}
		}
	}

	public static FileUpload uploadFileAsync(S3TransferManager s3TransferManager, String bucket, String key,
			Path source) {
		UploadFileRequest uploadFileRequest = UploadFileRequest.builder()
				.putObjectRequest(req -> req.bucket(bucket).key(key))
				.source(source)
				.build();

		return s3TransferManager.uploadFile(uploadFileRequest);
	}

	public static void uploadFile(S3TransferManager s3TransferManager, String bucket, String key, Path source) {

		uploadFileAsync(s3TransferManager, bucket, key, source).completionFuture().join();
	}

	public static FileDownload downloadFileAsync(S3TransferManager s3TransferManager, String bucket, String key,
			Path destination) {
		DownloadFileRequest downloadFileRequest = DownloadFileRequest.builder()
				.getObjectRequest(req -> req.bucket(bucket).key(key))
				.destination(destination)
				.build();

		return s3TransferManager.downloadFile(downloadFileRequest);
	}

	public static void downloadFile(S3TransferManager s3TransferManager, String bucket, String key, Path destination) {

		downloadFileAsync(s3TransferManager, bucket, key, destination).completionFuture().join();
	}

	public static void deleteObject(S3AsyncClient client, String bucket, String key) {
		DeleteObjectRequest deleteObjectRequest = DeleteObjectRequest.builder()
				.bucket(bucket)
				.key(key)
				.build();

		CompletableFuture<DeleteObjectResponse> deletion = client.deleteObject(deleteObjectRequest);

		deletion.join();
	}

	public static boolean exists(S3AsyncClient client, String bucket) {
		HeadBucketRequest headBucketRequest = HeadBucketRequest.builder()
				.bucket(bucket)
				.build();

		try {
			client.headBucket(headBucketRequest).join();
			return true;
		} catch (NoSuchBucketException e) {
			return false;
		}
	}

	public static boolean exists(S3AsyncClient client, String bucket, String key) {
		try {

			HeadObjectRequest headObjectRequest = HeadObjectRequest.builder()
					.bucket(bucket)
					.key(key)
					.build();

			client.headObject(headObjectRequest).join();
			return true;
		} catch (NoSuchKeyException e) {
			return false;
		}
	}
}
