package fi.csc.chipster.rest.hibernate;

import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.util.List;

import javax.net.ssl.SSLContext;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.amazonaws.services.s3.transfer.TransferManager;
import com.amazonaws.services.s3.transfer.TransferManagerBuilder;

import fi.csc.chipster.rest.Config;

/**
 * Utilities for using S3
 * 
 */
public class S3Util {

	private final static Logger logger = LogManager.getLogger();
	private static final String CONF_TLS_VERSION = "tls-version";

	public static TransferManager getTransferManager(String endpoint, String region, String access, String secret,
			String signerOverride, boolean pathStyleAccess) {

		TransferManagerBuilder builder = TransferManagerBuilder.standard()
				.withS3Client(getClient(endpoint, region, access, secret, signerOverride, pathStyleAccess));

		builder.setDisableParallelDownloads(true);

		builder.setMinimumUploadPartSize(4l * 1024 * 1024 * 1024);

		builder.setMultipartUploadThreshold(4l * 1024 * 1024 * 1024);

		return builder.build();

	}

	public static AmazonS3 getClient(String endpoint, String region, String access, String secret,
			String signerOverride, boolean pathStyleAccess) {

		AWSCredentials credentials = new BasicAWSCredentials(access, secret);

		ClientConfiguration clientConfig = new ClientConfiguration();
		// clientConfig.setSignerOverride("S3SignerType");
		clientConfig.setSignerOverride(signerOverride);

		logger.debug("S3 max idle:                 " + clientConfig.getConnectionMaxIdleMillis() + " ms");
		logger.debug("S3 connection timeout:       " + clientConfig.getConnectionTimeout() + " ms");
		logger.debug("S3 connection TTL:           " + clientConfig.getConnectionTTL() + " ms");
		logger.debug("S3 request timeout:          " + clientConfig.getRequestTimeout() + " ms");
		logger.debug("S3 socket timeout:           " + clientConfig.getSocketTimeout() + " ms");
		logger.debug("S3 client execution timeout: " + clientConfig.getClientExecutionTimeout() + " ms");

		AmazonS3 s3 = AmazonS3ClientBuilder.standard()
				.withClientConfiguration(clientConfig)
				// .withEndpointConfiguration(new EndpointConfiguration("object.pouta.csc.fi",
				// "regionOne"))
				.withEndpointConfiguration(new EndpointConfiguration(endpoint, region))
				.withCredentials(new AWSStaticCredentialsProvider(credentials))
				.withPathStyleAccessEnabled(pathStyleAccess)
				.build();

		return s3;
	}

	public static List<S3ObjectSummary> getObjects(TransferManager transferManager, String bucket) {
		AmazonS3 s3 = transferManager.getAmazonS3Client();
		ObjectListing listing = s3.listObjects(bucket);
		List<S3ObjectSummary> summaries = listing.getObjectSummaries();
		while (listing.isTruncated()) {
			listing = s3.listNextBatchOfObjects(listing);
			summaries.addAll(listing.getObjectSummaries());
		}
		return summaries;
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
}
