package fi.csc.chipster.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;

import org.junit.jupiter.api.Test;

public class AdminResourceStatParsingTest {

	@Test
	public void parseContainerCpuStats() {
		String stats = "usage_usec 64584235\n" +
				"user_usec 58125397\n" +
				"system_usec 6458838\n" +
				"nice_usec 0\n" +
				"core_sched.force_idle_usec 0\n" +
				"nr_periods 23166\n" +
				"nr_throttled 56\n" +
				"throttled_usec 3536561\n" +
				"nr_bursts 0\n" +
				"burst_usec 0\n";

		HashMap<String, Object> status = new HashMap<>();
		AdminResource.parseContainerCpuStats(stats, status);

		assertEquals(Long.valueOf(58125397), status.get("cpu.user_usec"));

		// for (String key : status.keySet()) {
		// System.out.println(key + ": " + status.get(key));
		// }
	}

	@Test
	public void parseContainerMemoryStats() {
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
		AdminResource.parseContainerMemoryStats(memoryString, mem);

		assertEquals(Long.valueOf(294002688), mem.get("mem.anon"));

		// for (String key : mem.keySet()) {
		// System.out.println(key + ": " + mem.get(key));
		// }

	}

	@Test
	public void parseContainerDiskStats() {

		String diskString = "8:176 rbytes=4096 wbytes=0 rios=1 wios=0 dbytes=0 dios=0\n" +
				"8:0 rbytes=16384 wbytes=8589312 rios=1 wios=2096 dbytes=0 dios=0\n" +
				"5214208 rbytes=5214208 wbytes=4337664 rios=66 wios=905 dbytes=0 dios=0";

		HashMap<String, Object> disk = new HashMap<>();
		AdminResource.parseContainerDiskStats(diskString, disk);

		assertEquals(Long.valueOf(5214208), disk.get("disk.rbytes,dev=5214208"));

		// for (String key : disk.keySet()) {
		// System.out.println(key + ": " + disk.get(key));
		// }
	}

	@Test
	public void parseContainerNetStats() {

		String netString = "Inter-|   Receive                                                |  Transmit\n" +
				" face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n"
				+
				"  eth0: 210692602135 21770865    0    0    0     0          0         0 41905777974 17418185    0    0    0     0       0          0\n"
				+
				"    lo: 10061358324  729772    0    0    0     0          0         0 10061358324  729772    0    0    0     0       0          0\n";

		HashMap<String, Object> net = new HashMap<>();
		AdminResource.parseContainerNetStats(netString, net);

		assertEquals(Long.valueOf(210692602135l), net.get("net.receive.bytes,interface=eth0"));

		// for (String key : net.keySet()) {
		// System.out.println(key + ": " + net.get(key));
		// }
	}
}
