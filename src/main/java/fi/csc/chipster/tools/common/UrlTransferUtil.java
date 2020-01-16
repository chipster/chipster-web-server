package fi.csc.chipster.tools.common;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.LinkedList;
import java.util.List;

public class UrlTransferUtil {

	public static int HTTP_TIMEOUT_MILLISECONDS = 2000;
	
	public static String parseFilename(URL url) {
		int start = url.getPath().contains("/") ? url.getPath().lastIndexOf("/") + 1 : url.getPath().length();
		return url.getPath().substring(start);
	}
    
    public static boolean isSuccessfulCode(int responseCode) {
		return responseCode >= 200 && responseCode < 300; // 2xx => successful
	}
    

    /**
     * Overrides system proxy settings (JVM level) to always bypass the proxy.
     * This method must be called BEFORE any upload URL objects are created.
     * It is required because JRE does not respect at all the proxy parameter
     * given to URL.openConnection(Proxy), which would be the good solution
     * for overriding proxies for uploads.
     * 
     * @see java.net.URL#openConnection(Proxy)
     */
	public static void disableProxies() {

		ProxySelector.setDefault(new ProxySelector() {

			@Override
			public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
				// we are not interested in this
			}

			@Override
			public List<Proxy> select(URI uri) {
                LinkedList<Proxy> proxies = new LinkedList<Proxy>();
                proxies.add(Proxy.NO_PROXY);
                return proxies;
			}
	    	
	    });
	}

	public static HttpURLConnection prepareForUpload(URL url) throws IOException {
		HttpURLConnection connection = (HttpURLConnection)url.openConnection(); // should use openConnection(Proxy.NO_PROXY) if it actually worked
		connection.setRequestMethod("PUT");
		connection.setDoOutput(true);
		return connection;
	}

	
	public static boolean isLocalhost(String host) throws SocketException, UnknownHostException {
		InetAddress address = InetAddress.getByName(host);
		return address.isAnyLocalAddress() || address.isLoopbackAddress();
	}
}
