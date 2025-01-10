package fi.csc.chipster.rest;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class BuildVersionStatusSource implements StatusSource {

    private static Logger logger = LogManager.getLogger();

    private String buildVersion;

    public BuildVersionStatusSource() {

        File buildVersionFile = new File("conf/build-version.txt");

        if (buildVersionFile.exists()) {
            try {
                this.buildVersion = Files.readString(buildVersionFile.toPath());
            } catch (IOException e) {
                logger.error("failed to read build version", e);
            }
        } else {
            logger.info("build version file not found " + buildVersionFile);
        }
    }

    @Override
    public Map<String, Object> getStatus() {

        HashMap<String, Object> map = new HashMap<>();
        map.put("chipsterBuildVersion", this.buildVersion);
        return map;
    }
}
