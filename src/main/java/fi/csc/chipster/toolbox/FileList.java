package fi.csc.chipster.toolbox;

import java.io.IOException;

/**
 * Interface for file directory tree information
 * 
 * Provides information about the directory structure (list files in a
 * directory) and symlink targets, but not the file data.
 * 
 */
public interface FileList {

    boolean exists(String path);

    String[] list(String path);

    String getSymlinkTarget(String path) throws IOException;

}
