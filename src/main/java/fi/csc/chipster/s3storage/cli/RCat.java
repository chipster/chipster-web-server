package fi.csc.chipster.s3storage.cli;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.commons.io.IOUtils;

import fi.csc.chipster.filestorage.ReadaheadFileInputStream;
import io.jsonwebtoken.lang.Arrays;

/**
 * Concatenate files with readahead and print on the standard output
 */
public class RCat {

        public static void main(String[] args)
                        throws IOException, NoSuchAlgorithmException, ExecutionException, InterruptedException {

                int queue = 32;
                long chunk = 16;

                if (args.length == 0) {
                        System.out.println("NAME");
                        System.out.println(
                                        "    RCat - concatenate files with readahead and print on the standard output");
                        System.out.println();
                        System.out.println("SYNOPSIS");
                        System.out.println("    RCat [OPTION]... [FILE]...");
                        System.out.println();
                        System.out.println("OPTIONS");
                        System.out.println("    -Q, --queue");
                        System.out.println("        how many readahead chunks to read in parallel, default " + queue);
                        System.out.println();
                        System.out.println("    -c, --chunk");
                        System.out.println("        readahead chunk size in MiB, default " + chunk);
                        System.out.println();
                        System.exit(1);
                }

                List<String> argsList = new LinkedList<>(Arrays.asList(args));

                Iterator<String> iter = argsList.iterator();
                while (iter.hasNext()) {
                        String arg = iter.next();
                        switch (arg) {
                                case "--queue":
                                case "-Q":
                                        iter.remove();
                                        queue = Integer.parseInt(iter.next());
                                        iter.remove();
                                        break;
                                case "--chunk":
                                case "-c":
                                        iter.remove();
                                        chunk = Long.parseLong(iter.next());
                                        iter.remove();
                                        break;
                        }
                }

                for (String fileArg : argsList) {
                        try (InputStream fileStream = new ReadaheadFileInputStream(new File(fileArg), queue,
                                        chunk * 1024 * 1024,
                                        true)) {
                                IOUtils.copyLarge(fileStream, System.out, new byte[1 << 16]);
                        }
                }
        }
}