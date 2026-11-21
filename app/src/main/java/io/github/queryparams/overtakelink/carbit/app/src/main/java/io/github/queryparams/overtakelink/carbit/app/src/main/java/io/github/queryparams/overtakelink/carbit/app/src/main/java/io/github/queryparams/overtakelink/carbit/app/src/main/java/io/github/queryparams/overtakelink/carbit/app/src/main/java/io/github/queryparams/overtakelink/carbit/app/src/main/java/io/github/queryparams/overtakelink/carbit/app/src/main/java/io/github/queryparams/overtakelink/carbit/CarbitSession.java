package io.github.queryparams.overtakelink.carbit;

import org.json.JSONObject;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import javax.crypto.Cipher;

public final class CarbitSession implements Closeable {
    private static final int MAX_QUEUED_VIDEO = 4194304;
    private static final int MAX_UNIT = 2097152;
    private String approvalNonce = "";
    private final Map<Integer, Channel> channels = new HashMap<>();
    private final LongSupplier clock = System::nanoTime;
    private final AtomicBoolean closed = new AtomicBoolean();
    private CarbitVideoConfig config;
    private Channel control;
    private byte[] csd;
    private boolean csdSent;
    private Channel data;
    private boolean dataReady;
    private final ThreadPoolExecutor events;
    private final AtomicBoolean failed = new AtomicBoolean();
    private final FramePacer framePacer = new FramePacer();
    private long generation;
    private String headUnitId = "";
    private boolean identityExchanged;
    private KeyPair keyPair;
    private long lastPxcHeartbeatAt;
    private final Listener listener;
    private final Object lock = new Object();
    private final AdbMultiplexer mux;
    private boolean needsKeyFrame = true;
    private Channel notify;
    private int pendingPulls;
    private final PhoneInfo phone;
    private int queuedBytes;
    private boolean ready;
    private boolean recoveryPending;
    private long recoveryRequestedAt;
    private final AtomicBoolean runStarted = new AtomicBoolean();
    private boolean startPending;
    private long startRequestedAt;
    private final ScheduledExecutorService timer;
    private final ArrayDeque<Unit> units = new ArrayDeque<>();

    public interface Listener {
        default void onDiagnostic(String msg) {}
        default void onFailure(String reason) {}
        default void onRequestKeyFrame(long generation) {}
        default void onTouch(CarbitTouchEvent event) {}
        default void onVideoConfig(CarbitVideoConfig config) {}
        default void onVideoStart(CarbitVideoConfig config) {}
        default void onVideoStop(long generation) {}
    }

    public static final class PhoneInfo {
        public final String installationId;
        public final String manufacturer;
        public final String model;
        public final String packageName;
        public final int sdkInt;
        public final int versionCode;
        public final String versionName;

        public PhoneInfo(String installationId, String manufacturer, String model, int sdkInt, String packageName, String versionName, int versionCode) {
            this.installationId = installationId == null ? "" : installationId;
            this.manufacturer = manufacturer == null ? "" : manufacturer;
            this.model = model == null ? "" : model;
            this.packageName = packageName == null ? "" : packageName;
            this.versionName = versionName == null ? "" : versionName;
            this.sdkInt = sdkInt;
            this.versionCode = versionCode;
        }
    }

