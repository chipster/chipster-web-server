package fi.csc.chipster.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Build a Config from a yaml string for the tests of the service password
 * checks
 * 
 * Config caches each file after the first read, so each yaml is written to a
 * new file in the test's temporary directory. The Configs ignore the
 * environment variables, see Config(List, boolean).
 */
public class PasswordTestConfig {

	/**
	 * Configuration keys of all passwords that auth checks on startup, sorted
	 * 
	 * Listed explicitly, so that the tests notice when a service is added to or
	 * removed from chipster-defaults.yaml. Add the new service here too.
	 */
	public static final List<String> PASSWORD_KEYS = List.of(
			Config.KEY_MONITORING_PASSWORD,
			"service-password-auth",
			"service-password-backup",
			"service-password-file-broker",
			"service-password-file-storage",
			"service-password-job-history",
			"service-password-s3-storage",
			"service-password-scheduler",
			"service-password-service-locator",
			"service-password-session-db",
			"service-password-session-worker",
			"service-password-toolbox",
			"service-password-type-service",
			"service-password-web-server");

	private static int fileCounter = 0;

	/**
	 * Config that reads only the defaults
	 */
	public static Config defaultsOnly() {
		return new Config(List.of(), false);
	}

	/**
	 * Config that reads the given yaml on top of the defaults
	 */
	public static Config fromYaml(Path tempDir, String yaml) throws IOException {
		Path file = tempDir.resolve("chipster-" + (fileCounter++) + ".yaml");
		Files.writeString(file, yaml);
		return new Config(List.of(file.toString()), false);
	}

	/**
	 * Config that reads the given entries on top of the defaults
	 * 
	 * @param entries keys and yaml values, written as "key: value" lines
	 */
	public static Config fromEntries(Path tempDir, Map<String, String> entries) throws IOException {
		StringBuilder yaml = new StringBuilder();
		for (Map.Entry<String, String> entry : entries.entrySet()) {
			yaml.append(entry.getKey() + ": " + entry.getValue() + "\n");
		}
		return fromYaml(tempDir, yaml.toString());
	}

	/**
	 * Config where every service account and the monitoring account has a
	 * non-default password, except the given keys which get the given values
	 * 
	 * @param overrides pairs of key and yaml value, e.g. "service-password-auth",
	 *                  "\"\""
	 */
	public static Config allPasswordsSet(Path tempDir, String... overrides) throws IOException {
		LinkedHashMap<String, String> entries = new LinkedHashMap<>();
		for (String key : PASSWORD_KEYS) {
			entries.put(key, nonDefaultPassword(key));
		}
		for (int i = 0; i < overrides.length; i += 2) {
			entries.put(overrides[i], overrides[i + 1]);
		}
		return fromEntries(tempDir, entries);
	}

	public static String nonDefaultPassword(String key) {
		return "password-for-" + key;
	}
}
