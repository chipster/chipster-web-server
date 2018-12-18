package sessionworker;

import java.time.Duration;

import org.junit.Assert;
import org.junit.Test;

import fi.csc.chipster.sessionworker.RequestThrottle;

public class RequestThrottleTest {
	
	@Test
	public void testLimits() throws InterruptedException {
		
		long limitDuration = 100; // ms
		
		RequestThrottle counter = new RequestThrottle(Duration.ofMillis(limitDuration), 3);
		
		String username1 = "user1";
		String username2 = "user2";
		// user1 makes requests
		Assert.assertEquals(true, counter.throttle(username1).isZero());
		Assert.assertEquals(1, counter.getUsernameCount());
		Assert.assertEquals(true, counter.throttle(username1).isZero());
		Assert.assertEquals(true, counter.throttle(username1).isZero());
		// user1 limit reached
		Assert.assertEquals(false, counter.throttle(username1).isZero());
		// doesn't affect user2
		Assert.assertEquals(true, counter.throttle(username2).isZero());
		
		// after a while user1 is allowed to continue
		Thread.sleep(limitDuration);
		Assert.assertEquals(true, counter.throttle(username1).isZero());
		
		// after some time the counter shouldn't consume anymore memory for these requests
		Thread.sleep(limitDuration * 2);
		Assert.assertEquals(0, counter.getUsernameCount());
	}

}
