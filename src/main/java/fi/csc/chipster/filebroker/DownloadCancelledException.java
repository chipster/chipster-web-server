package fi.csc.chipster.filebroker;

import org.eclipse.jetty.io.EofException;

public class DownloadCancelledException extends RuntimeException {

    public DownloadCancelledException(EofException e) {
        super(e);
    }

}
