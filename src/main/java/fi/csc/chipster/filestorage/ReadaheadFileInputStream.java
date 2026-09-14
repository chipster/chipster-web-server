package fi.csc.chipster.filestorage;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

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
 * reading the data at the moment.
 * 
 * Usually operating system provides this functionality (e.g. command "blockdev
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
 * would stay in memory. Close it from the same thread that reads it, because
 * close() accesses the same fields without synchronization.
 * 
 * The file is opened only once and the chunks are read with positional reads,
 * which don't use or change the position of the channel, so all the threads can
 * share the same channel. Like in any other stream, deleting the file doesn't
 * break the reading, because the file stays on the disk until the last open
 * file is closed.
 * 
 * The JVM reads a channel through a direct memory buffer, which it caches for
 * each thread, so each reading thread needs one chunk of direct memory in
 * addition to the chunk in the heap. That memory is released when the thread
 * ends, i.e. when this stream is closed.
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

    // the open file, shared by all the reading threads
    private FileChannel channel;

    // size of the chunks to request, the last one can be smaller
    private long maxChunkSize;

    // executor for file reading
    private ExecutorService executor;

    // queue for file read requests
    private Queue<Future<byte[]>> queue;

    // file position for the next read request
    private long requestPosition;

    // file position for the end of the current bufferStream, i.e. how much of the
    // file has been taken from the queue
    private long bufferPosition;

    // input stream for the current buffer
    private InputStream bufferStream;

    // first error of the read requests, kept to fail all the later reads too
    private IOException failure;

    // set in close()
    private boolean closed;

    // name the threads to make them easier to recognize in thread dumps
    public static final String THREAD_NAME_PREFIX = "readahead-";
    private static final AtomicInteger streamCount = new AtomicInteger();

    /**
     * Read file with readahead
     * 
     * This makes the first queueLength read requests immediately, which the
     * threads of the pool start to process. After that one new request is made
     * whenever the reader takes one out of the queue, see fillBuffer().
     * 
     * Queue length 32 and chunk size 16 MiB provided best performance on Ceph RBD,
     * but that reserves about 544 MiB of memory for each stream: the queue, the
     * chunk which is being read at the moment and the one which was just taken out
     * of the queue.
     * 
     * @param file         File to read
     * @param queueLength  How many chunks to read in parallel
     * @param maxChunkSize Maximum size for chunks. The last one can be smaller.
     *                     Chunks are kept in byte arrays, so this cannot be larger
     *                     than Integer.MAX_VALUE.
     */
    public ReadaheadFileInputStream(File file, int queueLength, long maxChunkSize) throws IOException {

        if (!file.isFile()) {
            // also a directory would pass exists()
            throw new FileNotFoundException(file.toString());
        }

        if (queueLength < 1) {
            throw new IllegalArgumentException("queue length must be at least 1, but it was " + queueLength);
        }

        if (maxChunkSize < 1 || maxChunkSize > Integer.MAX_VALUE) {
            // a larger value would be truncated to a wrong chunk size, or to zero,
            // which would look like an empty file
            throw new IllegalArgumentException(
                    "chunk size must be between 1 and " + Integer.MAX_VALUE + " bytes, but it was " + maxChunkSize);
        }

        this.file = file;
        this.fileLength = file.length();
        this.maxChunkSize = maxChunkSize;
        this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);

        // don't create more threads than there are chunks, but at least one, because
        // an empty file has no chunks at all
        long chunkCount = (fileLength + maxChunkSize - 1) / maxChunkSize;
        queueLength = (int) Math.max(1, Math.min(chunkCount, queueLength));

        int streamId = streamCount.incrementAndGet();
        AtomicInteger threadCount = new AtomicInteger();

        this.executor = Executors.newFixedThreadPool(queueLength, runnable -> {

            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + streamId + "-" + threadCount.incrementAndGet());

            // set these explicitly, because otherwise a new thread would inherit them
            // from the thread which happens to create the stream. Daemon threads don't
            // keep the JVM running, if a stream is left unclosed.
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);

            return thread;
        });

        // queue size limits how many requests can be made in parallel (and kept in
        // memory), when the stream is consumed slower than we produce it
        this.queue = new ArrayDeque<>(queueLength);

        try {
            // fill the queue, it's kept full in fillBuffer()
            for (int i = 0; i < queueLength; i++) {
                requestNextChunk();
            }
        } catch (RuntimeException e) {
            // nobody can close a constructor that throws, so release everything here
            close();
            throw e;
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

            queue.add(executor.submit(read(channel, requestPosition, file, chunkSize)));

            requestPosition += chunkSize;
        }
    }

    /**
     * Create Callable to read a file from specified position and length
     * 
     * @param channel Open file to read
     * @param pos     Start reading from this file position
     * @param file    File of the channel, for error messages
     * @param len     Number of bytes to read
     * @return File data in byte array
     */
    private static Callable<byte[]> read(FileChannel channel, long pos, File file, int len) {
        return new Callable<byte[]>() {
            public byte[] call() throws IOException {
                try {
                    logger.debug("read from " + pos / 1024 / 1024);

                    byte[] buffer = new byte[len];
                    ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);

                    // positional reads don't use or change the position of the channel, so
                    // the other threads can read the same channel at the same time
                    long position = pos;

                    while (byteBuffer.hasRemaining()) {

                        int bytes = channel.read(byteBuffer, position);

                        if (bytes < 0) {
                            // the file was truncated after we checked its length
                            throw new EOFException("file " + file + " ended at " + position);
                        }

                        position += bytes;
                    }

                    return buffer;

                } catch (IOException e) {
                    // get() will throw this as the cause of an ExecutionException
                    throw new IOException(
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

        if (bufferPosition < fileLength) {

            Future<byte[]> request = queue.poll();

            if (request == null) {
                throw failed(new IOException("no read requests in the queue for file " + this.file));
            }

            byte[] buffer;

            try {
                // get() waits for Callable to complete
                buffer = request.get();

            } catch (ExecutionException e) {

                if (e.getCause() instanceof Error) {
                    // e.g. OutOfMemoryError from the chunk allocation. Don't report it as
                    // a file read problem, it's a problem of the whole JVM
                    throw (Error) e.getCause();
                }

                // the Callable threw an IOException, use it as the cause
                throw failed(new IOException("failed to read file " + this.file, e.getCause()));

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                InterruptedIOException e2 = new InterruptedIOException("interrupted while reading file " + this.file);
                e2.initCause(e);

                // this request is still running, it's of no use to anyone anymore
                request.cancel(true);

                throw failed(e2);

            } catch (RuntimeException e) {
                // e.g. CancellationException, if the stream was closed by another thread
                request.cancel(true);
                throw failed(new IOException("failed to read file " + this.file, e));
            }

            // request one more to keep the queue full
            requestNextChunk();

            this.bufferStream = new ByteArrayInputStream(buffer);

            logger.debug("got chunk " + bufferPosition / 1024 / 1024 + " \t" + buffer.length);

            bufferPosition += buffer.length;

        } else {
            // end of the file, and of an empty file already in the first read
            this.bufferStream = new ByteArrayInputStream(new byte[0]);
        }
    }

    /**
     * Remember the first error and return it for throwing
     * 
     * All the later reads must fail too. Otherwise the next read would continue from
     * the next chunk, i.e. return data from a wrong position without any error.
     * 
     * @param e The error
     * @return The same error
     */
    private IOException failed(IOException e) {
        if (this.failure == null) {
            this.failure = e;
        }
        return e;
    }

    /**
     * Check that the stream is still usable
     * 
     * @throws IOException if the stream is closed or a read has failed
     */
    private void checkUsable() throws IOException {

        if (this.closed) {
            throw new IOException("stream is closed, file " + this.file);
        }

        if (this.failure != null) {
            throw new IOException("readahead has failed earlier for file " + this.file, this.failure);
        }
    }

    @Override
    public int read() throws IOException {

        prepareBuffer();

        // there is at least one byte, or the end of the file
        return bufferStream.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {

        if (len == 0) {
            // must return zero, also in the end of the file, where the buffer would
            // report the end instead
            checkUsable();
            return 0;
        }

        prepareBuffer();

        // can be smaller than requested, if we are at the end of the current
        // bufferStream, but the InputStream definition allows it
        return bufferStream.read(b, off, len);
    }

    /**
     * Make sure the current buffer has data, or that we are in the end of the file
     * 
     * @throws IOException if the stream is closed, a read has failed or the next
     *                     chunk cannot be read
     */
    private void prepareBuffer() throws IOException {

        checkUsable();

        // assume that ByteArrayInputStream.available() accurately reports remaining
        // data in the buffer
        if (bufferStream == null || bufferStream.available() == 0) {
            fillBuffer();
        }
    }

    @Override
    public long skip(long n) throws IOException {

        checkUsable();

        if (n <= 0) {
            return 0;
        }

        // we could call fillBuffer() and bufferStream.skip() repeatedly if needed
        throw new IOException("skip is not supported");
    }

    @Override
    public int available() throws IOException {

        checkUsable();

        if (bufferStream == null) {
            // nothing is read yet
            return 0;
        }

        return bufferStream.available();
    }

    /**
     * Close the stream and release its threads and buffers
     * 
     * This must be called also when the stream is not read to the end, e.g. when a
     * client cancels a download. Otherwise the read requests would keep their data
     * in memory and the threads would stay alive.
     * 
     * Call this from the same thread that reads the stream, because it accesses the
     * same fields without synchronization.
     * 
     * A read which has already started is interrupted, which closes the channel,
     * so the thread and its chunk are released soon. Can be called multiple times.
     */
    @Override
    public void close() throws IOException {

        this.closed = true;

        // cancel requests which are still waiting or running
        for (Future<byte[]> request : queue) {
            request.cancel(true);
        }
        queue.clear();

        // interrupt reads and stop the threads
        executor.shutdownNow();

        this.bufferStream = null;

        // an interrupted read closes the channel anyway, but close it also when
        // nothing was read
        this.channel.close();
    }

    /**
     * Length of the file when this stream was created
     * 
     * This stream reads exactly this many bytes, whatever happens to the file
     * later, so use this for the Content-Length header instead of asking the file
     * again.
     * 
     * @return File length in bytes
     */
    public long length() {
        return this.fileLength;
    }

    @Override
    public boolean markSupported() {
        return false;
    }
}
