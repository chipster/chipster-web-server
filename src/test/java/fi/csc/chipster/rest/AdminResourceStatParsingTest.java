package fi.csc.chipster.rest;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;

import org.junit.Test;

public class AdminResourceStatParsingTest {

	
	@Test
	public void parseContainerCpuStats() {
		String stats = "user 12799096\n" + 
				"system 952552\n";
		
		HashMap<String, Object> status = new HashMap<>();
		AdminResource.parseContainerCpuStats(stats, status);
		
		assertEquals(Long.valueOf(12799096), status.get("cpu.user"));
		
//		for (String key : status.keySet()) {
//			System.out.println(key + ": " + status.get(key));
//		}
	}
	
	@Test
	public void parseContainerMemoryStats() {
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
		AdminResource.parseContainerMemoryStats(memoryString, mem);
		
		assertEquals(Long.valueOf(1157156864), mem.get("mem.rss"));
		
//		for (String key : mem.keySet()) {
//			System.out.println(key + ": " + mem.get(key));
//		}
		
	}
	
	@Test
	public void parseContainerDiskStats() {	
		
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
		AdminResource.parseContainerDiskStats(diskString, disk);
		
		assertEquals(Long.valueOf(113679659008l), disk.get("disk.read,dev=253:0"));
		
//		for (String key : disk.keySet()) {
//			System.out.println(key + ": " + disk.get(key));
//		}
	}
		
	@Test
	public void parseContainerNetStats() {
		
		String netString = "Inter-|   Receive                                                |  Transmit\n" + 
				" face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed\n" + 
				"  eth0: 210692602135 21770865    0    0    0     0          0         0 41905777974 17418185    0    0    0     0       0          0\n" + 
				"    lo: 10061358324  729772    0    0    0     0          0         0 10061358324  729772    0    0    0     0       0          0\n";
		
		
		HashMap<String, Object> net = new HashMap<>();
		AdminResource.parseContainerNetStats(netString, net);
		
		assertEquals(Long.valueOf(210692602135l), net.get("net.receive.bytes,interface=eth0"));
		
//		for (String key : net.keySet()) {
//			System.out.println(key + ": " + net.get(key));
//		}
	}
}
