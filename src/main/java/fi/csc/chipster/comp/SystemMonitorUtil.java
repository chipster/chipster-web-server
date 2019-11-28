package fi.csc.chipster.comp;

import java.io.PrintWriter;
import java.io.StringWriter;

public class SystemMonitorUtil {
	
	public static final String CPU_LOAD = "Load";
	public static final String CPU_CORES = "Cores";
	public static final String CPU_PERCENTS = "Cpu";
	public static final String MEM_USED = "Memory used";
	public static final String MEM_TOTAL = "Memory total";
	public static final String MEM_PERCENTS = "Memory";
	public static final String DISK_USED = "Disk used";
	public static final String DISK_TOTAL = "Disk total";
	public static final String DISK_PERCENTS = "Disk space";

	public static String bytesToMegas(long bytes) {
		float megas = ((float)bytes)/1024f/1024f;
		return round(megas);
	}
	
	public static String bytesToGigas(long bytes) {
		float gigas = ((float)bytes)/1024f/1024f/1024f;
		return round1(gigas);
	}
	
	/**
	 * Round to integer
	 * 
	 * @param f
	 * @return
	 */
	public static String round(float f) {
		StringWriter s = new StringWriter(); 
		new PrintWriter(s).printf("%.0f", f);
		return s.toString();		
	}
	
	/**
	 * Round, keep one decimal
	 * 
	 * @param f
	 * @return
	 */
	public static String round1(float f) {
		StringWriter s = new StringWriter(); 
		new PrintWriter(s).printf("%.1f", f);
		return s.toString();		
	}
	
	public static long getUsed() {
		return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
	}
	
	public static String getMemInfo() {
		long used = getUsed();
		//String totalS = bytesToMegas(Runtime.getRuntime().totalMemory());
		String usedS = bytesToMegas(used);
		String maxS = bytesToMegas(Runtime.getRuntime().maxMemory());
		
		return usedS + "M / " + maxS + "M";
	}
}
