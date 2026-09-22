package fi.csc.chipster.filestorage;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
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
 * client cancels a download. Otherwise the chunks in the queue would stay in
 * memory and the file would stay open. Close it from the same thread that reads
 * it, because close() accesses the same fields without synchronization.
 * 
 * The JVM reads a channel into a heap array through a temporary native buffer,
 * which it keeps for each thread until the thread ends, so each thread of the
 * pool needs one chunk of native memory in addition to the chunk in the heap.
 * The threads are shared and live as long as the pool, so this is paid once, not
 * for every transfer. That memory is allocated with Unsafe, so
 * -XX:MaxDirectMemorySize doesn't limit it and BufferPoolMXBean doesn't show it,
 * but the container counts it like any other memory. Set
 * -Djdk.nio.maxCachedBufferSize=0 to free it after each read instead.
 * 
 * The file is opened only once and the chunks are read with positional reads,
 * which don't use or change the position of the channel, so all the threads can
 * share the same channel. Like in any other stream, deleting the file doesn't
 * break the reading, because the file stays on the disk until the last open
 * file is closed.
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

    /**
     * Read file with readahead
     * 
     * This makes the first queueLength read requests immediately, which the
     * threads of the pool start to process. After that one new request is made
     * whenever the reader takes one out of the queue, see fillBuffer().
     * 
     * Queue length 32 and chunk size 16 MiB provided best performance on Ceph RBD,
     * but then one stream can keep about 544 MiB of heap: the queue, the chunk which
     * is being consumed and one which isn't collected yet. The threads of the pool
     * need one chunk of native memory each on top of that, see above.
     * 
     * @param file         File to read
     * @param queueLength  How many chunks to read in parallel
     * @param maxChunkSize Maximum size for chunks. The last one can be smaller.
     *                     Chunks are kept in byte arrays, so this cannot be larger
     *                     than Integer.MAX_VALUE.
     * @param executor     Threads for reading, shared by all the streams. It must
     *                     have at least queueLength threads for each stream which
     *                     is read at the same time, see createExecutor(). This
     *                     stream doesn't shut it down.
     */
    public ReadaheadFileInputStream(File file, int queueLength, long maxChunkSize, ExecutorService executor)
            throws IOException {

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
        this.maxChunkSize = maxChunkSize;
        this.executor = executor;

        this.channel = FileChannel.open(file.toPath(), StandardOpenOption.READ);

        try {
            // ask the length from the open file, because the path could point to
            // another file already
            this.fileLength = channel.size();

            init(queueLength, maxChunkSize);

        } catch (IOException | RuntimeException | Error e) {
            // nobody can close a constructor that throws, so release everything here
            try {
                close();
            } catch (Throwable e2) {
                // don't lose the original error
                e.addSuppressed(e2);
            }
            throw e;
        }
    }

    /**
     * Make the first read requests
     * 
     * @param queueLength  How many chunks to read in parallel
     * @param maxChunkSize Maximum size for chunks
     */
    private void init(int queueLength, long maxChunkSize) throws IOException {

        // don't request more chunks than the file has, but at least one, because an
        // empty file has no chunks at all
        long chunkCount = (fileLength + maxChunkSize - 1) / maxChunkSize;
        queueLength = (int) Math.max(1, Math.min(chunkCount, queueLength));

        // one request is made for each chunk taken out of the queue, see fillBuffer(),
        // which is what keeps the number of requests, and of the chunks in memory, at
        // queueLength
        this.queue = new ArrayDeque<>(queueLength);

        // fill the queue, it's kept full in fillBuffer()
        for (int i = 0; i < queueLength; i++) {
            requestNextChunk();
        }
    }

    /**
     * Create threads for the streams to share
     * 
     * One stream needs queueLength threads to read its chunks in parallel, so the
     * pool must have that many threads for each stream which is read at the same
     * time. The threads are kept until the pool is shut down, which is the point:
     * the JVM keeps a native buffer of one chunk for each thread which reads a
     * channel, and creating a pool for each transfer would pay that again for
     * every download.
     * 
     * @param threadCount Number of threads
     * @return Executor for the ReadaheadFileInputStream constructor
     */
    public static ExecutorService createExecutor(int threadCount) {

        AtomicInteger threadNumber = new AtomicInteger();

        return Executors.newFixedThreadPool(threadCount, runnable -> {

            Thread thread = new Thread(runnable, THREAD_NAME_PREFIX + threadNumber.incrementAndGet());

            // set these explicitly, because otherwise a new thread would inherit them
            // from the thread which happens to create the pool. Daemon threads don't
            // keep the JVM running, if the pool is left unclosed.
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY);

            return thread;
        });
    }

    /**
     * Make a read request for the next chunk, if the file has more data
     * 
     * Called from the constructor and from fillBuffer(), i.e. always from the
     * thread which reads this stream.
     */
    private void requestNextChunk() throws IOException {

        if (requestPosition < fileLength) {

            logger.debug("request " + requestPosition / 1024 / 1024);

            // smaller chunk in the end of the file
            int chunkSize = (int) Math.min(maxChunkSize, fileLength - requestPosition);

            try {
                queue.add(executor.submit(read(channel, requestPosition, file, chunkSize)));

            } catch (RejectedExecutionException e) {
                // the pool is shut down, e.g. the server is stopping. Report it like any
                // other read error, so that the caller doesn't get an unchecked
                // exception and the stream doesn't continue from a wrong position.
                throw failed(new IOException("failed to request a chunk of file " + this.file, e));
            }

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
                    // e.g. OutOfMemoryError from the chunk allocation. Report it as it is,
                    // it's a problem of the whole JVM, not of this file, but this chunk is
                    // lost, so the stream must not continue from the next one
                    failed(new IOException("failed to read file " + this.file, e.getCause()));
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
                // e.g. CancellationException, which shouldn't happen, because the same
                // thread reads and closes this stream. Report it like a read error
                // anyway, instead of throwing an unchecked exception to the caller.
                request.cancel(true);
                throw failed(new IOException("failed to read file " + this.file, e));
            }

            this.bufferStream = new ByteArrayInputStream(buffer);

            logger.debug("got chunk " + bufferPosition / 1024 / 1024 + " \t" + buffer.length);

            bufferPosition += buffer.length;

            // request one more to keep the queue full. A failure here fails this read
            // too, and the failure is remembered, so the next read can't continue from
            // a wrong position. The chunk of this read is then lost, whichever order we
            // use, because the caller gets an exception instead of the bytes.
            requestNextChunk();

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
    public int read(byte[] b, int off, int len) throws IOException {

        // the contract requires these checks also when nothing is read
        Objects.checkFromIndexSize(off, len, b.length);

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

    /**
     * Number of bytes left in the current chunk
     * 
     * The chunks which are read already but still in the queue are not counted, so
     * this can return zero although the next read wouldn't have to wait.
     */
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
     * Close the stream and release its buffers
     * 
     * This must be called also when the stream is not read to the end, e.g. when a
     * client cancels a download. Otherwise the read requests would keep their data
     * in memory.
     * 
     * Call this from the same thread that reads the stream, because it accesses the
     * same fields without synchronization.
     * 
     * A read which has already started is interrupted, which closes the channel.
     * Closing the channel waits for those reads, which means that this can block if
     * the storage doesn't respond, but also that almost all the chunks are released
     * when this returns. A chunk which is being allocated at that moment is
     * released a little later. Can be called multiple times.
     * 
     * The executor is shared with the other streams, so it's not shut down here.
     */
    @Override
    public void close() throws IOException {

        this.closed = true;

        try {
            // these are missing, if the constructor failed after the file was opened
            if (queue != null) {

                // cancel requests which are still waiting or running. This interrupts
                // the reads of this stream, which closes our channel, but the other
                // streams have channels of their own.
                for (Future<byte[]> request : queue) {
                    request.cancel(true);
                }
                queue.clear();
            }

            this.bufferStream = null;

        } finally {

            // an interrupted read closes the channel anyway, but close it also when
            // nothing was read. This waits for the reads which are already running, so
            // it can take as long as the storage does, and nothing here notices that.
            this.channel.close();
        }
    }

    /**
     * Write the whole stream to the OutputStream
     * 
     * The default implementation copies the data through a small buffer of its own.
     * Our chunks are already contiguous arrays, so we can write them as they are,
     * which saves a copy of every byte of every download.
     * 
     * A chunk is written with one call, so if the output takes some of it and then
     * throws, those bytes are written but this stream still has them. Nothing is
     * lost, but the transfer cannot be continued to another output.
     * 
     * @param out Where to write
     * @return Number of bytes written
     * @throws IOException
     */
    @Override
    public long transferTo(OutputStream out) throws IOException {

        Objects.requireNonNull(out);

        long transferred = 0;

        while (true) {

            prepareBuffer();

            if (bufferStream.available() == 0) {
                // end of the file
                return transferred;
            }

            // ByteArrayInputStream writes the rest of the chunk in one call
            transferred += bufferStream.transferTo(out);
        }
    }

    /**
     * Length of the file when this stream was created
     * 
     * This stream reads this many bytes, whatever happens to the file later, or
     * fails with an EOFException if the file was truncated in the middle. Use this
     * for the Content-Length header instead of asking the file again.
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
