package fi.csc.chipster.rest;

import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BuildVersionStatusSource implements StatusSource {

    public static final String CONF_CHIPSTER_BUILD_VERSION = "chipster-build-version";

    private static Logger logger = LogManager.getLogger();

    private String buildVersion;

    public BuildVersionStatusSource(Config config) {

        this.buildVersion = config.getString(CONF_CHIPSTER_BUILD_VERSION);

        logger.info("chipster build version: " + this.buildVersion);
    }

    @Override
    public Map<String, Object> getStatus() {

        HashMap<String, Object> map = new HashMap<>();
        map.put("chipsterBuildVersion", this.buildVersion);
        return map;
    }
}
