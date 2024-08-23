package fi.csc.chipster.util;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import org.apache.commons.io.FileUtils;

public class HttpDownload {

    public static void main(String args[]) throws URISyntaxException, IOException {
        if (args.length != 2) {
            System.err.println("Usage: HttpDownload URL FILE");
            System.exit(1);
        }

        URL url = new URI(args[0]).toURL();
        File file = new File(args[1]);

        FileUtils.copyURLToFile(url, file);
    }
}
