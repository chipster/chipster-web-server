package fi.csc.chipster.filestorage;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.NotImplementedException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Read files with readahead
 * 
 * Readahead is needed when reading files from distributed storage systems.
 * Network hops add latency, which makes it slow to read files if you make read
 * request only after you have processed the previous data. Luckily
 * those systems often tolerate plenty of concurrency. We can overcome the
 * latency by making a lot of read requests ahead of the position where we are
 * reading the data at the mmoment.
 * 
 * Usually opeating system provides this functionality (e.g. command "blockdev
 * --getra /dev/vdX") and that should be used when possible. However, when we
 * run a service on a shared container platform, we don't have access to those
 * settings. We can still do the essentially same here in the application
 * code.
 * 
 * The read requests are kept in a queue, which works like a sliding window. The
 * constructor fills the queue and after that one new request is made whenever
 * one is taken out of the queue. All requests are made from the thread that
 * reads this stream, so the queue and all other fields are accessed only from
 * that one thread. Usage of the InputStream interface is assumed to be
 * single-threaded.
 * 
 * The stream must be closed, also when it's not read to the end, e.g. when a
 * client cancels a download. Otherwise the threads and the data in the queue
 * would stay in memory.
 * 
 * Some example results (CRC32 of 8 GiB file, one warm-up round with empty file,
 * OS caches dropped):
 * - chunk size 4 MiB, queue length 1: 56 MiB/s (essentially without readahead)
 * - chunk size 4 MiB, queue length 16: 375 MiB/s
 * - chunk size 16 MiB, queue length 32: 995 MiB/s
 */
public class ReadaheadFileInputStream extends InputStream {

    private final static Logger logger = LogManager.getLogger();

    // file to read
    private File file;
    private long fileLength;

    // size of the chunks to request, the last one can be smaller
    private long maxChunkSize;

    // store data chunks in direct memory instead of heap
    private boolean useDirectMemory;

    // executor for file reading
    private ExecutorService executor;

    // queue for file read requests
    private Queue<Future<byte[]>> queue;

    // file position for the next read request
    private long requestPosition;

    // file position for the start of the current bufferStream
    private long bufferPosition;

    // input stream for the current buffer
    private InputStream bufferStream;

    /**
     * queue length 32 and chunk size 16 MiB provided best performance on Ceph RBD
     */
    public ReadaheadFileInputStream(File file) {
        this(file, 32, 1 << 24, true);
    }

    /**
     * Read file with readahead
     * 
     * This will start threads to fill the queue. More requests will be made as soon
     * as there is space in the queue.
     * 
     * @param file            File to tread
     * @param queueLength     How many chunks to read in parallel
     * @param maxChunkSize    Maximum size for chunks. The last one can be smaller.
     * @param useDirectMemory Create data chunks in direct memmory. Set to false to
     *                        use heap instead.
     */
    public ReadaheadFileInputStream(File file, int queueLength, long maxChunkSize, boolean useDirectMemory) {

        if (!file.exists()) {
            throw new RuntimeException(new FileNotFoundException(file.toString()));
        }

        this.file = file;
        this.fileLength = file.length();
        this.maxChunkSize = maxChunkSize;
        this.useDirectMemory = useDirectMemory;

        // don't create more threads than necessary for small files
        // fileLength is a long and can be casted to int only after the division
        queueLength = Math.min((int) (fileLength / maxChunkSize + 1), queueLength);

        this.executor = Executors.newFixedThreadPool(queueLength);

        // queue size limits how many requests can be made in parallel (and kept in
        // memory), when the stream is consumed slower than we produce it
        this.queue = new ArrayDeque<>(queueLength);

        // fill the queue, it's kept full in fillBuffer()
        for (int i = 0; i < queueLength; i++) {
            requestNextChunk();
        }
    }

    /**
     * Make a read request for the next chunk, if the file has more data
     * 
     * Called from the constructor and from fillBuffer(), i.e. always from the
     * thread which reads this stream.
     */
    private void requestNextChunk() {

        if (requestPosition < fileLength) {

            logger.debug("request " + requestPosition / 1024 / 1024);

            // smaller chunk in the end of the file
            int chunkSize = (int) Math.min(maxChunkSize, fileLength - requestPosition);

            queue.add(executor.submit(read(requestPosition, file, chunkSize, useDirectMemory)));

            requestPosition += chunkSize;
        }
    }

    /**
     * Create Callable to read a file from specified position and length
     * 
     * @param pos             Start reading from this file position
     * @param file            File to read
     * @param len             Number of bytes to read
     * @param useDirectMemory Store data in direct memory
     * @return File data in byte array
     */
    private static Callable<byte[]> read(long pos, File file, int len, boolean useDirectMemory) {
        return new Callable<byte[]>() {
            public byte[] call() throws FileNotFoundException, IOException {
                try {
                    logger.debug("read from " + pos / 1024 / 1024);

                    if (useDirectMemory) {
                        // buffer in direct memory
                        // size can be adjusted with -XX:MaxDirectMemorySize=
                        try (FileChannel ch = FileChannel.open(file.toPath(),
                                StandardOpenOption.READ)) {
                            InputStream is = Channels.newInputStream(ch.position(pos));
                            return is.readNBytes(len);
                        }

                    } else {

                        // buffer in heap
                        byte[] buffer = new byte[len];
                        RandomAccessFile raf;

                        raf = new RandomAccessFile(file, "r");
                        raf.seek(pos);
                        raf.readFully(buffer, 0, len);
                        raf.close();

                        return buffer;
                    }

                } catch (IOException e) {
                    // will be rethrown in get()
                    throw new RuntimeException(
                            "failed to read " + len + " bytes from position " + pos + " from file " + file, e);
                }
            }
        };
    }

    /**
     * Get more data from the queue
     * 
     * Get a new buffer from the queue and create a fixed size ByteArrayInputStream
     * out of it, which is easy to consume in all read() methods. Make a new read
     * request to keep the queue full.
     * 
     * This method is called only from the InputStream interface and usage is
     * assumed to be single-threaded.
     * 
     * @throws IOException
     */
    private void fillBuffer() throws IOException {

        if (bufferStream != null && bufferStream.available() > 0) {
            throw new RuntimeException("cannot fill buffer when previous buffer has data available");
        }

        try {
            if (bufferPosition < fileLength) {

                Future<byte[]> request = queue.poll();

                if (request == null) {
                    throw new IllegalStateException("no read requests in the queue, is the stream closed?");
                }

                // get() waits for Callable to complete
                byte[] buffer = request.get();

                // request one more to keep the queue full
                requestNextChunk();

                this.bufferStream = new ByteArrayInputStream(buffer);

                logger.debug("got chunk " + bufferPosition / 1024 / 1024 + " \t" + buffer.length);

                bufferPosition += buffer.length;

            } else if (bufferPosition == fileLength) {
                // empty file
                this.bufferStream = new ByteArrayInputStream(new byte[0]);
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to read file " + this.file.toString(), e);
        }
    }

    @Override
    public int read() throws IOException {

        // assume that ByteArrayInputStream.available() accurately reports remaining
        // data in the buffer
        if (bufferStream == null || bufferStream.available() == 0) {
            fillBuffer();
        }

        // there is at least one byte
        return bufferStream.read();
    }

    @Override
    public int read(byte[] b) throws IOException {

        // assume that ByteArrayInputStream.available() accurately reports remaining
        // data in the buffer
        if (bufferStream == null || bufferStream.available() == 0) {
            fillBuffer();
        }

        // can be smaller than requested, if we are at the end of the current
        // bufferStream, but the InputStream definition allows it
        return bufferStream.read(b);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {

        // assume that ByteArrayInputStream.available() accurately reports remaining
        // data in the buffer
        if (bufferStream == null || bufferStream.available() == 0) {
            fillBuffer();
        }

        // can be smaller than requested, if we are at the end of the current
        // bufferStream, but the InputStream definition allows it
        return bufferStream.read(b, off, len);
    }

    @Override
    public long skip(long n) throws IOException {

        // we could call fillBuffer() and sourceStream.skip() repeatedly if needed
        throw new NotImplementedException();
    }

    @Override
    public int available() throws IOException {
        return bufferStream.available();
    }

    /**
     * Close the stream and release its threads and buffers
     * 
     * This must be called also when the stream is not read to the end, e.g. when a
     * client cancels a download. Otherwise the read requests would keep their data
     * in memory and the threads would stay alive.
     * 
     * Can be called multiple times.
     */
    @Override
    public void close() {

        // cancel requests which are still waiting or running
        for (Future<byte[]> request : queue) {
            request.cancel(true);
        }
        queue.clear();

        // interrupt reads and stop the threads
        executor.shutdownNow();

        this.bufferStream = null;
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
