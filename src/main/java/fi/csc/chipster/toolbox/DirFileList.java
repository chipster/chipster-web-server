package fi.csc.chipster.toolbox;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Get file directory structure information directly from file system
 * 
 */
public class DirFileList implements FileList {

    @SuppressWarnings("unused")
    private Logger logger = LogManager.getLogger();

    private File rootPath;

    public DirFileList(File rootPath) {
        this.rootPath = rootPath;
    }

    @Override
    public boolean exists(String path) {
        File dir = new File(rootPath, path);
        return dir.exists();
    }

    @Override
    public String[] list(String path) {
        File dir = new File(rootPath, path);

        return dir.list();
    }

    @Override
    public String getSymlinkTarget(String path) throws IOException {

        Path symlink = new File(rootPath, path).toPath();
        if (Files.isSymbolicLink(symlink)) {
            String target = Files.readSymbolicLink(symlink).getFileName().toString();
            return target;
        } else {
            throw new IOException("failed to get symlink target, not a symlink: " + path);
        }
    }

}
