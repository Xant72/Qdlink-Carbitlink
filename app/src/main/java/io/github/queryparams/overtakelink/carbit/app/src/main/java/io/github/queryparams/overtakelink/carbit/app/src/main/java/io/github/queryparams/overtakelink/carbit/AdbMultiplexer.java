package io.github.queryparams.overtakelink.carbit;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

final class AdbMultiplexer implements Closeable {
    static final int MAX_PENDING_BYTES = 6291456;
    private boolean connected;
    private final InputStream input;
    private final Listener listener;
    private volatile boolean newAdbMode;
    private final OutputStream output;
    private int pendingBytes;
    private volatile Thread writer;
    private final Object lock = new Object();
    private final Map<Integer, Stream> streams = new HashMap<>();
    private final ArrayBlockingQueue<AdbPacket> outputPackets = new ArrayBlockingQueue<>(64);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<IOException> writeFailure = new AtomicReference<>();
    private int nextId = 1;
    private int peerMax = 4096;
    private final CountDownLatch writerFinished = new CountDownLatch(1);
    private final CountDownLatch bootstrapWritten = new CountDownLatch(1);
    private final AtomicInteger packetDiagnostics = new AtomicInteger();

    interface Listener {
        void closed(Stream stream);
        void connected();
        void data(Stream stream, byte[] bArr) throws IOException;
        default void diagnostic(String str) {}
        void opened(Stream stream) throws IOException;
        default void peerIdentity(String str) {}
    }

    AdbMultiplexer(InputStream inputStream, OutputStream outputStream, Listener listener) {
        this.input = new AccessoryReadBuffer(inputStream);
        this.output = outputStream;
        this.listener = listener;
    }

    final class Stream {
        private boolean awaitingAck;
        private boolean ended;
        final int localId;
        private long outstandingAt;
        private Runnable outstandingCompletion;
        private int outstandingSize;
        private final ArrayDeque<Chunk> pending = new ArrayDeque<>();
        final int port;
        final int remoteId;

        Stream(int localId, int remoteId, int port) {
            this.localId = localId;
            this.remoteId = remoteId;
            this.port = port;
        }

        void send(byte[] bArr) throws IOException {
            send(bArr, null);
        }

        void send(byte[] bArr, Runnable runnable) throws IOException {
            synchronized (AdbMultiplexer.this.lock) {
                if (AdbMultiplexer.this.closed.get() || this.ended) {
                    throw new IOException("Carbit stream is closed");
                }
                if (bArr.length > AdbMultiplexer.MAX_PENDING_BYTES - AdbMultiplexer.this.pendingBytes) {
                    throw new IOException("Carbit peer stopped acknowledging data");
                }
                int i = 0;
                while (i < bArr.length) {
                    int end = Math.min(bArr.length, AdbMultiplexer.this.peerMax + i);
                    this.pending.add(new Chunk(Arrays.copyOfRange(bArr, i, end), end == bArr.length ? runnable : null));
                    i += AdbMultiplexer.this.peerMax;
                }
                AdbMultiplexer.this.pendingBytes += bArr.length;
                AdbMultiplexer.this.pump(this);
            }
        }

        boolean isIdle() {
            synchronized (AdbMultiplexer.this.lock) {
                return !this.awaitingAck && this.pending.isEmpty();
            }
        }
    }

    void run() throws IOException {
        synchronized (this.lock) {
            if (this.writer != null) {
                throw new IllegalStateException("ADB reader already started");
            }
            if (this.closed.get()) {
                return;
            }
            this.writer = new Thread(this::writeLoop, "carbit-adb-writer");
            this.writer.setDaemon(true);
        }
        try {
            this.writer.start();
            this.bootstrapWritten.await();
            IOException ioe = this.writeFailure.get();
            if (ioe != null) {
                throw ioe;
            }
            AdbPacket.AoaReader aoaReader = new AdbPacket.AoaReader(this.input);
            int zeroCount = 0;
            while (!this.closed.get()) {
                AdbPacket pkt = aoaReader.read();
                if (aoaReader.zeroRecords() != zeroCount) {
                    zeroCount = aoaReader.zeroRecords();
                    this.listener.diagnostic("Carbit ADB zero headers accepted=" + zeroCount);
                }
                if (pkt == null) {
                    break;
                }
                packetDiagnostic("RX", pkt, pkt.declaredChecksum);
                receive(pkt);
            }
            IOException ioe2 = this.writeFailure.get();
            if (ioe2 != null) {
                throw ioe2;
            }
            close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            close();
            throw new IOException("Carbit ADB bootstrap interrupted", e);
        } catch (Throwable th) {
            this.writerFinished.countDown();
            close();
            throw th;
        }
    }

