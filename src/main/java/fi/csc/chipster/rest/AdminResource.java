
package fi.csc.chipster.rest;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import jakarta.annotation.security.RolesAllowed;
import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.CriteriaQuery;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jose.util.IOUtils;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;

@Path("admin")
public class AdminResource {

	public static final String PATH_STATUS = "status";

	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	private List<Class<?>> dbTables;
	private List<StatusSource> statusSources;
	private HashMap<String, File> fileSystems = new HashMap<>();

	@SuppressWarnings({ "unchecked", "rawtypes" }) // Jersey logs a warning if the dbTables is typed
	public AdminResource(HibernateUtil hibernate, List dbTables, StatusSource... stats) {
		this.hibernate = hibernate;
		this.dbTables = dbTables;
		if (stats != null) {
			this.statusSources = Arrays.asList(stats);
		}
		this.fileSystems.put("root", new File("."));

		if (this.hibernate != null) {
			hibernate.getSessionFactory().getStatistics().setStatisticsEnabled(true);
		}
	}

	public AdminResource(StatusSource... stats) {
		this(null, new ArrayList<>(), stats);
	}

	@GET
	@Path("alive")
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public Response getAlive(@Context SecurityContext sc) {
		return Response.ok().build();
	}

	@GET
	@Path(PATH_STATUS)
	@RolesAllowed({ Role.MONITORING, Role.ADMIN })
	@Produces(MediaType.APPLICATION_JSON)
	@Transaction
	public HashMap<String, Object> getStatus(@Context SecurityContext sc) {

		HashMap<String, Object> status = new HashMap<>();

		for (Class<?> table : dbTables) {

			long rowCount = getRowCount(table, hibernate);

			status.put(table.getSimpleName().toLowerCase() + "Count", rowCount);

		}

		if (this.hibernate != null) {
			long openCount = hibernate.getSessionFactory().getStatistics().getSessionOpenCount();
			long closeCount = hibernate.getSessionFactory().getStatistics().getSessionCloseCount();
			status.put("dbSessionOpenCount", openCount);
			status.put("dbSessionCloseCount", closeCount);
			status.put("dbSessionsOpen", openCount - closeCount);
		}

		if (statusSources != null) {
			for (StatusSource src : statusSources) {
				status.putAll(src.getStatus());
			}
		}

		status.putAll(getSystemStats());

		return status;

	}

	public static long getRowCount(Class<?> table, HibernateUtil hibernate) {
		CriteriaBuilder qb = hibernate.session().getCriteriaBuilder();
		CriteriaQuery<Long> cq = qb.createQuery(Long.class);
		cq.select(qb.count(cq.from(table)));
		return hibernate.session().createQuery(cq).getSingleResult();
	}

	public HashMap<String, Object> getSystemStats() {

		HashMap<String, Object> status = new HashMap<>();

		status.put("load", ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage());
		status.put("cores", Runtime.getRuntime().availableProcessors());
		status.put("memoryJvmMax", Runtime.getRuntime().maxMemory());
		status.put("memoryJvmFree", Runtime.getRuntime().freeMemory());

		for (String name : fileSystems.keySet()) {
			collectDiskStats(status, fileSystems.get(name), name);
		}

		collectContainerMemoryStats(status);
		collectContainerDiskStats(status);
		collectContainerNetStats(status);
		collectContainerCpuStats(status);

		return status;
	}

	private static void collectDiskStats(HashMap<String, Object> status, File file, String name) {
		status.put("diskTotal,fs=" + name, file.getTotalSpace());
		status.put("diskFree,fs=" + name, file.getFreeSpace());
	}

	private static Object parseLongOrString(String valueString) {
		try {
			return Long.parseLong(valueString);
		} catch (NumberFormatException e) {
			return valueString;
		}
	}

	private static String fileToString(String path) {
		File file = new File(path);

		if (file.exists()) {
			try {
				return IOUtils.readFileToString(file);

			} catch (IOException e) {
				logger.warn("reading container stats failed", e);
			}
		}
		return null;
	}

	private static void collectContainerMemoryStats(HashMap<String, Object> status) {

		String stats = fileToString("/sys/fs/cgroup/memory/memory.stat");

		if (stats != null) {
			parseContainerMemoryStats(stats, status);
		}
	}

	private static void collectContainerCpuStats(HashMap<String, Object> status) {

		String stats = fileToString("/sys/fs/cgroup/cpuacct/cpuacct.stat");

		if (stats != null) {
			parseContainerCpuStats(stats, status);
		}
	}

	static void parseContainerCpuStats(String stats, HashMap<String, Object> status) {

		String[] rows = stats.split("\n");
		for (String row : rows) {
			String[] cols = row.split(" ");
			if (cols.length == 2) {

				Object value = parseLongOrString(cols[1]);

				status.put("cpu." + cols[0], value);
			}
		}
	}

	static void parseContainerMemoryStats(String stats, HashMap<String, Object> status) {

		String[] rows = stats.split("\n");
		for (String row : rows) {
			String[] cols = row.split(" ");
			if (cols.length == 2) {

				Object value = parseLongOrString(cols[1]);

				status.put("mem." + cols[0], value);
			}
		}
	}

