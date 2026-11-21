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

/**
 * Сердце OvertakeLink: держит связь с ГУ по USB Accessory (протоколы QDLink и CarbitLink), поднимает экран машины
 * (виртуальный дисплей → H.264 → ГУ) и запускает на нём Android Auto через Open Headunit.
 */
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
        if (!criticalScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            critical.execute(() -> {
                criticalScheduled.set(false);
                SessionHost h = currentHost;
                if (h == null) {
                    return;
                }
                Runnable screen = h.pendingScreen.getAndSet(null);
                if (screen != null) {
                    safely("команда ГУ: экран", screen);
                }
                Runnable video = h.pendingVideo.getAndSet(null);
                if (video != null) {
                    safely("команда ГУ: видео", video);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
        }
    }

    private static final int COMMANDS_MAX = 200;
    private long commandsDropped;
    private final java.util.concurrent.ThreadPoolExecutor commands = new java.util.concurrent.ThreadPoolExecutor(1, 1,
            0, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(COMMANDS_MAX), r -> new Thread(r, "ol-commands"),
            (r, pool) -> {
                if (commandsDropped++ % 100 == 0) {
                    EventLog.add("Команды ГУ: очередь полна, команды отбрасываются. Отброшено: " + commandsDropped);
                }
            });

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
                        EventLog.add("Микрофон машины молчит " + CAR_MIC_WAIT_MS + " мс — до конца подключения пишем с микрофона телефона");
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
            if (on && s != null) {
                s.speech(false);
            }
        }
    };

    public static State state() {
        return state;
    }

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
        EventLog.add("OvertakeLink " + BuildConfig.VERSION_NAME + " (" + BuildConfig.GIT_SHA + ") запущен. Телефон: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
    }

    private void startForegroundWithMicrophone() {
        int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
        }
        try {
