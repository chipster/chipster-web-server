package fi.csc.chipster.tools.common;

import java.net.URLConnection;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class KeyAndTrustManager {

	/**
	 * Logger for this class
	 */
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();

	private static SocketFactoryCache _trustAllFactoryCache; // use getter for this

	private static String protocol = "TLS";

	public static void configureForTrustAllCertificates(URLConnection connection)
			throws NoSuchAlgorithmException, KeyManagementException {

		if (connection instanceof HttpsURLConnection) {
			((HttpsURLConnection) connection)
					.setSSLSocketFactory(getTrustAllSocketFactoryCache().getSocketFactoryForThisThread());
			((HttpsURLConnection) connection).setHostnameVerifier(new TrustAllHostnameVerifier());
		}
	}

	public static class TrustAllX509TrustManager implements X509TrustManager {
		public X509Certificate[] getAcceptedIssuers() {
			return new X509Certificate[0];
		}

		public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {
			throw new UnsupportedOperationException();
		}

		public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {
		}
	}

	public static class TrustAllHostnameVerifier implements HostnameVerifier {
		public boolean verify(String string, SSLSession ssls) {
			return true;
		}
	}

	private static SocketFactoryCache getTrustAllSocketFactoryCache() {
		if (_trustAllFactoryCache == null) {
			_trustAllFactoryCache = new SocketFactoryCache(new TrustManager[] { new TrustAllX509TrustManager() },
					protocol);
		}
		return _trustAllFactoryCache;
	}
}
