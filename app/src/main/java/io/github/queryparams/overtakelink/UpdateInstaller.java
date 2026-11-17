package io.github.queryparams.overtakelink;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import io.github.queryparams.overtakelink.link.EventLog;

/**
 * Обновление в один тап: скачать APK новой версии и открыть системную установку. Android сам
 * проверяет, что обновление подписано тем же ключом; если GitHub сообщил SHA-256 файла, сверяем и его.
 */
final class UpdateInstaller {

    interface Progress {
        /** Состояние для кнопки; вызывается в главном потоке. null — загрузка закончилась. */
        void show(String text);
    }

    private static volatile boolean running;
    /** Окно OvertakeLink на экране (из фона Android не даёт открыть установщик). */
    private static volatile boolean visible;
    /** Скачанный и проверенный APK ждёт возвращения приложения на экран. */
    private static volatile File pending;

    /**
     * Убрать скачанный APK, если он ничего не ждёт: установили (процесс новый), загрузка оборвалась или так и не
     * поставили — файл в кэше больше не нужен и только занимает место.
     */
    static void cleanup(Context context) {
        // Отдан установщику в этом процессе — он может ещё читать файл; после обновления процесс новый и флаг снят.
        if (running || pending != null || handedOver) {
            return;
        }
        File dir = new File(context.getCacheDir(), "update");
        File[] files = dir.listFiles();
        for (File f : files == null ? new File[0] : files) {
            f.delete();
        }
    }

    /** Окно на экране / ушло с экрана; вернулось и обновление ждёт — открыть установщик. */
    static void visible(Activity activity, boolean on) {
        visible = on;
        File apk = pending;
        if (on && apk != null) {
            pending = null;
            if (apk.isFile()) {
                install(activity.getApplicationContext(), apk);
            }
        }
    }

    /** APK отдан установщику в этом процессе. */
    private static volatile boolean handedOver;

    private static void install(Context app, File apk) {
        handedOver = true;
        Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", apk);
        app.startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private UpdateInstaller() {
    }

    static void start(Activity activity, Progress progress) {
        String url = UpdateCheck.apkUrl(activity);
        if (url == null) {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(UpdateCheck.downloadUrl(activity))));
            return;
        }
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            // Разрешение «Установка неизвестных приложений» выдаётся пользователем в настройках.
            progress.show(activity.getString(R.string.ol_update_allow));
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName())));
            return;
        }
        if (running) {
            return;
        }
        running = true;
        Handler main = new Handler(Looper.getMainLooper());
        String expected = UpdateCheck.apkSha256(activity);
        File dir = new File(activity.getCacheDir(), "update");
        File apk = new File(dir, "OvertakeLink.apk");
        new Thread(() -> {
            String error = null;
            try {
                download(url, dir, apk, expected, percent -> main.post(() -> progress.show(activity.getString(R.string.ol_update_progress, percent))));
            } catch (Exception e) {
                error = e.getMessage();
                EventLog.add("Обновление: загрузка не удалась (" + error + ")");
                // Недокачанный файл не оставляем: следующая загрузка всё равно начнётся заново.
                apk.delete();
            }
            String failed = error;
            main.post(() -> {
                // «Загрузка идёт» снимаем здесь, в главном потоке, вместе с передачей файла под защиту (pending или
                // установщик): cleanup() из onCreate тоже на главном и не попадёт между ними.
                running = false;
                if (failed != null) {
                    progress.show(activity.getString(R.string.ol_update_failed));
                    return;
                }
                progress.show(null);
                // Окно свернули, пока шла загрузка: из фона Android установщик не откроет — ждём возвращения.
                if (!visible) {
                    pending = apk;
                    EventLog.add("Обновление: скачано, установщик откроется, когда приложение снова на экране");
                    return;
                }
                install(activity.getApplicationContext(), apk);
            });
        }, "ol-update-download").start();
    }

    private interface Percent {
        void at(int percent);
    }

    private static void download(String url, File dir, File apk, String expectedSha256, Percent percent) throws Exception {
        dir.mkdirs();
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15_000);
        c.setReadTimeout(30_000);
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) {
                throw new IllegalStateException("HTTP " + c.getResponseCode());
            }
            long total = c.getContentLengthLong();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(apk)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int last = -1;
                for (int n; (n = in.read(buf)) > 0; ) {
                    out.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    int p = total > 0 ? (int) (done * 100 / total) : 0;
                    if (p != last) {
                        last = p;
                        percent.at(p);
                    }
                }
            }
            if (expectedSha256 != null) {
                StringBuilder hex = new StringBuilder();
                for (byte b : sha.digest()) {
                    hex.append(String.format(Locale.US, "%02x", b));
                }
                if (!hex.toString().equalsIgnoreCase(expectedSha256)) {
                    apk.delete();
                    throw new IllegalStateException("контрольная сумма не совпала");
                }
            }
        } finally {
            c.disconnect();
        }
    }
}
