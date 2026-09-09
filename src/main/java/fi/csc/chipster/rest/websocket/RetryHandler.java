package fi.csc.chipster.rest.websocket;

/**
 * Backoff between the WebSocketClient's reconnection attempts
 *
 * Retry quickly at first, because most disconnections are momentary (a service
 * restarting during an update), then back off so that a server which stays down
 * isn't hammered for as long as it takes someone to fix it. There is no attempt
 * limit: a client that stopped trying would silently stop delivering events.
 *
 * The only close code we refuse to retry is VIOLATED_POLICY, which
 * WebSocketClient checks - see there. In particular these two are retried:
 * TRY_AGAIN_LATER (1013), the server closing us because its send queue was full,
 * and UNEXPECTED_CONDITION (1011), a send IOException on the server side. Both
 * mean some events are already lost, so reconnecting is the best recovery
 * available.
 *
 * Touched only by the thread that reconnects, so it needs no synchronization.
 *
 * @author klemela
 *
 */
public class RetryHandler {

	private int attempts = 0;

	/**
	 * How long to wait before the next attempt, counting this one
	 */
	public long nextDelaySeconds() {
		attempts++;
		if (attempts < 15) {
			return 1;
		} else if (attempts < 30) {
			return 10;
		} else {
			return 60;
		}
	}

	/**
	 * Start again from the shortest delay, after a successful connection
	 */
	public void reset() {
		attempts = 0;
	}
}
