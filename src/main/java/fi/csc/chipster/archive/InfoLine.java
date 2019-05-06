package fi.csc.chipster.archive;

import java.nio.file.Path;
import java.nio.file.Paths;

public class InfoLine {
				
	private Path path;
	private long size;
	private String sha512;
	private Path gpgPath;
	private long gpgSize;
	private String gpgSha512;
	private String backupName;
	
	public InfoLine(Path path, long size, String sha512, Path gpgPath, long gpgSize, String gpgSha512, String backupName) {
		this.path = path;
		this.size = size;
		this.sha512 = sha512;
		this.gpgPath = gpgPath;
		this.gpgSize = gpgSize;
		this.gpgSha512 = gpgSha512;
		this.backupName = backupName;			
	}
	
	public void setBackupName(String backupName) {
		this.backupName = backupName;
	}

	public long getSize() {
		return size;
	}

	public Path getPath() {
		return path;
	}

	public static InfoLine parseLine(String line) {
		String[] split = line.split("\t");
		
		Path path =  Paths.get(split[0]);
		long size = Long.parseLong(split[1]);
		String sha512 = split[2];
		Path gpgPath =  Paths.get(split[3]);
		long gpgSize = Long.parseLong(split[4]);
		String gpgSha512 = split[5];
		String backupName = split[6];
		
		return new InfoLine(path, size, sha512, gpgPath, gpgSize, gpgSha512, backupName);
	}
	
	public String toLine() {
		return path + "\t" + size + "\t" + sha512 + "\t" + gpgPath + "\t" + gpgSize + "\t" + gpgSha512 + "\t" + backupName;
	}

	public String getBackupName() {
		return backupName;
	}

	public String getSha512() {
		return sha512;
	}

	public Path getGpgPath() {
		return gpgPath;
	}

	public long getGpgSize() {
		return gpgSize;
	}

	public String getGpgSha512() {
		return gpgSha512;
	}
}