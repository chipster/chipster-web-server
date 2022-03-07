package fi.csc.chipster.sessionworker;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Timer;
import java.util.TimerTask;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Measure if a user has made more than allowed number of requests in a given duration
 * 
 * The maximum number of timestamps stored for each user is limited by the countLimit, so
 * the memory usage of each user is limited. Obsolete records are deleted by a timer.
 * 
 * All methods are thread-safe after the constructor, but the crude synchronization may limit performance.
 * 
 * @author klemela
 */
public class RequestThrottle {
	
	@SuppressWarnings("unused")
	private static Logger logger = LogManager.getLogger();
	
	public static final String HEADER_RETRY_AFTER = "Retry-After";
	
	HashMap<String, LinkedList<Instant>> usernameMap = new HashMap<>();
	private Duration durationLimit;
	private long requestLimit;
		
	/**
	 * @param durationLimit Duration of the sliding window
	 * @param countLimit Maximum allowed number of requests during the given duration 
	 */
	public RequestThrottle(Duration durationLimit, long countLimit) {
		this.durationLimit = durationLimit;
		this.requestLimit = countLimit;		
		
		new Timer(true).scheduleAtFixedRate(
				new CleanUpTask(), 
				this.durationLimit.toMillis(), 
				this.durationLimit.toMillis());
	}
	
	/**
	 * Check whether the user has exceeded her rate limit
	 * 
	 * The return value tells how long the client should wait when the limit is exceeded.
	 * In the normal situation this should return Duration.ZERO and the request can be handled right away. 
	 * 
	 * @param username
	 * @return Duration.ZERO when the request is allowed or positive Duration if the limit is exceeded 
	 */
	public Duration throttle(String username) {
		synchronized (usernameMap) {			
			if (usernameMap.get(username) == null) {
				usernameMap.put(username, new LinkedList<Instant>());			
			}
			LinkedList<Instant> requests = usernameMap.get(username);
			
			// add the current time to the end of the list
			requests.add(Instant.now());
				
			// allow the list to grow until the countLimit is reached
			if (requests.size() > this.requestLimit) {
				
				// keep the list size in countLimit (max memory consumption per user)
				requests.removeFirst();

				// if the oldest request is newer than the durationLimit, user has exceeded her limit				
				Duration ageOfOldest = Duration.between(requests.getFirst(), Instant.now());
				Duration retryAfter = durationLimit.minus(ageOfOldest);
				if (!retryAfter.isNegative()) {
					return retryAfter;
				}
			}
			// request allowed
			return Duration.ZERO;
		}
	}
	
	public class CleanUpTask extends TimerTask {
		@Override
		public void run() {
			synchronized (usernameMap) {				
				Iterator<String> iter = usernameMap.keySet().iterator();
				
				while (iter.hasNext()) {
					String username = iter.next();
					
					LinkedList<Instant> requests = usernameMap.get(username);
					
					// the user's latest request is older than durationLimit, these
					// won't affect the new requests and can be removed
					if (requests.getLast().isBefore(Instant.now().minus(durationLimit))) {
						iter.remove();
					}
				}
			}
		}		
	}

	public int getUsernameCount() {
		synchronized (usernameMap) {
			return usernameMap.size();
		}
	}
}
