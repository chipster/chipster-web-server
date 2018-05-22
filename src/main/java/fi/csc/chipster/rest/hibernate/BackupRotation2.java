package fi.csc.chipster.rest.hibernate;

import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.TreeMap;

import org.apache.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.Instant;

import com.amazonaws.services.s3.model.S3ObjectSummary;

/**
 * Deletion logic for backup files
 * 
 * @author klemela
 */
public class BackupRotation2 {
	
	private static final Logger logger = Logger.getLogger(BackupRotation2.class);


	public static <T> TreeMap<Instant, T> getFirstOfEachMonth(TreeMap<Instant, T> files) {
		
		HashSet<DateTime> months = new HashSet<>();
		
		Iterator<Instant> filesIter = files.keySet().iterator();
		
		TreeMap<Instant, T> firstOfEachMonth = new TreeMap<>();
		
		while (filesIter.hasNext()) {
			DateTime fileDate = filesIter.next().toDateTime();
			DateTime month = new DateTime(fileDate.getYear(), fileDate.getMonthOfYear(), 1, 0, 0);
			
			if (!months.contains(month)) {
				firstOfEachMonth.put(fileDate.toInstant(), files.get(fileDate));
				months.add(month);
			}
		}
		
		return firstOfEachMonth;
	}
	
	public static <T> TreeMap<Instant, T> getFirstOfEachDay(TreeMap<Instant, T> files) {
		
		HashSet<DateTime> days = new HashSet<>();
		
		Iterator<Instant> filesIter = files.keySet().iterator();
		
		TreeMap<Instant, T> firstOfEachDay = new TreeMap<>();
		
		while (filesIter.hasNext()) {
			DateTime fileDate = filesIter.next().toDateTime();
			DateTime day = new DateTime(fileDate.getYear(), fileDate.getMonthOfYear(), fileDate.getDayOfMonth(), 0, 0);
			
			if (!days.contains(day)) {
				firstOfEachDay.put(fileDate.toInstant(), files.get(fileDate));
				days.add(day);
			}
		}
		
		return firstOfEachDay;
	}
	
	public static <T> TreeMap<Instant, T> getLast(TreeMap<Instant, T> files, int n) {
		TreeMap<Instant, T> last = new TreeMap<>();
		int i = 0;
		for (Entry<Instant, T> entry : files.descendingMap().entrySet()) {
			if (i < n) {
				last.put(entry.getKey(), entry.getValue());
			}
			i++;
		}
		return last;
	}

	public static TreeMap<Instant, S3ObjectSummary> parse(List<S3ObjectSummary> summaries, String backupPrefix, String backupPostfix) {
		TreeMap<Instant, S3ObjectSummary> files = new TreeMap<>();		
		for (S3ObjectSummary s3Obj : summaries) {
			String key = s3Obj.getKey();
			if (key.startsWith(backupPrefix) && key.endsWith(backupPostfix)) {
				String timestamp = key.replace(backupPrefix, "").replace(backupPostfix, "");
				try {
					Instant instant = Instant.parse(timestamp);
					files.put(instant, s3Obj);
				} catch (IllegalArgumentException e) {
					logger.warn("unparseable timestamp in the backup object: " + key);
				}
			} else {
				logger.warn("unparseable key in the backup bucket: " + key);
			}
		}
		return files;
	}

	public static <T> TreeMap<Instant, T> removeAll(TreeMap<Instant, T> allItems,
		Collection<Instant> keysToDelete) {
		
		TreeMap<Instant, T> filtered = new TreeMap<>(allItems);
		for (Instant key : keysToDelete) {
			filtered.remove(key);
		}
		return filtered;
	}

	public static long getTotalSize(Collection<S3ObjectSummary> summaries) {
		return summaries.stream()
				.map(s -> s.getSize())
				.reduce(0l, Long::sum);
	}
}
