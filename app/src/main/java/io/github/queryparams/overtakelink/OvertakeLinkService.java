package io.github.queryparams.overtakelink;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.MotionEvent;

import com.andrerinas.openheadunit.App;
import com.andrerinas.openheadunit.BuildConfig;
import com.andrerinas.openheadunit.R;
import com.andrerinas.openheadunit.aap.AapService;
import com.andrerinas.openheadunit.connection.CommManager;
import com.andrerinas.openheadunit.decoder.audio.MicRecorder;

import java.io.Closeable;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import io.github.queryparams.overtakelink.car.CarProjection;
import io.github.queryparams.overtakelink.car.CarScreen;
import io.github.queryparams.overtakelink.car.DirectProjection;
import io.github.queryparams.overtakelink.carbit.CarbitSession;
import io.github.queryparams.overtakelink.carbit.CarbitTouchEvent;
import io.github.queryparams.overtakelink.carbit.CarbitVideoConfig;
import io.github.queryparams.overtakelink.gap.GapService;
import io.github.queryparams.overtakelink.link.EventLog;
import io.github.queryparams.overtakelink.link.LinkSession;
import io.github.queryparams.overtakelink.link.OldPackets;
import io.github.queryparams.overtakelink.link.Packets;
import io.github.queryparams.overtakelink.link.ScreenGeometry;

public final class OvertakeLinkService extends Service implements LinkSession.Host {
    public static final String ACTION_CONNECT_USB = "io.github.queryparams.overtakelink.CONNECT_USB";
    public static final String ACTION_STOP = "io.github.queryparams.overtakelink.STOP";
    public static final String ACTION_CONNECT_SIM = "io.github.queryparams.overtakelink.CONNECT_SIM";
    public static final String EXTRA_SIM = "sim";

    public enum State { WAITING, HANDSHAKE, RUNNING }

    private static final String CHANNEL = "overtakelink";
    public static final int NOTIFICATION_ID = 7;
    private static final String ALERT_CHANNEL = "overtakelink-alert";
    private static final int AA_SERVER_OFF_ID = 8;
    private static final int HU_SILENT_ID = 9;
    private static final long HU_SILENT_MS = 5000;
    private static final int VIDEO_QUEUE_LIMIT = 3;
    private static final int VIDEO_QUEUE_HARD_LIMIT = 30;
    private static final int OUT_QUEUE_MAX = 500;
    public static final String PREF_VIDEO_MODE = "videoMode";
    public static final String PREF_CAR_MIC = "carMic";
    private static final long CAR_MIC_WAIT_MS = 1500;
    private static final int OPEN_WAIT_S = 20;
    public static final String VIDEO_DIRECT = "direct";
    public static final String VIDEO_DIRECT_LIGHT = "direct720";
    public static final String VIDEO_DIRECT_SMALL = "direct480";
    public static final String VIDEO_TRANSCODE = "transcode";
    public static final String VIDEO_DEFAULT = VIDEO_TRANSCODE;
    private static final int CAR_DPI = 160;
    private static final int BITRATE = 6_000_000;
    private static final int MIN_BITRATE = 2_000_000;
    private volatile int encoderBitrate = BITRATE;
    private static final int DEFAULT_KEY_INTERVAL = 2;

    private static volatile OvertakeLinkService instance;
    private static volatile State state = State.WAITING;
    private static volatile String detail;

