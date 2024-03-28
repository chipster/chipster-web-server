package fi.csc.chipster.filestorage.client;

import java.net.URISyntaxException;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Set;

import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;

public class DnsUtils {

	public static Set<String> getSrvRecords(String service) throws NamingException, URISyntaxException {

		Hashtable<String, String> env = new Hashtable<String, String>();
		env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
		env.put("java.naming.provider.url", "dns:");
		DirContext ctx = new InitialDirContext(env);
		Attributes attrs = ctx.getAttributes(service, new String[] { "SRV" });

		Attribute srv = attrs.get("srv");
		if (srv == null) {
			throw new RuntimeException("no SRV records in DNS for " + service);
		}

		NamingEnumeration<?> servers = srv.getAll();

		Set<String> hosts = new HashSet<String>();

		while (servers.hasMore()) {
			DnsRecord record = DnsRecord.fromString((String) servers.next());
			hosts.add(record.getHost());
		}

		return hosts;
	}

	static class DnsRecord {

		private final int priority;
		private final int weight;
		private final int port;
		private final String host;

		public DnsRecord(int priority, int weight, int port, String host) {
			this.priority = priority;
			this.weight = weight;
			this.port = port;
			// should we keep the dot or not?
			this.host = host.replaceAll("\\\\.$", "");
			;
		}

		public int getPriority() {
			return priority;
		}

		public int getWeight() {
			return weight;
		}

		public int getPort() {
			return port;
		}

		public String getHost() {
			return host;
		}

		public static DnsRecord fromString(String input) {
			String[] splitted = input.split(" ");
			return new DnsRecord(
					Integer.parseInt(splitted[0]),
					Integer.parseInt(splitted[1]),
					Integer.parseInt(splitted[2]),
					splitted[3]);
		}
	}
}
