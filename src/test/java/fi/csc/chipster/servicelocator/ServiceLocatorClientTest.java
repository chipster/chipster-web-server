package fi.csc.chipster.servicelocator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.rest.Config;
import fi.csc.chipster.servicelocator.resource.Service;
import jakarta.ws.rs.ProcessingException;

public class ServiceLocatorClientTest {

    @Test
    public void getPublicUrisTimeout() throws Exception {
        // accepts connections, but never responds
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {

            ServiceLocatorClient client = new ServiceLocatorClient(
                    "http://127.0.0.1:" + server.getLocalPort());

            long start = System.currentTimeMillis();
            assertThrows(ProcessingException.class,
                    () -> client.getPublicUris(Role.WEB_SERVER, Duration.ofMillis(200)));
            assertTrue(System.currentTimeMillis() - start < 5_000);
        }
    }

    @Test
    public void getPublicUrisLocally() throws Exception {
        ArrayList<Service> services = new ArrayList<>(List.of(
                new Service(Role.WEB_SERVER, null, "http://localhost:4200", null, null)));

        /*
         * service-locator's own CORS filter uses this, so it must not make requests
         * to the service-locator, i.e. to itself
         */
        ServiceLocatorClient client = new LocalServiceLocatorClient(services, services, new Config());

        assertEquals(Set.of("http://localhost:4200"), client.getPublicUris(Role.WEB_SERVER, Duration.ofMillis(200)));
    }
}
