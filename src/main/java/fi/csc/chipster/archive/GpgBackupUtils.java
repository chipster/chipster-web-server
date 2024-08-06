package fi.csc.chipster.archive;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import fi.csc.chipster.rest.Config;
import fi.csc.chipster.rest.ProcessUtils;
import fi.csc.chipster.rest.hibernate.ChipsterS3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

public class GpgBackupUtils {

	private static final String ENV_GPG_PASSPHRASE = "GPG_PASSPHRASE";

	private final static Logger logger = LogManager.getLogger();

	private static final String CONF_BACKUP_BUCKET = "backup-bucket";
	private static final String CONF_BACKUP_S3_SECRET_KEY = "backup-s3-secret-key";
	private static final String CONF_BACKUP_S3_ACCESS_KEY = "backup-s3-access-key";
	private static final String CONF_BACKUP_S3_REGION = "backup-s3-region";
	private static final String CONF_BACKUP_S3_ENDPOINT = "backup-s3-endpoint";
	private static final String CONF_BACKUP_S3_PATH_STYLE_ACCESS = "backup-s3-path-style-access";

	public static final String CONF_BACKUP_TIME = "backup-time";
	public static final String CONF_BACKUP_INTERVAL = "backup-interval";

	public static final String CONF_BACKUP_GPG_PASSPHRASE = "backup-gpg-passphrase";
	public static final String CONF_BACKUP_GPG_PUBLIC_KEY = "backup-gpg-public-key";
	public static final String CONF_BACKUP_GPG_PROGRAM = "backup-gpg-program";

	public static Map<Path, InfoLine> infoFileToMap(ChipsterS3Client s3Client, String bucket, String key,
			Path tempDir)
			throws InterruptedException, IOException, ArchiveException {

		Path infoPath = tempDir.resolve(key);

		s3Client.downloadFile(bucket, key, infoPath.toFile());

		try {
			Map<Path, InfoLine> map = (HashMap<Path, InfoLine>) Files.lines(infoPath)
					.map(line -> InfoLine.parseLine(line))
					.collect(Collectors.toMap(info -> info.getPath(), info -> info));
			Files.delete(infoPath);

			return map;

		} catch (IllegalStateException e) {
			throw new ArchiveException("failed to parse " + bucket + "/" + key, e);
		}
	}

	public static void backupFileAsTar(String name, Path storage, Path file, Path backupDir,
			ChipsterS3Client s3Client, String bucket, String backupName, Path backupInfoPath, String recipient,
			String gpgPassphrase, Config config) throws IOException, InterruptedException {
		backupFilesAsTar(name, storage, Collections.singleton(file), backupDir, s3Client, bucket, backupName,
				backupInfoPath, recipient, gpgPassphrase, config);
	}

	private static String getGpgProgram(Config config) {
		return ProcessUtils.getPath(config.getString(CONF_BACKUP_GPG_PROGRAM));
	}

