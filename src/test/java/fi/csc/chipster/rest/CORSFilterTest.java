package fi.csc.chipster.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashMap;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import fi.csc.chipster.servicelocator.ServiceLocatorClient;

public class CORSFilterTest {

    private static final String ORIGIN = "http://localhost:4200";
    private static final String ALLOW_ORIGIN = "Access-Control-Allow-Origin";

    /**
     * The first request to the service-locator waits until the latch is released
     * and the ones in failFirst fail
     */
    private static class SlowServiceLocatorClient extends ServiceLocatorClient {

        private final CountDownLatch firstRequestStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFirstRequest = new CountDownLatch(1);
        private final AtomicInteger requestCount = new AtomicInteger();
        private final int failFirst;
        private volatile Duration timeout;

        public SlowServiceLocatorClient() {
            this(0);
        }

        public SlowServiceLocatorClient(int failFirst) {
            super("http://service-locator.invalid");
            this.failFirst = failFirst;
        }

        @Override
        public Set<String> getPublicUris(String role, Duration timeout) {
            this.timeout = timeout;
            int count = requestCount.incrementAndGet();
            if (count == 1) {
                firstRequestStarted.countDown();
                try {
                    releaseFirstRequest.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            if (count <= failFirst) {
                throw new IllegalStateException("service-locator not available");
            }
            return Set.of(ORIGIN);
        }
    }

    @Test
    public void headersWhileAnotherRequestGetsTheOrigin() throws Exception {
        SlowServiceLocatorClient serviceLocator = new SlowServiceLocatorClient();
        CORSFilter filter = new CORSFilter(serviceLocator);

        CompletableFuture<HashMap<String, String>> first = CompletableFuture
                .supplyAsync(() -> filter.getCorsHeaders(ORIGIN));
        assertTrue(serviceLocator.firstRequestStarted.await(10, TimeUnit.SECONDS));

        // the first request is still waiting for the service-locator
        assertEquals(ORIGIN, filter.getCorsHeaders(ORIGIN).get(ALLOW_ORIGIN));

        serviceLocator.releaseFirstRequest.countDown();
        assertEquals(ORIGIN, first.get(10, TimeUnit.SECONDS).get(ALLOW_ORIGIN));

        // the origin is stored, later requests don't ask again
        assertEquals(ORIGIN, filter.getCorsHeaders(ORIGIN).get(ALLOW_ORIGIN));
        assertEquals(2, serviceLocator.requestCount.get());

        // so that the requests don't hang if the service-locator does
        assertNotNull(serviceLocator.timeout);
    }

    @Test
    public void retryAfterFailure() {
        SlowServiceLocatorClient serviceLocator = new SlowServiceLocatorClient(1);
        serviceLocator.releaseFirstRequest.countDown();
        CORSFilter filter = new CORSFilter(serviceLocator);

        assertTrue(filter.getCorsHeaders(ORIGIN).isEmpty());
        assertEquals(ORIGIN, filter.getCorsHeaders(ORIGIN).get(ALLOW_ORIGIN));
        assertEquals(2, serviceLocator.requestCount.get());
    }

    @Test
    public void noRequestWithoutOrigin() {
        SlowServiceLocatorClient serviceLocator = new SlowServiceLocatorClient();
        CORSFilter filter = new CORSFilter(serviceLocator);

        // a request without an origin isn't from a browser
        assertTrue(filter.getCorsHeaders(null).isEmpty());
        assertEquals(0, serviceLocator.requestCount.get());
    }

    @Test
    public void noHeadersForOtherOrigin() {
        SlowServiceLocatorClient serviceLocator = new SlowServiceLocatorClient();
        serviceLocator.releaseFirstRequest.countDown();
        CORSFilter filter = new CORSFilter(serviceLocator);

        assertTrue(filter.getCorsHeaders("http://example.invalid").isEmpty());
    }
}
