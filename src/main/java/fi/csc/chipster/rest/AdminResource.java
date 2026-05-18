
package fi.csc.chipster.rest;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.nimbusds.jose.util.IOUtils;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.hibernate.HibernateUtil;
import fi.csc.chipster.rest.hibernate.Transaction;
import jakarta.annotation.security.RolesAllowed;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;

@Path("admin")
public class AdminResource {

	public static final String PATH_STATUS = "status";

	private static Logger logger = LogManager.getLogger();

	private HibernateUtil hibernate;

	private List<Class<?>> dbTables;
	private List<StatusSource> statusSources;
	private HashMap<String, File> fileSystems = new HashMap<>();

	@SuppressWarnings({ "unchecked", "rawtypes" }) // Jersey logs a warning if the dbTables is typed
	public AdminResource(HibernateUtil hibernate, List dbTables, Config config,
			StatusSource... stats) {
		this.hibernate = hibernate;
		this.dbTables = dbTables;
		if (stats != null) {
			this.statusSources = new ArrayList(Arrays.asList(stats));
		} else {
			this.statusSources = new ArrayList<StatusSource>();
		}
		this.fileSystems.put("root", new File("."));

		if (this.hibernate != null) {
			hibernate.getSessionFactory().getStatistics().setStatisticsEnabled(true);
		}

		this.statusSources.add(new BuildVersionStatusSource(config));
	}

	public AdminResource(Config config, StatusSource... stats) {
		this(null, new ArrayList<>(), config, stats);
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

		status.put("mem.max", parseLongOrString(fileToString("/sys/fs/cgroup/memory.max")));
		status.put("mem.current", parseLongOrString(fileToString("/sys/fs/cgroup/memory.current")));
		status.put("mem.peak", parseLongOrString(fileToString("/sys/fs/cgroup/memory.peak")));
		status.put("mem.swap.current", parseLongOrString(fileToString("/sys/fs/cgroup/memory.swap.current")));

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

		String stats = fileToString("/sys/fs/cgroup/memory.stat");

		if (stats != null) {
			parseContainerMemoryStats(stats, status);
		}
	}

	private static void collectContainerCpuStats(HashMap<String, Object> status) {

		String stats = fileToString("/sys/fs/cgroup/cpu.stat");

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

		String stats = fileToString("/sys/fs/cgroup/io.stat");

		if (stats != null) {
			parseContainerDiskStats(stats, status);
		}
	}

	static void parseContainerDiskStats(String stats, HashMap<String, Object> status) {

		String[] rows = stats.split("\n");
		for (String row : rows) {
			List<String> cols = new LinkedList<>(Arrays.asList(row.split(" ")));

			if (!cols.isEmpty()) {
				String dev = cols.removeFirst();
				for (String col : cols) {
					String[] entry = col.split("=");
					if (entry.length == 2) {
						String key = entry[0];
						Object value = parseLongOrString(entry[1]);

						status.put("disk." + key + ",dev=" + dev, value);
					}
				}
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

		String memoryString = "anon 294002688\n" +
				"file 5816320\n" +
				"kernel 3596288\n" +
				"kernel_stack 753664\n" +
				"pagetables 1228800\n" +
				"sec_pagetables 0\n" +
				"percpu 4248\n" +
				"sock 0\n" +
				"vmalloc 86016\n" +
				"shmem 0\n" +
				"zswap 0\n" +
				"zswapped 0\n" +
				"file_mapped 32768\n" +
				"file_dirty 4096\n" +
				"file_writeback 0\n" +
				"swapcached 0\n" +
				"anon_thp 213909504\n" +
				"file_thp 0\n" +
				"shmem_thp 0\n" +
				"inactive_anon 0\n" +
				"active_anon 293978112\n" +
				"inactive_file 4505600\n" +
				"active_file 1310720\n" +
				"unevictable 0\n" +
				"slab_reclaimable 581752\n" +
				"slab_unreclaimable 756784\n" +
				"slab 1338536\n" +
				"workingset_refault_anon 0\n" +
				"workingset_refault_file 0\n" +
				"workingset_activate_anon 0\n" +
				"workingset_activate_file 0\n" +
				"workingset_restore_anon 0\n" +
				"workingset_restore_file 0\n" +
				"workingset_nodereclaim 0\n" +
				"pgscan 0\n" +
				"pgsteal 0\n" +
				"pgscan_kswapd 0\n" +
				"pgscan_direct 0\n" +
				"pgscan_khugepaged 0\n" +
				"pgsteal_kswapd 0\n" +
				"pgsteal_direct 0\n" +
				"pgsteal_khugepaged 0\n" +
				"pgfault 172506\n" +
				"pgmajfault 6\n" +
				"pgrefill 0\n" +
				"pgactivate 0\n" +
				"pgdeactivate 0\n" +
				"pglazyfree 0\n" +
				"pglazyfreed 0\n" +
				"zswpin 0\n" +
				"zswpout 0\n" +
				"zswpwb 0\n" +
				"thp_fault_alloc 27\n" +
				"thp_collapse_alloc 86\n" +
				"thp_swpout 0\n" +
				"thp_swpout_fallback 0";

		HashMap<String, Object> mem = new HashMap<>();
		parseContainerMemoryStats(memoryString, mem);

		for (String key : mem.keySet()) {
			System.out.println(key + ": " + mem.get(key));
		}

		System.out.println("** CPU");

		String cpuString = "usage_usec 64584235\n" +
				"user_usec 58125397\n" +
				"system_usec 6458838\n" +
				"nice_usec 0\n" +
				"core_sched.force_idle_usec 0\n" +
				"nr_periods 23166\n" +
				"nr_throttled 56\n" +
				"throttled_usec 3536561\n" +
				"nr_bursts 0\n" +
				"burst_usec 0\n";

		HashMap<String, Object> cpu = new HashMap<>();
		parseContainerCpuStats(cpuString, cpu);
		for (String key : cpu.keySet()) {
			System.out.println(key + ": " + cpu.get(key));
		}

		System.out.println("** Disk");

		String diskString = "8:176 rbytes=4096 wbytes=0 rios=1 wios=0 dbytes=0 dios=0\n" +
				"8:0 rbytes=16384 wbytes=8589312 rios=1 wios=2096 dbytes=0 dios=0\n" +
				"8:16 rbytes=5214208 wbytes=4337664 rios=66 wios=905 dbytes=0 dios=0";

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
