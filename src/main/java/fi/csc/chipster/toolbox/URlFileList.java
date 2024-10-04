package fi.csc.chipster.toolbox;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Get file directory sturcture infromation from a file behind an URL address
 * 
 * The file must be in a format of what command 'find . -printf "%p\t%l\n"'
 * would output in the root directory.
 */
public class URlFileList implements FileList {

    private Logger logger = LogManager.getLogger();

    // key is the file path. If the file is a symlink, the value is the symlink
    // taarget, or otherwise null
    private HashMap<String, String> filesMap = null;

    public URlFileList(String fileListUrl) throws MalformedURLException, IOException, URISyntaxException {

        logger.info("download tools-bin file list from " + fileListUrl);

        HashMap<String, String> filesMap = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new URI(fileListUrl).toURL().openStream()))) {

            String line;

            while ((line = reader.readLine()) != null) {
                String[] cols = line.split("\t");
                if (cols.length == 1) {
                    // regular file
                    filesMap.put(cols[0], null);

                } else if (cols.length == 2) {
                    // symlink
                    filesMap.put(cols[0], cols[1]);
                } else {
                    throw new RuntimeException(
                            "parsing tools-bin file list failed. columns: " + cols.length
                                    + ", line: " + line);
                }

                if (filesMap.size() % 100_000 == 0) {
                    logger.info("files parsed: " + filesMap.size());
                }
            }

            this.filesMap = filesMap;
        }
    }

    @Override
    public boolean exists(String path) {

        return filesMap.containsKey("./" + path);
    }

    @Override
    public String[] list(String path) {

        String pathStart = "./" + path + "/";

        return filesMap.keySet().stream()
                .filter(key -> key.startsWith(pathStart))
                .map(key -> key.substring(pathStart.length()))
                .toArray(String[]::new);
    }

    @Override
    public String getSymlinkTarget(String path) throws IOException {

        String target = filesMap.get("./" + path);

        if (target == null) {
            throw new IOException("not a symlink: " + path);
        }

        return target;
    }
}