	public static void backupFilesAsTar(String name, Path storage, Set<Path> files, Path backupDir,
			ChipsterS3Client s3Client, String bucket, String backupName, Path backupInfoPath, String recipient,
			String gpgPassphrase, Config config) throws IOException, InterruptedException {

		Path tarPath = backupDir.resolve(name + ".tar");

		// make sure there is no old tar file, because we would append to it
		if (Files.exists(tarPath)) {
			logger.warn(tarPath + " exists already");
			Files.delete(tarPath);
		}

		for (Path packageFilePath : files) {

			Path packagePath = null;

			if (recipient != null || gpgPassphrase != null) {
				packagePath = getPackageGpgPath(packageFilePath);
			} else {
				packagePath = getPackageLz4Path(packageFilePath);
			}

			Path localFilePath = storage.resolve(packageFilePath);
			Path localPackagePath = backupDir.resolve(packagePath);

			long fileSize;
			String sha512;

			try {
				// checksum of the original file to be checked after restore
				// file read once, cpu bound
				sha512 = parseChecksumLine(
						ProcessUtils.runStdoutToString(null, "shasum", "-a", "512", localFilePath.toString()));
				fileSize = Files.size(localFilePath);
			} catch (RuntimeException e) {
				if (!Files.exists(localFilePath)) {
					logger.error("file disappeared during the backup process: " + e.getMessage()
							+ " (probably deleted by some user)");
					continue;
				} else {
					throw e;
				}
			}

			// compress and encrypt
			// file read and written once, cpu bound (shell pipe saves one write and read)
			String cmd = "";
			Map<String, String> env = new HashMap<String, String>();

			cmd += ProcessUtils.getPath("lz4") + " -q -c " + localFilePath.toString();

			if (recipient != null || gpgPassphrase != null) {
				cmd += " | " + getGpgProgram(config) + " --output - --compress-algo none --no-tty ";

				// don't set null value, because the map returned by
				// ProcessBuilder.getEnvironment() won't tolerate it
				if (gpgPassphrase != null) {
					env.put(ENV_GPG_PASSPHRASE, gpgPassphrase);
				}

				if (recipient != null) {

					// asymmetric encryption
					cmd += "--recipient " + recipient + " --always-trust --encrypt -";
				} else if (gpgPassphrase != null) {
					/*
					 * Symmetric encryption
					 * 
					 * Try to hide the passphrase from the process list in case this runs in
					 * multiuser
					 * system (container is safe anyway).
					 * - echo is not visible in the process list because it's a builtin
					 * - process substitution <() creates a anonymous pipe, where the content is not
					 * visible in the process list
					 */
					cmd += "--passphrase-file <(echo $" + ENV_GPG_PASSPHRASE + ") ";
					cmd += "--pinentry-mode loopback ";
					cmd += "--symmetric -";
				}
			}

			Files.createDirectories(localPackagePath.getParent());
			ProcessUtils.run(null, localPackagePath.toFile(), env, false, "bash", "-c", cmd);

			long gpgFileSize = Files.size(localPackagePath);

			// checksum of the compressed and encrypted file to monitor bit rot on the
			// backup server
			// file read and written once, cpu bound
			String gpgSha512 = parseChecksumLine(
					ProcessUtils.runStdoutToString(null, "shasum", "-a", "512", localPackagePath.toString()));

			// use --directory to convert paths to relative
			// file read and written once, io bound. Unfortunately tar can't read the file
			// data from stdin
			ProcessUtils.run(null, null, "tar", "-f", tarPath.toString(), "--directory", backupDir.toString(),
					"--append", packagePath.toString());

			Files.delete(localPackagePath);

			String line = new InfoLine(packageFilePath, fileSize, sha512, packagePath, gpgFileSize, gpgSha512,
					backupName).toLine();
			Files.write(backupInfoPath, Collections.singleton(line), Charset.defaultCharset(),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}

		upload(s3Client, bucket, backupName, tarPath, true);

		Files.delete(tarPath);
	}

	private static void upload(ChipsterS3Client s3Client, String bucket, String bucketDir, Path filePath,
			boolean verbose) throws IOException, InterruptedException {
		if (verbose) {
			logger.info("upload " + filePath.getFileName() + " to " + bucket + "/" + bucketDir + " ("
					+ FileUtils.byteCountToDisplaySize(Files.size(filePath)) + ")");
		}

		s3Client.uploadFile(bucket, bucketDir + "/" + filePath, filePath);
	}

	public static Path getPackageGpgPath(Path packageFilePath) {
		return Paths.get(packageFilePath.toString() + ".lz4.gpg");
	}

	public static Path getPackageLz4Path(Path packageFilePath) {
		return Paths.get(packageFilePath.toString() + ".lz4");
	}

	private static String parseChecksumLine(String line) throws IOException {
		String[] parts = line.split(" ");
		if (parts.length == 0) {
			throw new IllegalArgumentException("cannot parse checksum " + line + ", delimiter ' ' not found");
		}
		return parts[0];
	}

	public static void uploadBackupInfo(ChipsterS3Client s3Client, String bucket, String backupName,
			Path backupInfoPath) throws InterruptedException, FileNotFoundException, IOException {

		s3Client.uploadFile(bucket, backupName + "/" + BackupArchive.BACKUP_INFO, backupInfoPath);
	}

	public static ChipsterS3Client getS3Client(Config config, String role) {

		String endpoint = config.getString(CONF_BACKUP_S3_ENDPOINT, role);
		String region = config.getString(CONF_BACKUP_S3_REGION, role);
		String access = config.getString(CONF_BACKUP_S3_ACCESS_KEY, role);
		String secret = config.getString(CONF_BACKUP_S3_SECRET_KEY, role);
		boolean pathStyleAccess = config.getBoolean(CONF_BACKUP_S3_PATH_STYLE_ACCESS, role);

		if (endpoint == null || endpoint.isEmpty() ||
				region == null || region.isEmpty() ||
				access == null || access.isEmpty() ||
				secret == null || secret.isEmpty()) {
			logger.warn("backups are not configured");
			return null;
		}

		return new ChipsterS3Client(endpoint, region, access, secret, pathStyleAccess);
	}

	public static String getBackupBucket(Config config, String role) {
		return config.getString(CONF_BACKUP_BUCKET, role);
	}

	public static Timer startBackupTimer(TimerTask timerTask, Config config) {

		int backupInterval = Integer.parseInt(config.getString(CONF_BACKUP_INTERVAL));
		String backupTimeString = config.getString(CONF_BACKUP_TIME);

		int startHour = Integer.parseInt(backupTimeString.split(":")[0]);
		int startMinute = Integer.parseInt(backupTimeString.split(":")[1]);
		Calendar firstBackupTime = Calendar.getInstance();
		if (firstBackupTime.get(Calendar.HOUR_OF_DAY) > startHour ||
				(firstBackupTime.get(Calendar.HOUR_OF_DAY) == startHour &&
						firstBackupTime.get(Calendar.MINUTE) >= startMinute)) {
			firstBackupTime.add(Calendar.DATE, 1);
		}
		firstBackupTime.set(Calendar.HOUR_OF_DAY, startHour);
		firstBackupTime.set(Calendar.MINUTE, startMinute);
		firstBackupTime.set(Calendar.SECOND, 0);
		firstBackupTime.set(Calendar.MILLISECOND, 0);
		logger.info("next backup is scheduled at " + firstBackupTime.getTime().toString());

		Timer backupTimer = new Timer();
		backupTimer.scheduleAtFixedRate(timerTask, firstBackupTime.getTime(), backupInterval * 60 * 60 * 1000);
		return backupTimer;
	}

	public static String importPublicKey(Config config, String role) throws IOException, InterruptedException {

		String gpgPublicKey = config.getString(GpgBackupUtils.CONF_BACKUP_GPG_PUBLIC_KEY, role);
		if (gpgPublicKey.isEmpty()) {
			logger.warn(CONF_BACKUP_GPG_PUBLIC_KEY + " is not configured");
			return null;
		}

		logger.info("import gpg public key");
		String cmd = "";
		cmd += "echo -e '" + gpgPublicKey + "' | ";
		cmd += getGpgProgram(config) + " ";
		cmd += "--import";

		ProcessUtils.run(null, null, null, true, "bash", "-c", cmd);

		logger.info("get the recipient from the public key");
		cmd = "";
		cmd += "echo -e '" + gpgPublicKey + "' | ";
		cmd += getGpgProgram(config) + " ";
		cmd += "--list-packets";

		String output = ProcessUtils.runStdoutToString(null, "bash", "-c", cmd);

		Optional<String> uidLineOptional = Arrays.asList(output.split("\n")).stream()
				.filter(line -> line.startsWith(":user ID packet:"))
				.findAny();

		if (!uidLineOptional.isPresent()) {
			throw new IllegalArgumentException("gpg public key user ID packet not found: " + output);
		}

		String recipient;
		try {
			recipient = uidLineOptional.get().substring(uidLineOptional.get().indexOf("<") + 1,
					uidLineOptional.get().indexOf(">"));
		} catch (IndexOutOfBoundsException e) {
			throw new IllegalArgumentException("gpg public key < or > not found from line: " + uidLineOptional.get());
		}

		if (recipient.isEmpty()) {
			throw new IllegalArgumentException("gpg public key email is empty: " + uidLineOptional);
		}

		logger.info("gpg recipient " + recipient);
		return recipient;
	}

	public static String findLatest(List<S3Object> objects, String backupNamePrefix, String fileName) {
		List<String> fileBrokerBackups = objects.stream()
				.map(o -> o.key())
				.filter(name -> name.startsWith(backupNamePrefix))
				// only completed backups (the file info list uploaded in the end)
				.filter(name -> name.endsWith("/" + fileName))
				// this compares strings, but luckily it works with this timestamp format from
				// Instant.toString()
				.sorted()
				.collect(Collectors.toList());

		if (fileBrokerBackups.isEmpty()) {
			return null;
		}

		// return latest
		return fileBrokerBackups.get(fileBrokerBackups.size() - 1);
	}

	public static Instant getLatestArchive(ChipsterS3Client s3Client, String backupNamePrefix, String bucket) {
		List<S3Object> objects = s3Client.getObjects(bucket);

		String archiveInfoKey = findLatest(objects, backupNamePrefix, BackupArchive.ARCHIVE_INFO);

		if (archiveInfoKey != null) {
			String archiveName = archiveInfoKey.substring(0, archiveInfoKey.indexOf("/"));
			String backupTimeString = archiveName.substring(backupNamePrefix.length());
			return Instant.parse(backupTimeString);
		}
		return null;
	}
}