	private static void collectContainerDiskStats(HashMap<String, Object> status) {

		String stats = fileToString("/sys/fs/cgroup/blkio/blkio.throttle.io_service_bytes");

		if (stats != null) {
			parseContainerDiskStats(stats, status);
		}
	}

	static void parseContainerDiskStats(String stats, HashMap<String, Object> status) {

		String[] rows = stats.split("\n");
		for (String row : rows) {
			String[] cols = row.split(" ");
			if (cols.length == 3) {

				String type = cols[1].toLowerCase();
				Object value = parseLongOrString(cols[2]);

				status.put("disk." + type + ",dev=" + cols[0], value);
			}
		}
	}

	private static void collectContainerNetStats(HashMap<String, Object> status) {

		String stats = fileToString("/proc/1/net/dev");

		if (stats != null) {
			parseContainerNetStats(stats, status);
		}
	}

	static void parseContainerNetStats(String statsFile, HashMap<String, Object> status) {

		String[] rows = statsFile.split("\n");

		ArrayList<String> keys = new ArrayList<>();
		if (rows.length > 3) {
			String[] directions = StringUtils.normalizeSpace(rows[0]).split("\\|");
			String[] directionKeyLines = rows[1].split("\\|");

			for (int directionIndex = 1; directionIndex < directions.length; directionIndex++) {
				String direction = directions[directionIndex].toLowerCase().trim();
				String directionKeyLine = StringUtils.normalizeSpace(directionKeyLines[directionIndex]);
				List<String> directionKeys = Arrays.asList(directionKeyLine.split(" "));
				for (String key : directionKeys) {
					keys.add(direction + "." + key);
				}
			}

			for (int rowIndex = 2; rowIndex < rows.length; rowIndex++) {
				String row = rows[rowIndex];
				String[] interfaceAndValues = row.split(":");
				String interfaceString = interfaceAndValues[0].trim();
				String valuesRow = interfaceAndValues[1];

				String[] values = StringUtils.normalizeSpace(valuesRow).split(" ");

				for (int valueIndex = 0; valueIndex < values.length; valueIndex++) {
					String key = keys.get(valueIndex);
					Object value = parseLongOrString(values[valueIndex]);

					status.put("net." + key + ",interface=" + interfaceString, value);
				}
			}
		}
	}

	public void addFileSystem(String name, File dir) {
		this.fileSystems.put(name, dir);
	}

	public static void main(String[] args) {

		System.out.println("** Memory");

		String memoryString = "cache 33280602112\n" +
				"rss 1157156864\n" +
				"rss_huge 566231040\n" +
				"mapped_file 1527808\n" +
				"swap 0\n" +
				"pgpgin 910350605\n" +
				"pgpgout 905237877\n" +
				"pgfault 1858278414\n" +
				"pgmajfault 5048\n" +
				"inactive_anon 0\n" +
				"active_anon 1157124096\n" +
				"inactive_file 930308096\n" +
				"active_file 32350294016\n" +
				"unevictable 0\n" +
				"hierarchical_memory_limit 41875931136\n" +
				"hierarchical_memsw_limit 41875931136\n" +
				"total_cache 33280602112\n" +
				"total_rss 1157156864\n" +
				"total_rss_huge 566231040\n" +
				"total_mapped_file 1527808\n" +
				"total_swap 0\n" +
				"total_pgpgin 910350605\n" +
				"total_pgpgout 905237877\n" +
				"total_pgfault 1858278414\n" +
				"total_pgmajfault 5048\n" +
				"total_inactive_anon 0\n" +
				"total_active_anon 1157124096\n" +
				"total_inactive_file 930308096\n" +
				"total_active_file 32350294016\n" +
				"total_unevictable 0\n";

		HashMap<String, Object> mem = new HashMap<>();
		parseContainerMemoryStats(memoryString, mem);

		for (String key : mem.keySet()) {
			System.out.println(key + ": " + mem.get(key));
		}

		System.out.println("** Disk");

		String diskString = "253:0 Read 113679659008\n" +
				"253:0 Write 22238393344\n" +
				"253:0 Sync 21107835904\n" +
				"253:0 Async 114810216448\n" +
				"253:0 Total 135918052352\n" +
				"8:16 Read 113687285760\n" +
				"8:16 Write 22844026880\n" +
				"8:16 Sync 21713420288\n" +
				"8:16 Async 114817892352\n" +
				"8:16 Total 136531312640\n" +
				"253:1 Read 7168000\n" +
				"253:1 Write 676328448\n" +
				"253:1 Sync 676279296\n" +
				"253:1 Async 7217152\n" +
				"253:1 Total 683496448\n" +
				"Total 273132861440\n";

		HashMap<String, Object> disk = new HashMap<>();
		parseContainerDiskStats(diskString, disk);

		for (String key : disk.keySet()) {
			System.out.println(key + ": " + disk.get(key));
		}

		String netString = "Inter-|   Receive                                                |  Transmit\n" +
				" face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
				+
				"  eth0: 210692602135 21770865    0    0    0     0          0         0 41905777974 17418185    0    0    0     0       0          0\n"
				+
				"    lo: 10061358324  729772    0    0    0     0          0         0 10061358324  729772    0    0    0     0       0          0\n";

		HashMap<String, Object> net = new HashMap<>();
		parseContainerNetStats(netString, net);

		for (String key : net.keySet()) {
			System.out.println(key + ": " + net.get(key));
		}
	}
}
