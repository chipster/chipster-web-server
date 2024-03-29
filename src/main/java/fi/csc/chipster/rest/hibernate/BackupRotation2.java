package fi.csc.chipster.rest.hibernate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map.Entry;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.function.Function;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Deletion logic for backup files
 * 
 * @author klemela
 */
public class BackupRotation2 {

	private static Logger logger = LogManager.getLogger();

	public static <T> TreeMap<Instant, T> getFirstOfEachMonth(TreeMap<Instant, T> files) {

		HashSet<LocalDate> months = new HashSet<>();

		Iterator<Instant> filesIter = files.keySet().iterator();

		TreeMap<Instant, T> firstOfEachMonth = new TreeMap<>();

		while (filesIter.hasNext()) {
			Instant fileInstant = filesIter.next();
			LocalDate fileDate = getLocalDate(fileInstant);
			LocalDate month = LocalDate.of(fileDate.getYear(), fileDate.getMonthValue(), 1);

			if (!months.contains(month)) {
				firstOfEachMonth.put(fileInstant, files.get(fileInstant));
				months.add(month);
			}
		}

		return firstOfEachMonth;
	}

	public static LocalDate getLocalDate(Instant instant) {
		return instant.atZone(TimeZone.getDefault().toZoneId()).toLocalDate();
	}

	public static <T> TreeMap<Instant, T> getFirstOfEachDay(TreeMap<Instant, T> files) {

		HashSet<LocalDate> days = new HashSet<>();

		Iterator<Instant> filesIter = files.keySet().iterator();

		TreeMap<Instant, T> firstOfEachDay = new TreeMap<>();

		while (filesIter.hasNext()) {
			Instant fileInstant = filesIter.next();
			LocalDate fileDate = getLocalDate(fileInstant);
			LocalDate day = LocalDate.of(fileDate.getYear(), fileDate.getMonthValue(), fileDate.getDayOfMonth());

			if (!days.contains(day)) {
				firstOfEachDay.put(fileInstant, files.get(fileInstant));
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

	public static <T> TreeMap<Instant, T> getNewerThan(TreeMap<Instant, T> files, Instant reference) {

		TreeMap<Instant, T> newer = new TreeMap<>();

		for (Entry<Instant, T> entry : files.descendingMap().entrySet()) {
			if (entry.getKey().isAfter(reference)) {
				newer.put(entry.getKey(), entry.getValue());
			}
		}
		return newer;
	}

	public static <T> TreeMap<Instant, T> parse(List<T> summaries, String backupPrefix,
			Function<T, String> nameFunction) {
		TreeMap<Instant, T> files = new TreeMap<>();
		for (T s3Obj : summaries) {
			String key = nameFunction.apply(s3Obj);
			if (key.startsWith(backupPrefix)) {
				String timestamp = key.substring(backupPrefix.length());
				try {
					Instant instant = Instant.parse(timestamp);
					files.put(instant, s3Obj);
				} catch (IllegalArgumentException e) {
					logger.warn("unparseable timestamp in the backup object: " + key);
				}
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
}