    private void writeLoop() {
        try {
            if (!this.closed.get()) {
                byte[] bootstrap = ByteBuffer.allocate(24).array();
                this.output.write(bootstrap);
                this.output.flush();
                this.listener.diagnostic("Carbit ADB bootstrap written: 24 bytes");
                this.bootstrapWritten.countDown();
                while (!this.closed.get()) {
                    AdbPacket pkt = this.outputPackets.take();
                    pkt.write(this.output, this.newAdbMode);
                    packetDiagnostic("TX", pkt, this.newAdbMode ? 0 : AdbPacket.checksum(pkt.payload));
                }
            }
        } catch (IOException e) {
            if (!this.closed.get()) {
                this.writeFailure.compareAndSet(null, e);
            }
            close();
        } catch (InterruptedException unused) {
            Thread.currentThread().interrupt();
        } finally {
            this.bootstrapWritten.countDown();
            this.writerFinished.countDown();
        }
    }

    private void packetDiagnostic(String dir, AdbPacket pkt, int csum) {
        if (this.packetDiagnostics.getAndUpdate(i -> i < 32 ? i + 1 : 32) >= 32) {
            return;
        }
        String status = csum == 0 ? "ZERO" : (csum == AdbPacket.checksum(pkt.payload) ? "MATCH" : "DIFFERENT");
        this.listener.diagnostic("Carbit ADB " + dir + " cmd=0x" + Integer.toHexString(pkt.command) + " len=" + pkt.payload.length + " checksum=" + status);
    }

    private void emit(AdbPacket pkt) throws IOException {
        if (this.closed.get() || !this.outputPackets.offer(pkt)) {
            throw new IOException("Carbit USB output queue stopped");
        }
    }

    private void pump(Stream stream) throws IOException {
        if (stream.awaitingAck || stream.pending.isEmpty() || stream.ended) {
            return;
        }
        Chunk chunk = stream.pending.removeFirst();
        stream.awaitingAck = true;
        stream.outstandingSize = chunk.bytes.length;
        stream.outstandingAt = System.nanoTime();
        stream.outstandingCompletion = chunk.completion;
        emit(new AdbPacket(AdbPacket.WRTE, stream.localId, stream.remoteId, chunk.bytes));
    }

    void checkTimeout(long nowNano) throws IOException {
        synchronized (this.lock) {
            for (Stream stream : this.streams.values()) {
                if (stream.awaitingAck && nowNano - stream.outstandingAt > TimeUnit.SECONDS.toNanos(10L)) {
                    throw new IOException("Carbit ADB stream acknowledgement timed out");
                }
            }
        }
    }

    private void receive(AdbPacket pkt) throws IOException {
        Stream stream;
        Runnable completion;
        if (pkt.command == AdbPacket.CNXN) {
            synchronized (this.lock) {
                if (this.connected) {
                    throw new IOException("Repeated ADB connection in active USB session");
                }
                if (pkt.arg1 < 1024 || pkt.arg1 > 1048576 || (pkt.arg1 != 4096 && pkt.arg0 != AdbPacket.VERSION && pkt.arg0 != 16777217)) {
                    throw new IOException("Unsupported Carbit ADB version or payload size");
                }
                this.newAdbMode = pkt.arg1 != 4096;
                this.peerMax = Math.min(pkt.arg1, AdbPacket.MAX_PAYLOAD);
                this.connected = true;
                emit(new AdbPacket(AdbPacket.CNXN, AdbPacket.VERSION + (this.newAdbMode ? 1 : 0), this.peerMax,
                        "device::ro.product.name=jadbd".getBytes(StandardCharsets.UTF_8)));
            }
            this.listener.diagnostic("Carbit ADB negotiated version=0x" + Integer.toHexString(pkt.arg0) + " peerMax=" + pkt.arg1 + " mode=" + (this.newAdbMode ? "NEW" : "OLD"));
            this.listener.peerIdentity(nonce(pkt.payload));
            this.listener.connected();
            return;
        }
        if (pkt.command == AdbPacket.AUTH) {
            throw new IOException("This head unit requires an unsupported ADB authentication mode");
        }
        synchronized (this.lock) {
            if (!this.connected) {
        }