    private volatile Outbox outbox = new Outbox();
    private volatile boolean stopping;
    private final java.util.concurrent.ExecutorService critical =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> new Thread(r, "ol-critical"));

    private volatile SessionHost currentHost;
    private final java.util.concurrent.atomic.AtomicBoolean criticalScheduled = new java.util.concurrent.atomic.AtomicBoolean();

    private void scheduleCritical() {
        if (!criticalScheduled.compareAndSet(false, true)) return;
        try {
            critical.execute(() -> {
                criticalScheduled.set(false);
                SessionHost h = currentHost;
                if (h == null) return;
                Runnable screen = h.pendingScreen.getAndSet(null);
                if (screen != null) safely("screen", screen);
                Runnable video = h.pendingVideo.getAndSet(null);
                if (video != null) safely("video", video);
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {}
    }

    private static final int COMMANDS_MAX = 200;
    private long commandsDropped;
    private final java.util.concurrent.ThreadPoolExecutor commands = new java.util.concurrent.ThreadPoolExecutor(1, 1,
            0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(COMMANDS_MAX), r -> new Thread(r, "ol-commands"),
            (r, pool) -> { if (commandsDropped++ % 100 == 0) EventLog.add("Queue full"); });

    private volatile int connectAttempt;
    private PowerManager.WakeLock wakeLock;
    private Closeable link;
    private InputStream in;
    private OutputStream out;
    private Timer timer;
    private volatile LinkSession session;
    private volatile CarbitSession carbitSession;
    private volatile boolean connected;
    private BroadcastReceiver detachReceiver;
    private volatile long connectedAt;
    private volatile boolean firstWriteDone;
    private volatile boolean direct;
    private volatile DirectProjection.Size directSize;
    private volatile int videoWidth, videoHeight;
    private volatile MicRecorder.ExternalMic.Sink carMicSink;
    private volatile boolean carMicHeard;
    private volatile boolean carMicSilent;

    private final MicRecorder.ExternalMic carMic = new MicRecorder.ExternalMic() {
        @Override
        public void start(Sink sink, Runnable fallback) {
            LinkSession s = session;
            Timer t = timer;
            if (s == null || t == null || carMicSilent) {
                fallback.run();
                return;
            }
            carMicHeard = false;
            carMicSink = sink;
            s.speech(true);
            t.schedule(new TimerTask() {
                @Override
                public void run() {
                    if (carMicSink == sink && !carMicHeard) {
                        carMicSilent = true;
                        fallback.run();
                    }
                }
            }, CAR_MIC_WAIT_MS);
        }

        @Override
        public void stop() {
            boolean on = carMicSink != null;
            carMicSink = null;
            LinkSession s = session;
            if (on && s != null) s.speech(false);
        }
    };

    public static State state() { return state; }

    public static String detail(Context c) {
        String d = detail;
        return d != null ? d : c.getString(R.string.ol_plug_cable);
    }

    public static String title(Context c) {
        return c.getString(state == State.RUNNING ? R.string.ol_state_running
                : state == State.HANDSHAKE ? R.string.ol_state_handshake : R.string.ol_state_waiting);
    }

    public static long connectedAt() {
        OvertakeLinkService s = instance;
        return s == null ? 0 : s.connectedAt;
    }

    private void setState(State s, String d) {
        state = s;
        detail = d;
        updateNotification();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        EventLog.init(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "OvertakeLink", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(ALERT_CHANNEL, getString(R.string.ol_alerts), NotificationManager.IMPORTANCE_HIGH));
        startForegroundWithMicrophone();
        wakeLock = getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "overtakelink:link");
        wakeLock.setReferenceCounted(false);
    }

    private void startForegroundWithMicrophone() {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(), types);
        } catch (RuntimeException e) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        }
    }

    private Notification buildNotification() { return notification(this); }

    public static Notification notification(Context c) {
        if (GapService.state() != State.WAITING) return GapService.notification(c);
        PendingIntent open = PendingIntent.getActivity(c, 0, new Intent(c, OvertakeLinkActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(c, 1, new Intent(c, OvertakeLinkService.class).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(c, CHANNEL)
                .setContentTitle("OvertakeLink · " + title(c))
          setContentText(detail(c))
.setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
.setContentIntent(open)
.addAction(new Notification.Action.Builder(null, c.getString(R.string.ol_stop), stop).build())
.setOngoing(true)
.build();
}
private void updateNotification() {
NotificationManager nm = getSystemService(NotificationManager.class);
if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
}
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
String action = intent == null ? null : intent.getAction();
if (ACTION_CONNECT_USB.equals(action)) {
int attempt;
synchronized (this) { attempt = ++connectAttempt; stopping = false; }
new Thread(() -> safely("usb", () -> connectUsb(attempt)), "ol-usb").start();
} else if (ACTION_CONNECT_SIM.equals(action) && BuildConfig.SIM_LINK) {
String target = intent.getStringExtra(EXTRA_SIM);
new Thread(() -> safely("sim", () -> connectSim(target)), "ol-sim").start();
} else if (ACTION_STOP.equals(action)) {
stopping = true;
disconnect("stopped");
stopSelf();
} else if (!connected) {
stopSelf();
}
return START_NOT_STICKY;
}
@Override
public void onDestroy() {
stopping = true;
disconnect("destroyed");
commands.shutdownNow();
critical.shutdownNow();
if (wakeLock.isHeld()) wakeLock.release();
instance = null;
super.onDestroy();
}
@Override
public IBinder onBind(Intent intent) { return null; }
private static void safely(String what, Runnable r) {
try { r.run(); } catch (Throwable t) { EventLog.error(what, t); }
}
private void connectUsb(int attempt) {
EventLog.newConnection();
synchronized (this) {
if (attempt != connectAttempt || stopping) return;
if (connected) {
if (carbitSession != null) closeCarbit("reconnect", false);
else close("reconnect", session, false);
}
}
UsbManager usb = getSystemService(UsbManager.class);
UsbAccessory[] list = usb.getAccessoryList();
if (list == null || list.length == 0) {
connectFailed(attempt, getString(R.string.ol_car_not_found));
return;
}
UsbAccessory acc = Arrays.asList(list).get(0);
String mfg = acc.getManufacturer() == null ? "" : acc.getManufacturer();
String mdl = acc.getModel() == null ? "" : acc.getModel();
if (!usb.hasPermission(acc)) {
connectFailed(attempt, getString(R.string.ol_no_usb_permission));
return;
}
ParcelFileDescriptor pfd = null;
int tries = 0;
while (pfd == null && tries < OPEN_WAIT_S && !stopping && attempt == connectAttempt) {
tries++;
pfd = usb.openAccessory(acc);
if (pfd == null) {
try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
}
}
if (pfd == null) {
if (attempt != connectAttempt) return;
if (stopping) { stopAaIfOwner(); return; }
connectFailed(attempt, getString(R.string.ol_usb_busy));
return;
}
synchronized (this) {
if (attempt != connectAttempt || stopping) {
try { pfd.close(); } catch (IOException ignored) {}
if (stopping) stopAaIfOwner();
return;
}
boolean isQdLink = "Neusoft".equalsIgnoreCase(mfg) || "QDriveLink".equalsIgnoreCase(mdl)
|| "AndroidLink".equalsIgnoreCase(mfg) || "AndroidLink".equalsIgnoreCase(mdl);
if (isQdLink) {
open(pfd, new FileInputStream(pfd.getFileDescriptor()), new FileOutputStream(pfd.getFileDescriptor()));
} else {
openCarbit(pfd, new FileInputStream(pfd.getFileDescriptor()), new FileOutputStream(pfd.getFileDescriptor()));
}
watchDetach();
}
}
private void stopAaIfOwner() {
OvertakeLinkService live = instance;
if (live == null || live == this) stopService(new Intent(this, AapService.class));
}
private void connectFailed(int attempt, String why) {
synchronized (this) {
if (attempt != connectAttempt || connected) return;
stopAaIfOwner();
idle(why);
}
}
private void watchDetach() {
LinkSession s = session;
detachReceiver = new BroadcastReceiver() {
@Override
public void onReceive(Context c, Intent i) {
if (Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction())) return;
new Thread(() -> {
if (carbitSession != null) closeCarbit("detached", true);
else close("detached", s, true);
}, "ol-detach").start();
}
};
IntentFilter f = new IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
f.addAction(Intent.ACTION_POWER_DISCONNECTED);
registerReceiver(detachReceiver, f, Context.RECEIVER_NOT_EXPORTED);
}
```java
    private void openCarbit(Closeable l, InputStream i, OutputStream o) {
        wakeLock.acquire();
        link = l;
        in = i;
        out = o;
        connected = true;
        connectedAt = System.currentTimeMillis();
        setState(State.HANDSHAKE, "CarbitLink: подключение к ГУ...");
        CarbitSession.PhoneInfo info = new CarbitSession.PhoneInfo(
                phoneUuid(), Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT,
                getPackageName(), BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
        CarbitSession cs = new CarbitSession(i, o, new CarbitSession.Listener() {
            @Override
            public void onDiagnostic(String msg) { EventLog.add("Carbit: " + msg); }
            @Override
            public void onFailure(String reason) { closeCarbit("error: " + reason, true); }
            @Override
            public void onVideoConfig(CarbitVideoConfig cfg) {
                videoWidth = cfg.width;
                videoHeight = cfg.height;
                carScreenFor(null, cfg.width, cfg.height);
            }
            @Override
            public void onVideoStart(CarbitVideoConfig cfg) {
                videoWidth = cfg.width;
                videoHeight = cfg.height;
                if (CarScreen.current() == null) carScreenFor(null, cfg.width, cfg.height);
                requestKeyFrame();
                setState(State.RUNNING, "CarbitLink: " + cfg.width + "x" + cfg.height);
            }
            @Override
            public void onVideoStop(long gen) {
                if (connected) setState(State.HANDSHAKE, "CarbitLink: пауза");
            }
            @Override
            public void onRequestKeyFrame(long gen) { requestKeyFrame(); }
            @Override
            public void onTouch(CarbitTouchEvent ev) {
                int act = ev.action == CarbitTouchEvent.Action.DOWN ? MotionEvent.ACTION_DOWN
                        : (ev.action == CarbitTouchEvent.Action.UP ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE);
                touch(act, ev.x, ev.y);
            }
        }, info);
        carbitSession = cs;
        new Thread(() -> {
            try { cs.run(); } catch (IOException e) { closeCarbit("io: " + e.getMessage(), true); }
        }, "ol-carbit-main").start();
    }

    private void closeCarbit(String reason, boolean goIdle) {
        CarbitSession cs;
        Closeable l;
        synchronized (this) {
            if (!connected || carbitSession == null) return;
            connected = false;
            cs = carbitSession;
            carbitSession = null;
            l = link;
            link = null;
            in = null;
            out = null;
            tearDownCar();
            if (detachReceiver != null) { unregisterReceiver(detachReceiver); detachReceiver = null; }
        }
        cs.close();
        try { if (l != null) l.close(); } catch (Exception ignored) {}
        if (goIdle) synchronized (this) { if (!connected) idle(getString(R.string.ol_plug_cable)); }
    }

    private synchronized void connectSim(String target) {
        if (connected) close("reconnect", session, false);
        int colon = target.lastIndexOf(':');
        try {
            Socket socket = new Socket(target.substring(0, colon), Integer.parseInt(target.substring(colon + 1)));
            socket.setTcpNoDelay(true);
            open(socket, socket.getInputStream(), socket.getOutputStream());
        } catch (IOException e) {
            idle(getString(R.string.ol_sim_unavailable, target, e.getMessage()));
        }
    }

    private void open(Closeable l, InputStream i, OutputStream o) {
        wakeLock.acquire();
        link = l;
        in = i;
        out = o;
        SessionHost h = new SessionHost();
        LinkSession s = new LinkSession(h);
        h.owner = s;
        Outbox box = new Outbox();
        h.box = box;
        outbox = box;
        session = s;
        currentHost = h;
        connected = true;
        connectedAt = System.currentTimeMillis();
        firstWriteDone = false;
        carMicSilent = false;
        MicRecorder.setExternalMic(getSharedPreferences("overtakelink", MODE_PRIVATE).getBoolean(PREF_CAR_MIC, false) ? carMic : null);
        setState(State.HANDSHAKE, getString(R.string.ol_waiting_hu));
        timer = new Timer("ol-timer");
        new Thread(() -> readLoop(s, i), "ol-reader").start();
        new Thread(() -> writeLoop(s, o, box.q), "ol-writer").start();
        timer.schedule(new TimerTask() {
            private boolean silentShown, answered;
            @Override
            public void run() {
                safely("timer", s::tick);
                if (answered || s != session) return;
                if (s.proto() != LinkSession.Proto.UNKNOWN) {
                    answered = true;
                    getSystemService(NotificationManager.class).cancel(HU_SILENT_ID);
                } else if (!silentShown && System.currentTimeMillis() - connectedAt >= HU_SILENT_MS) {
                    silentShown = true;
                    huSilent(s);
                }
            }
        }, 0, 3000);
    }

    private void disconnect(String reason) {
        if (carbitSession != null) closeCarbit(reason, true);
        else close(reason, session, true);
    }

    private void close(String reason, LinkSession s, boolean idle) {
        if (carbitSession != null) { closeCarbit(reason, idle); return; }
        Closeable l;
        synchronized (this) {
            if (!connected || s != session) return;
            connected = false;
            l = link;
            link = null;
            in = null;
            out = null;
            tearDownCar();
            MicRecorder.setExternalMic(null);
            getSystemService(NotificationManager.class).cancel(HU_SILENT_ID);
            if (timer != null) { timer.cancel(); timer = null; }
            if (detachReceiver != null) { unregisterReceiver(detachReceiver); detachReceiver = null; }
        }
        try { if (l != null) l.close(); } catch (Exception ignored) {}
        if (idle) synchronized (this) { if (!connected) idle(getString(R.string.ol_plug_cable)); }
    }

    private void idle(String why) {
        if (wakeLock.isHeld()) wakeLock.release();
        setState(State.WAITING, why);
        stopSelf();
    }

    private void readLoop(LinkSession s, InputStream i) {
        byte[] buf = new byte[OldPackets.BLOCK];
        try {
            while (connected && s == session) {
                int n = i.read(buf, 0, OldPackets.BLOCK);
                if (n < 0) { close("eof", s, true); return; }
                s.onBytes(buf, n);
            }
        } catch (Throwable t) { close("read error", s, true); }
    }

    private void writeLoop(LinkSession s, OutputStream o, LinkedBlockingQueue<byte[]> q) {
        try {
            boolean first = true;
            while (connected && s == session) {
                byte[] p = q.poll(500, TimeUnit.MILLISECONDS);
                if (p != null) {
                    o.write(p);
                    if (first) {
                        first = false;
                        synchronized (this) { if (connected && s == session) firstWriteDone = true; }
                    }
                }
            }
        } catch (Exception e) { close("write error", s, true); }
    }

    @Override
    public void send(byte[] packet) { if (connected) outbox.offer(packet); }

    private String videoMode() {
        return getSharedPreferences("overtakelink", MODE_PRIVATE).getString(PREF_VIDEO_MODE, VIDEO_DEFAULT);
    }

    public static DirectProjection.Size directSize(String mode) {
        return VIDEO_DIRECT_LIGHT.equals(mode) ? DirectProjection.Size.LIGHT
                : VIDEO_DIRECT_SMALL.equals(mode) ? DirectProjection.Size.SMALL : DirectProjection.Size.FULL;
    }

    @Override
    public void carScreen(int carWidth, int carHeight) { carScreenFor(session, carWidth, carHeight); }

    void carScreenFor(LinkSession owner, int carWidth, int carHeight) {
        boolean isCarbit = (owner == null && carbitSession != null);
        if ((!isCarbit && owner != session) || CarScreen.current() != null) return;
        getSharedPreferences("overtakelink", MODE_PRIVATE).edit().putString("carScreen", carWidth + "×" + carHeight).apply();
        Packets.Video v = new Packets.Video();
        v.width = carWidth; v.height = carHeight; v.frameRate = CarScreen.FRAME_RATE;
        v.bitRate = BITRATE; encoderBitrate = BITRATE; v.frameInterval = DEFAULT_KEY_INTERVAL;
        LinkSession s = owner;
        Outbox box;
        synchronized (this) {
            if (!isCarbit && owner != session) return;
            box = outbox;
        }
        String mode = videoMode();
        boolean isDirect = !VIDEO_TRANSCODE.equals(mode);
        DirectProjection.Size size = directSize(mode);
        CarScreen screen = new CarScreen(carWidth, carHeight, CAR_DPI, new CarScreen.Sink() {
            @Override
            public void onEncoderFailed(String why) {
                new Thread(() -> {
                    synchronized (OvertakeLinkService.this) {
                        if (!connected || (!isCarbit && s != session)) return;
                        if (isCarbit) closeCarbit("encoder: " + why, true);
                        else close("encoder: " + why, s, true);
                    }
                }).start();
            }
            @Override
            public void onCodecConfig(byte[] spsPps) {
                CarbitSession cs = carbitSession;
                if (cs != null) { cs.submitAvcAccessUnit(spsPps, System.nanoTime() / 1000L, true, true); return; }
                if (s != null && s.videoPlaying() && box.acceptVideo(true)) box.offer(s.videoPacket(spsPps, 0, spsPps.length));
            }
            @Override
            public void onFrame(byte[] data, int off, int len, boolean keyFrame) {
                CarbitSession cs = carbitSession;
              if (cs != null) {
byte[] copy = (off == 0 && len == data.length) ? data : Arrays.copyOfRange(data, off, off + len);
cs.submitAvcAccessUnit(copy, System.nanoTime() / 1000L, keyFrame, false);
cs.markVideoReady(cs.getGeneration());
return;
}
if (s != null && s.videoPlaying() && box.acceptVideo(keyFrame)) box.offer(s.videoPacket(data, off, len));
}
});
try {
if (isDirect) screen.startDirect(this);
else screen.start(this, v);
} catch (Exception e) {
screen.stop();
return;
}
synchronized (this) {
if (!connected || (!isCarbit && s != session) || CarScreen.current() != null) {
screen.stop();
if (!connected) stopAaIfOwner();
return;
}
screen.publish();
direct = isDirect;
directSize = size;
if (isDirect) {
if (s != null) s.setFrameSize(size.getWidth(), size.getHeight());
DirectProjection.start(this, carWidth, carHeight, size, (data, off, len, keyFrame) -> {
CarbitSession cs = carbitSession;
if (cs != null) {
byte[] copy = (off == 0 && len == data.length) ? data : Arrays.copyOfRange(data, off, off + len);
ByteBuffer hdr = ByteBuffer.wrap(copy);
boolean isCfg = keyFrame && copy.length >= 5 && ((hdr.get(3) & 31) == 7 || (hdr.get(4) & 31) == 7);
cs.submitAvcAccessUnit(copy, System.nanoTime() / 1000L, keyFrame, isCfg);
if (!isCfg) cs.markVideoReady(cs.getGeneration());
return;
}
if (s != null && s.videoPlaying() && box.acceptVideo(keyFrame)) box.offer(s.videoPacket(data, off, len));
});
} else {
CarProjection.show(this, screen);
}
sendAap(AapService.ACTION_START_SELF_MODE);
}
setState(State.HANDSHAKE, getString(R.string.ol_aa_starting));
NotificationManager nm = getSystemService(NotificationManager.class);
if (!isCarbit && s != null) retryAaUntilStarted(nm, s);
if (AaServer.isListening()) nm.cancel(AA_SERVER_OFF_ID);
else {
String howTo = getString(R.string.ol_how_to_start);
if (isDirect) DirectProjection.notice(howTo);
else CarProjection.notice(howTo);
setState(State.HANDSHAKE, howTo.replace('\n', ' '));
notifyAaServerOff(nm);
}
}
private void retryAaUntilStarted(NotificationManager nm, LinkSession mine) {
Timer t = timer;
if (t == null) return;
t.schedule(new TimerTask() {
@Override
public void run() {
safely("retry", () -> {
CommManager.ConnectionState aa = App.Companion.provide(OvertakeLinkService.this).getCommManager().getConnectionState().getValue();
if (!connected) { cancel(); return; }
if (aa instanceof CommManager.ConnectionState.TransportStarted) {
cancel();
LinkSession s = session;
if (s != null && s.videoPlaying()) setState(State.RUNNING, runningText());
return;
}
if ((aa instanceof CommManager.ConnectionState.Error || aa instanceof CommManager.ConnectionState.Disconnected) && AaServer.isListening()) {
synchronized (OvertakeLinkService.this) {
if (!connected || session != mine) { cancel(); return; }
nm.cancel(AA_SERVER_OFF_ID);
sendAap(AapService.ACTION_START_SELF_MODE);
setState(State.HANDSHAKE, getString(R.string.ol_aa_starting));
}
}
});
}
}, 3000, 3000);
}
private synchronized void huSilent(LinkSession s) {
if (!connected || s != session || s.proto() != LinkSession.Proto.UNKNOWN) return;
String text = getString(R.string.ol_hu_silent_text);
setState(State.HANDSHAKE, getString(R.string.ol_hu_silent) + ". " + text);
getSystemService(NotificationManager.class).notify(HU_SILENT_ID, new Notification.Builder(this, ALERT_CHANNEL)
.setContentTitle(getString(R.string.ol_hu_silent)).setContentText(text)
.setSmallIcon(android.R.drawable.stat_sys_warning).setAutoCancel(true).build());
}
private void notifyAaServerOff(NotificationManager nm) {
PendingIntent open = PendingIntent.getActivity(this, 2, AaServer.settingsIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
String text = getString(R.string.ol_aa_server_off_text);
nm.notify(AA_SERVER_OFF_ID, new Notification.Builder(this, ALERT_CHANNEL)
.setContentTitle(getString(R.string.ol_aa_server_off)).setContentText(text)
.setSmallIcon(android.R.drawable.stat_sys_warning).setContentIntent(open).setAutoCancel(true).build());
}
@Override
public void startVideo(Packets.Video v) { startVideoFor(session, v); }
void startVideoFor(LinkSession owner, Packets.Video v) {
if (owner != session || !connected) return;
CarScreen screen = CarScreen.current();
if (screen == null) {
carScreenFor(owner, v.width, v.height);
screen = CarScreen.current();
if (screen == null) { owner.videoStopped(); return; }
}
synchronized (this) {
if (owner != session || !connected) return;
outbox.waitKeyFrame = true;
requestKeyFrame();
videoWidth = v.width; videoHeight = v.height;
if (!direct && v.bitRate > 0) {
int target = Math.max(MIN_BITRATE, v.bitRate);
if (target != encoderBitrate) { screen.setBitrate(target); encoderBitrate = target; }
}
if (App.Companion.provide(this).getCommManager().getConnectionState().getValue() instanceof CommManager.ConnectionState.TransportStarted) {
setState(State.RUNNING, runningText());
}
}
}
private String runningText() {
return direct ? getString(R.string.ol_running_direct, directSize.getHeight())
: getString(R.string.ol_running_transcode, videoWidth, videoHeight, encoderBitrate / 1e6);
}
@Override
public void stopVideo() { if (connected) setState(State.HANDSHAKE, getString(R.string.ol_video_paused)); }
private void tearDownCar() {
CarScreen screen = CarScreen.current();
if (screen == null) { stopService(new Intent(this, AapService.class)); return; }
DirectProjection.stop();
CarProjection.close();
sendAap(AapService.ACTION_STOP_SERVICE);
screen.stop();
}
private void sendAap(String action) {
try { startService(new Intent(this, AapService.class).setAction(action)); } catch (Exception ignored) {}
}
@Override
public void requestKeyFrame() {
if (direct) { DirectProjection.requestKeyFrame(); return; }
CarScreen screen = CarScreen.current();
if (screen != null) { screen.requestKeyFrame(); CarProjection.redraw(); }
}
@Override
public void touch(int action, float x, float y) {
if (direct) DirectProjection.touch(action, x, y);
else CarProjection.touch(action, x, y);
}
@Override
public void caption(String text) {}
@Override
public void micData(byte[] b, int off, int len) {
MicRecorder.ExternalMic.Sink sink = carMicSink;
if (sink != null) { carMicHeard = true; sink.data(Arrays.copyOfRange(b, off, off + len), len); }
}
@Override
public ScreenGeometry geometry(int carWidth, int carHeight) { return ScreenGeometry.of(this, carWidth, carHeight); }
@Override
public String btAddress() {
String mac = Settings.Secure.getString(getContentResolver(), "bluetooth_address");
return mac == null || mac.isEmpty() ? "00:00:00:00:00:00" : mac.toUpperCase(Locale.US);
}
@Override
public String phoneUuid() {
SharedPreferences p = getSharedPreferences("overtakelink", Context.MODE_PRIVATE);
String id = p.getString("uuid", null);
if (id == null) { id = UUID.randomUUID().toString(); p.edit().putString("uuid", id).apply(); }
return id;
}
@Override
public String phoneBrand() { return Build.MANUFACTURER; }
@Override
public String phoneModel() { return Build.MODEL; }
@Override
public int sdk() { return Build.VERSION.SDK_INT; }
@Override
public void schedule(long delayMs, Runnable task) {
Timer t = timer;
if (t != null) try { t.schedule(new TimerTask() { @Override public void run() { safely("delay", task); } }, delayMs); } catch (Exception ignored) {}
}
@Override
public void scheduleRepeating(long delayMs, long periodMs, Runnable task) {
Timer t = timer;
if (t != null) try { t.schedule(new TimerTask() { @Override public void run() { safely("repeat", task); } }, delayMs, periodMs); } catch (Exception ignored) {}
}
private final class SessionHost implements LinkSession.Host {
volatile LinkSession owner;
volatile Outbox box;
private boolean live() { return owner == session && connected; }
@Override
public void send(byte[] packet) { Outbox b = box; if (b != null && connected) b.offer(packet); }
private void command(Runnable r) { try { commands.execute(() -> safely("cmd", r)); } catch (Exception ignored) {} }
final java.util.concurrent.atomic.AtomicReference pendingScreen = new java.util.concurrent.atomic.AtomicReference<>();
final java.util.concurrent.atomic.AtomicReference pendingVideo = new java.util.concurrent.atomic.AtomicReference<>();
private void criticalCommand(java.util.concurrent.atomic.AtomicReference slot, Runnable r) { slot.set(r); scheduleCritical(); }
@Override
public void startVideo(Packets.Video params) {
Packets.Video v = new Packets.Video();
v.width = params.width; v.height = params.height; v.frameRate = params.frameRate; v.bitRate = params.bitRate; v.frameInterval = params.frameInterval;
criticalCommand(pendingVideo, () -> startVideoFor(owner, v));
}
@Override
public void stopVideo() { criticalCommand(pendingVideo, () -> { synchronized (OvertakeLinkService.this) { if (live()) OvertakeLinkService.this.stopVideo(); } }); }
@Override
public void requestKeyFrame() { command(() -> { synchronized (OvertakeLinkService.this) { if (live()) OvertakeLinkService.this.requestKeyFrame(); } }); }
@Override
public void touch(int action, float x, float y) { if (live()) OvertakeLinkService.this.touch(action, x, y); }
@Override
public void caption(String text) { command(() -> { synchronized (OvertakeLinkService.this) { if (live()) OvertakeLinkService.this.caption(text); } }); }
@Override
public void micData(byte[] b, int off, int len) {
if (carMicSink == null) return;
byte[] copy = Arrays.copyOfRange(b, off, off + len);
command(() -> { synchronized (OvertakeLinkService.this) { if (live()) OvertakeLinkService.this.micData(copy, 0, copy.length); } });
}
@Override
public ScreenGeometry geometry(int carWidth, int carHeight) { return OvertakeLinkService.this.geometry(carWidth, carHeight); }
@Override
public void carScreen(int carWidth, int carHeight) { criticalCommand(pendingScreen, () -> carScreenFor(owner, carWidth, carHeight)); }
@Override
public String btAddress() { return OvertakeLinkService.this.btAddress(); }
@Override
public String phoneUuid() { return OvertakeLinkService.this.phoneUuid(); }
@Override
public String phoneBrand() { return OvertakeLinkService.this.phoneBrand(); }
@Override
public String phoneModel() { return OvertakeLinkService.this.phoneModel(); }
@Override
public int sdk() { return OvertakeLinkService.this.sdk(); }
@Override
public void schedule(long delayMs, Runnable task) { command(() -> { synchronized (OvertakeLinkService.this) { if (live()) OvertakeLinkService.this.schedule(delayMs, task); } }); }
@Override
public void scheduleRepeating(long delayMs, long periodMs, Runnable task) { command(() -> { synchronized (OvertakeLinkService.this) { if (live()) OvertakeLinkService.this.scheduleRepeating(delayMs, periodMs, task); } }); }
}
private final class Outbox {
final LinkedBlockingQueue<byte[]> q = new LinkedBlockingQueue<>();
volatile boolean waitKeyFrame;
void offer(byte[] packet) { if (q.size() < OUT_QUEUE_MAX) q.offer(packet); }
synchronized boolean acceptVideo(boolean keyFrame) {
if (q.size() > VIDEO_QUEUE_HARD_LIMIT) { waitKeyFrame = true; return false; }
if (keyFrame) waitKeyFrame = false;
else if (waitKeyFrame) return false;
else if (q.size() > VIDEO_QUEUE_LIMIT) { waitKeyFrame = true; if (this == outbox) requestKeyFrame(); return false; }
return true;
}
}
}