    public CarbitSession(InputStream inputStream, OutputStream outputStream, Listener listener, PhoneInfo phoneInfo) {
        this.listener = listener;
        this.phone = phoneInfo;
        this.timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "carbit-session-clock");
            t.setDaemon(true);
            return t;
        });
        this.events = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), r -> {
            Thread t = new Thread(r, "carbit-session-events");
            t.setDaemon(true);
            return t;
        });
        this.mux = new AdbMultiplexer(inputStream, outputStream, new AdbMultiplexer.Listener() {
            @Override
            public void connected() {
                event(() -> listener.onDiagnostic("Carbit ADB connected"));
            }

            @Override
            public void peerIdentity(String nonce) {
                synchronized (lock) {
                    approvalNonce = nonce;
                }
            }

            @Override
            public void diagnostic(String msg) {
                event(() -> listener.onDiagnostic(msg));
            }

            @Override
            public void opened(AdbMultiplexer.Stream stream) throws IOException {
                openedStream(stream);
            }

            @Override
            public void data(AdbMultiplexer.Stream stream, byte[] bytes) throws IOException {
                Channel ch;
                synchronized (lock) {
                    ch = channels.get(stream.localId);
                }
                if (ch == null) {
                    throw new IOException("Missing Carbit stream owner");
                }
                ch.parser.feed(bytes);
            }

            @Override
            public void closed(AdbMultiplexer.Stream stream) {
                closedStream(stream);
            }
        });
    }

    public void run() throws IOException {
        if (!this.runStarted.compareAndSet(false, true)) {
            throw new IllegalStateException("Carbit session already started");
        }
        try {
            if (this.closed.get()) return;
            this.timer.scheduleAtFixedRate(this::tick, 10L, 10L, TimeUnit.MILLISECONDS);
            this.mux.run();
            close();
        } catch (IOException e) {
            if (!this.closed.get()) {
                fail(e.getMessage());
            }
            throw e;
        } catch (RejectedExecutionException e) {
            if (!this.closed.get()) {
                fail("Carbit session clock could not start");
                throw new IOException("Carbit clock unavailable", e);
            }
        } finally {
            close();
        }
    }

    public long getGeneration() {
        synchronized (this.lock) {
            return this.generation;
        }
    }

    public boolean markVideoReady(long gen) {
        try {
            synchronized (this.lock) {
                if (!this.closed.get() && this.startPending && this.config != null && gen == this.generation) {
                    if (this.csd != null && !this.units.isEmpty()) {
                        this.ready = true;
                        this.startPending = false;
                        this.control.stream.send(CarbitWire.mirror(113, null));
                        pumpVideo();
                        return true;
                    }
                }
                return false;
            }
        } catch (IOException e) {
            fail(e.getMessage());
            return false;
        }
    }

    public boolean submitAvcAccessUnit(byte[] bytes, long ptsUs, boolean keyFrame, boolean isConfig) {
        if (bytes == null || bytes.length == 0) return false;
        synchronized (this.lock) {
            if (this.closed.get() || this.config == null || (!this.ready && !this.startPending)) {
                return false;
            }
            if (bytes.length > MAX_UNIT) {
                rejectCurrentUnitLocked("Carbit access unit exceeds limit");
                return false;
            }
            if (isConfig) {
                if (bytes.length <= 65536 && hasNal(bytes, 7) && hasNal(bytes, 8)) {
                    if (!Arrays.equals(this.csd, bytes)) {
                        boolean hadCsd = this.csd != null;
                        this.units.clear();
                        this.queuedBytes = 0;
                        this.needsKeyFrame = true;
                        if (hadCsd) requestKeyFrameLocked();
                    }
                    this.csd = Arrays.copyOf(bytes, bytes.length);
                    this.csdSent = false;
                    return true;
                }
                rejectCurrentUnitLocked("Unsupported Carbit AVC codec configuration");
                return false;
            }
            if (isAnnexB(bytes) && (!keyFrame || hasNal(bytes, 5))) {
                if (this.needsKeyFrame && !keyFrame) {
                    return false;
                }
                if (bytes.length > MAX_QUEUED_VIDEO - this.queuedBytes || this.units.size() >= 8) {
                    this.units.clear();
                    this.queuedBytes = 0;
                    this.needsKeyFrame = true;
                    this.csdSent = false;
                    if (!keyFrame) {
                        requestKeyFrameLocked();
                        return false;
                    }
                }
                if (keyFrame) {
                    this.needsKeyFrame = false;
                    this.recoveryPending = false;
                }
                byte[] copy = Arrays.copyOf(bytes, bytes.length);
                this.units.add(new Unit(copy, ptsUs, this.generation));
                this.queuedBytes += copy.length;
                return true;
            }
            return false;
        }
    }

    private void rejectCurrentUnitLocked(String reason) {
        event(() -> fail(reason));
        stopLocked();
        this.config = null;
    }

    private void requestKeyFrameLocked() {
        if (this.recoveryPending) return;

