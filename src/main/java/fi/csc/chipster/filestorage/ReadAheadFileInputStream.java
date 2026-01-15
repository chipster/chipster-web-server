package fi.csc.chipster.filestorage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class ReadAheadFileInputStream extends InputStream {

    private final static Logger logger = LogManager.getLogger();

    private ExecutorService executor;
    private File file;
    private long fileLength;
    private long bufferPosition;

    private InputStream sourceStream;

    private BlockingQueue<Future<byte[]>> queue;

    private volatile boolean isError = false;

    /**
     * queue length 32 and chunk size 16 MiB provided best performance on Ceph RBD
     */
    public ReadAheadFileInputStream(File file) {
        this(file, 32, 1 << 24);
    }

    public ReadAheadFileInputStream(File file, int queueLength, int maxChunkSize) {

        if (!file.exists()) {
            throw new RuntimeException(new FileNotFoundException(file.toString()));
        }

        this.file = file;
        this.fileLength = file.length();

        // don't create more threads than necessary for small files
        // fileLength is long and can be casted to int only after the division
        queueLength = Math.min((int) (fileLength / maxChunkSize + 1), queueLength);

        this.executor = Executors.newFixedThreadPool(queueLength + 1);
        queue = new ArrayBlockingQueue<>(queueLength);

        executor.submit(new Runnable() {
            public void run() {
                try {
                    long requestTotal = 0;

                    while (!isError && requestTotal < fileLength) {
                        logger.debug("request " + requestTotal / 1024 / 1024);
                        int chunkSize = (int) Math.min(maxChunkSize, fileLength - requestTotal);
                        Callable<byte[]> task = read(requestTotal, file, chunkSize);
                        queue.put(executor.submit(task));
                        requestTotal += chunkSize;
                    }
                } catch (Exception e) {
                    logger.error("file read failed", e);
                    isError = true;
                }
            }
        });
    }

    private static Callable<byte[]> read(long pos, File file, int len) {
        return new Callable<byte[]>() {
            public byte[] call() throws FileNotFoundException, IOException {
                try {
                    logger.debug("read from " + pos / 1024 / 1024);

                    byte[] buffer = new byte[len];
                    RandomAccessFile raf;

                    raf = new RandomAccessFile(file, "r");
                    raf.seek(pos);
                    raf.readFully(buffer, 0, len);
                    raf.close();

                    return buffer;
                } catch (IOException e) {
                    // will be rethrown in get()
                    throw new RuntimeException(
                            "failed to read " + len + " bytes from position " + pos + " from file " + file, e);
                }
            }
        };
    }

    private void fillBuffer() throws IOException {

        if (sourceStream != null && sourceStream.available() > 0) {
            throw new RuntimeException("cannot fill buffer when previous buffer has data available");
        }

        if (isError) {
            // exception in other thread should have been logged already
            throw new RuntimeException("failed to read file " + file.toString());
        }

        try {
            if (bufferPosition < fileLength) {
                // take() waits for the next in queue, in case the queue is empty
                // get() waits for Callable to complete
                byte[] buffer = queue.take().get();

                this.sourceStream = new ByteArrayInputStream(buffer);

                logger.debug("got chunk " + bufferPosition / 1024 / 1024 + " \t" + buffer.length);

                bufferPosition += buffer.length;
            } else if (bufferPosition == fileLength) {
                // empty file
                this.sourceStream = new ByteArrayInputStream(new byte[0]);
            }
        } catch (Exception e) {
            // stop creating new read requests
            isError = true;
            throw new RuntimeException("failed to read file " + this.file.toString(), e);
        }
    }

    private void end() {
        executor.shutdown();
    }

    @Override
    public int read() throws IOException {

        if (sourceStream == null || sourceStream.available() == 0) {
            fillBuffer();
        }

        return sourceStream.read();
    }

    @Override
    public int read(byte[] b) throws IOException {

        if (sourceStream == null || sourceStream.available() == 0) {
            fillBuffer();
        }

        return sourceStream.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {

        if (sourceStream == null || sourceStream.available() == 0) {
            fillBuffer();
        }

        return sourceStream.read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {

        // we could call fillBuffer() and sourceStream.skip() repeatedly if needed
        throw new NotImplementedException();
    }

    @Override
    public int available() throws IOException {
        return sourceStream.available();
    }

    @Override
    public void close() throws IOException {
        sourceStream.close();
        end();
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
