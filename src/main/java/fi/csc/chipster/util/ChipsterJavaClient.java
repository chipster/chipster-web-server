package fi.csc.chipster.util;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import fi.csc.chipster.auth.AuthenticationClient;
import fi.csc.chipster.auth.model.Role;
import fi.csc.chipster.filebroker.RestFileBrokerClient;
import fi.csc.chipster.servicelocator.ServiceLocatorClient;
import fi.csc.chipster.sessiondb.RestException;
import fi.csc.chipster.sessiondb.SessionDbClient;
import fi.csc.chipster.sessiondb.model.Dataset;
import fi.csc.chipster.sessiondb.model.Session;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;

/**
 * Chipster client in Java example
 */
public class ChipsterJavaClient {

    private final static Logger logger = LogManager.getLogger();

    public static void main(String args[]) throws JsonMappingException, JsonProcessingException, RestException {

        if (args.length != 3) {
            System.err.println("Usage: ChipsterJavaClient SERVER USERNAME PASSWORD");
            System.exit(1);
        }
        String server = args[0];
        String username = args[1];
        String password = args[2];

        Client webClient = AuthenticationClient.getClient(username, password, false);
        WebTarget appConfTarget = webClient.target(server).path("assets").path("conf").path("chipster.yaml");

        logger.info("get app conf from " + appConfTarget.getUri());

        String appConf = appConfTarget
                .request(MediaType.TEXT_PLAIN)
                .get(String.class);

        ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
        mapper.findAndRegisterModules();
        @SuppressWarnings("unchecked")
        Map<String, Object> appConfMap = mapper.readValue(appConf, HashMap.class);

        String serviceLocatorAddr = (String) appConfMap.get("service-locator");

        ServiceLocatorClient serviceLocator = new ServiceLocatorClient(serviceLocatorAddr);
        AuthenticationClient auth = new AuthenticationClient(serviceLocator.getPublicUri(Role.AUTH), username, password,
                Role.CLIENT);

        SessionDbClient sessionDb = new SessionDbClient(serviceLocator.getPublicUri(Role.SESSION_DB),
                Role.SESSION_DB_EVENTS, auth.getCredentials());

        RestFileBrokerClient fileBroker = new RestFileBrokerClient(serviceLocator, auth.getCredentials(), Role.CLIENT);

        ArrayList<Long> durations = new ArrayList<>();

        for (int j = 0; j < 10; j++) {

            Session session = new Session();

            session.setName("java-client-test-" + j);

            UUID sessionId = sessionDb.createSession(session);

            for (int i = 0; i < 10; i++) {

                long t = System.currentTimeMillis();

                Dataset dataset = new Dataset();
                dataset.setName("test-dataset-" + i);

                System.out.println("upload session " + j + " dataset " + i);
                UUID datasetId = sessionDb.createDataset(sessionId, dataset);

                long size = 3l * 1024;
                fileBroker.upload(sessionId, datasetId, new DummyInputStream(size), size);

                durations.add(System.currentTimeMillis() - t);
            }

            sessionDb.deleteSession(sessionId);
        }

        Collections.sort(durations);

        System.out.println("fastest");
        for (int i = 0; i < 3; i++) {
            System.out.println(durations.get(i) + "ms");
        }

        System.out.println("slowest");
        for (int i = 0; i < 3; i++) {
            System.out.println(durations.get(durations.size() - 1 - i) + "ms");
        }
    }

    public static class DummyInputStream extends InputStream {

        long bytes = 0;
        private long size;

        public DummyInputStream(long size) {
            this.size = size;
        }

        @Override
        public int read() {
            if (bytes < size) {
                bytes++;
                // don't convert to byte, because it would be signed and this could return -1
                // making the stream shorter
                return (int) (bytes % 256);
            } else {
                return -1;
            }
        }
    }

}
