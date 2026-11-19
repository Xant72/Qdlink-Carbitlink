package io.github.queryparams.overtakelink;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.andrerinas.openheadunit.R;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

import io.github.queryparams.overtakelink.link.EventLog;

/**
 * Обновление в один тап: скачать APK новой версии и открыть системную установку. Android сам проверяет, что обновление
 * подписано тем же ключом; если GitHub сообщил SHA-256 файла, сверяем и его.
 * Качает системный загрузчик Android: идёт и в свёрнутом приложении (своему потоку Android урезает сеть в фоне),
 * докачивает после обрыва, прогресс — в шторке. Загрузчика нет, он отказал сразу или не скачал — качаем сами, как
 * раньше, во внутренний кэш.
 * Скачанный и проверенный файл новой версии лежит до её установки: установку отменили — второй раз не качаем.
 */
final class UpdateInstaller {

    interface Progress {
        /** Состояние для кнопки; вызывается в главном потоке. null — обычная надпись (см. {@link #label}). */
        void show(String text);
    }

    /** Загрузка своим потоком (запасной путь) идёт. */
    private static volatile boolean running;
    /** Окно OvertakeLink на экране (из фона Android не даёт открыть установщик). */
    private static volatile boolean visible;
    /** Скачанный и проверенный APK ждёт возвращения приложения на экран. */
    private static volatile File pending;
    /** APK отдан установщику в этом процессе. */
    private static volatile boolean handedOver;
    /**
     * Кнопка, которой показываем ход загрузки (экран перерисовывается — кнопка новая). Слабая ссылка: закрытый экран
     * не держим, живую кнопку держит её же обработчик нажатия. Главный поток.
     */
    private static java.lang.ref.WeakReference<Progress> shown = new java.lang.ref.WeakReference<>(null);
    /** Идёт сверка скачанного файла. Главный поток. */
    private static boolean verifying;
    /** Номер загрузки системного загрузчика — в настройках: переживает перезапуск приложения. */
    private static final String PREF_ID = "updateDownloadId";
    private static final String PREF_VERSION = "updateDownloadVersion";
    /** SHA-256 от GitHub на момент нажатия: релиз перевыложат во время загрузки — сверяем с ней. */
    private static final String PREF_SHA = "updateDownloadSha256";
    private static final long POLL_MS = 500;
    /** Опрос загрузчика Android не удался подряд; после {@link #QUERY_FAILS} — забыть загрузку. Главный поток. */
    private static int queryFails;
    private static final int QUERY_FAILS = 3;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static final Runnable poll = UpdateInstaller::poll;

    private UpdateInstaller() {
    }

    /**
     * Убрать файлы, которые ничего не ждут: версия уже стоит или вышла новее, недокачанное своим потоком. Файл
     * доступной версии оставляем — установку могли отменить, второй раз не качаем.
     */
    static void cleanup(Context context) {
        // Отдан установщику в этом процессе — он может ещё читать файл; после обновления процесс новый и флаг снят.
        if (running || pending != null || handedOver) {
            return;
        }
        String version = UpdateCheck.availableVersion(context);
        SharedPreferences p = prefs(context);
        long id = p.getLong(PREF_ID, -1);
        if (id >= 0 && (version == null || !version.equals(p.getString(PREF_VERSION, null)))) {
            // Загрузка версии, которую уже не ставить: её файл — тоже.
            remove(context, id);
            forget(context);
            id = -1;
            lastText = null;
        }
        // Недокачанное своим потоком (процесс убили посреди загрузки) и файлы прежних версий. Пока качает загрузчик
        // Android — его папку не трогаем.
        deleteAll(new File(context.getCacheDir(), "update"), version == null ? null : cached(context, version));
        File ext = context.getExternalFilesDir(null);
        if (ext != null && id < 0) {
            deleteAll(new File(ext, "update"), version == null ? null : target(context, version));
        }
    }

    private static void deleteAll(File dir, File keep) {
        File[] files = dir.listFiles();
        for (File f : files == null ? new File[0] : files) {
            if (!f.equals(keep)) {
                f.delete();
            }
        }
    }

    /** Окно на экране / ушло с экрана; вернулось и обновление ждёт — открыть установщик. */
    static void visible(Activity activity, boolean on) {
        visible = on;
        app = activity.getApplicationContext();
        if (!on) {
            main.removeCallbacks(poll);
            return;
        }
        File apk = pending;
        if (apk != null) {
            pending = null;
            // Пока окна не было, могла выйти версия новее — эту уже не ставим.
            if (apk.isFile() && current(app, apk)) {
                install(app, apk);
            }
            return;
        }
        // Загрузка шла, пока приложения не было на экране (или его закрывали): досмотреть её.
        if (prefs(app).getLong(PREF_ID, -1) >= 0) {
            main.removeCallbacks(poll);
            main.post(poll);
        }
    }

    /** Надпись кнопки обновления: скачанное ждёт установки — «Установить», иначе — «Скачать и установить». */
    static String label(Context context) {
        String version = UpdateCheck.availableVersion(context);
        boolean ready = version != null && UpdateCheck.apkSha256(context) != null && found(context, version) != null
                && prefs(context).getLong(PREF_ID, -1) < 0 && !running && !verifying;
        return context.getString(ready ? R.string.ol_update_install_ready : R.string.ol_update_install);
    }

    /** Экран перерисован — ход загрузки показывать новой кнопкой, сразу с последним состоянием. */
    static void attach(Progress progress) {
        shown = new java.lang.ref.WeakReference<>(progress);
        // Ошибка или ход загрузки прежней версии к карточке новой не относятся.
        if (lastText != null && app != null && lastVersion != null && lastVersion.equals(UpdateCheck.availableVersion(app))) {
            progress.show(lastText);
        }
    }

    /** Последнее, что показали на кнопке (null — обычная надпись), и для какой версии шла операция. Главный поток. */
    private static String lastText;
    private static String lastVersion;
    /** Версия, которую сейчас качаем или сверяем. Главный поток. */
    private static String opVersion;

    private static void show(String text) {
        lastText = text;
        lastVersion = opVersion;
        // Карточка уже про версию новее — ход и ошибки прежней на неё не выводим.
        String now = app == null ? null : UpdateCheck.availableVersion(app);
        Progress p = shown.get();
        if (p != null && (text == null || opVersion == null || opVersion.equals(now))) {
            p.show(text);
        }
    }

    /** Убрать загрузку из загрузчика Android (и её недокачанный файл); загрузчик отключён — ничего не делать. */
    private static void remove(Context context, long id) {
        try {
            DownloadManager dm = context.getSystemService(DownloadManager.class);
            if (dm != null) {
                dm.remove(id);
            }
        } catch (RuntimeException e) {
            EventLog.add("Обновление: загрузчик Android недоступен (" + e.getMessage() + ")");
        }
    }

    /** Файл загрузчика Android, пока он качает; готовый переименовываем в {@link #target}, запись загрузчика убираем. */
    private static File dmFile(File apk) {
        return new File(apk.getPath() + ".download");
    }

    private static void install(Context context, File apk) {
        handedOver = true;
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", apk);
        context.startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    /** Файл версии готов: окно на экране — установщик сейчас, нет — когда вернётся. Главный поток. */
    private static void ready(Context context, File apk) {
        show(null);
        if (!visible) {
            pending = apk;
            EventLog.add("Обновление: скачано, установщик откроется, когда приложение снова на экране");
            return;
        }
        install(context, apk);
    }

    static void start(Activity activity, Progress progress) {
        shown = new java.lang.ref.WeakReference<>(progress);
        app = activity.getApplicationContext();
        String url = UpdateCheck.apkUrl(activity);
        String version = UpdateCheck.availableVersion(activity);
        if (url == null || version == null) {
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
        if (running || verifying) {
            return;
        }
        opVersion = version;
        long id = prefs(app).getLong(PREF_ID, -1);
        if (id >= 0 && version.equals(prefs(app).getString(PREF_VERSION, null))) {
            // Системный загрузчик уже качает эту версию — только показать ход.
            main.removeCallbacks(poll);
            main.post(poll);
            return;
        }
        if (id >= 0) {
            // Качается прежняя версия — её отменить, качать эту.
            remove(app, id);
            forget(app);
        }
        File apk = target(app, version);
        String expected = UpdateCheck.apkSha256(app);
        // Без суммы от GitHub скачанное раньше не сверить — качаем заново, как до переиспользования.
        File have = expected == null ? null : found(app, version);
        if (have != null) {
            // Скачали раньше (установку отменили или прервали): сверить и ставить, не качая заново.
            verifyThen(have, expected, "Обновление: уже скачано — открываем установку", () -> download(url, version, apk, expected));
            return;
        }
        download(url, version, apk, expected);
    }

    /** Системным загрузчиком; его нет или он отказал — своим потоком. Главный поток. */
    private static void download(String url, String version, File apk, String expected) {
        apk.delete();
        DownloadManager dm = app.getSystemService(DownloadManager.class);
        File dir = apk.getParentFile();
        if (dm != null && dir != null && dir.getParentFile() != null && dir.getParentFile().equals(app.getExternalFilesDir(null))) {
            try {
                dir.mkdirs();
                dmFile(apk).delete();
                long id = dm.enqueue(new DownloadManager.Request(Uri.parse(url))
                        .setTitle("OvertakeLink " + version)
                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                        .setDestinationInExternalFilesDir(app, null, "update/" + dmFile(apk).getName()));
                if (id < 0) {
                    throw new IllegalStateException("загрузка не создалась");
                }
                prefs(app).edit().putLong(PREF_ID, id).putString(PREF_VERSION, version).putString(PREF_SHA, expected).apply();
                EventLog.add("Обновление: качает загрузчик Android (" + version + ")");
                show(app.getString(R.string.ol_update_progress, 0));
                main.removeCallbacks(poll);
                main.post(poll);
                return;
            } catch (RuntimeException e) {
                // Загрузчик выключен в настройках телефона или недоступен — качаем сами.
                EventLog.add("Обновление: загрузчик Android недоступен (" + e.getMessage() + ") — качаем сами");
            }
        }
        downloadSelf(url, cached(app, version), expected);
    }

    /** Ход загрузки системным загрузчиком, пока окно на экране; закончилась — сверить и ставить. Главный поток. */
    private static void poll() {
        Context context = app;
        if (context == null) {
            return;
        }
        SharedPreferences p = prefs(context);
        long id = p.getLong(PREF_ID, -1);
        String version = p.getString(PREF_VERSION, null);
        String saved = p.getString(PREF_SHA, null);
        String sha = saved != null ? saved : UpdateCheck.apkSha256(context);
        opVersion = version;
        DownloadManager dm = context.getSystemService(DownloadManager.class);
        if (id < 0 || dm == null) {
            return;
        }
        int status = -1;
        int reason = 0;
        long done = 0;
        long total = -1;
        try (Cursor c = dm.query(new DownloadManager.Query().setFilterById(id))) {
            if (c == null) {
                throw new IllegalStateException("пустой ответ");
            }
            if (c.moveToFirst()) {
                status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                reason = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES));
            }
        } catch (RuntimeException e) {
            // Сбой опроса — не отмена: скачанное не трогаем, спросим позже. Не отвечает раз за разом — забыть загрузку:
            // следующее нажатие пойдёт запасным путём, кнопка не застрянет.
            EventLog.add("Обновление: загрузчик Android не ответил (" + e.getMessage() + ")");
            if (++queryFails >= QUERY_FAILS) {
                queryFails = 0;
                remove(context, id);
                forget(context);
                String url = UpdateCheck.apkUrl(context);
                if (url != null && version != null && version.equals(UpdateCheck.availableVersion(context))) {
                    downloadSelf(url, cached(context, version), sha);
                } else {
                    show(null);
                }
            } else if (visible) {
                main.postDelayed(poll, POLL_MS * 4);
            }
            return;
        }
        queryFails = 0;
        File apk = version == null ? null : target(context, version);
        if (apk != null && !version.equals(UpdateCheck.availableVersion(context))) {
            // Пока качали, вышла версия новее (или эта уже стоит): эту не ставим.
            remove(context, id);
            forget(context);
            EventLog.add("Обновление: загрузка " + version + " больше не нужна — отменена");
            show(null);
            return;
        }
        if (status == DownloadManager.STATUS_SUCCESSFUL && apk != null) {
            // Файл — себе, запись загрузчика — убрать: сам загрузчик со временем может подчистить свой файл.
            File got = dmFile(apk);
            // Уже переименовали, но процесс убили до удаления записи — файл на месте.
            boolean moved = got.renameTo(apk) || (!got.exists() && apk.isFile());
            remove(context, id);
            forget(context);
            if (!moved) {
                // Забрать файл не вышло (общая память только для чтения) — качаем сами во внутренний кэш.
                apk.delete();
                String url = UpdateCheck.apkUrl(context);
                if (url == null) {
                    show(context.getString(R.string.ol_update_failed));
                    return;
                }
                EventLog.add("Обновление: скачанный загрузчиком файл не забрать — качаем сами");
                downloadSelf(url, cached(context, version), sha);
                return;
            }
            EventLog.add("Обновление: скачано загрузчиком Android");
            verifyThen(apk, sha, null, null);
            return;
        }
        if (status == DownloadManager.STATUS_FAILED && apk != null && UpdateCheck.apkUrl(context) != null) {
            // Загрузчик не справился (не записать файл, сеть): качаем сами во внутренний кэш, как до загрузчика.
            remove(context, id);
            forget(context);
            EventLog.add("Обновление: загрузчик Android не скачал (причина " + reason + ") — качаем сами");
            downloadSelf(UpdateCheck.apkUrl(context), cached(context, version), sha);
            return;
        }
        if (status == DownloadManager.STATUS_FAILED || status == -1 || apk == null) {
            // Загрузку отменили в шторке: файл недокачан — убрать, кнопка снова «скачать».
            remove(context, id);
            forget(context);
            EventLog.add("Обновление: загрузчик Android не скачал" + (status == -1 ? " (загрузку отменили)" : " (причина " + reason + ")"));
            show(context.getString(R.string.ol_update_failed));
            return;
        }
        int percent = total > 0 ? (int) (done * 100 / total) : 0;
        boolean noNetwork = status == DownloadManager.STATUS_PAUSED && (reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK
                || reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI);
        show(noNetwork ? context.getString(R.string.ol_update_waiting, percent) : context.getString(R.string.ol_update_progress, percent));
        if (visible) {
            main.postDelayed(poll, POLL_MS);
        }
    }

    /**
     * Сверить файл (SHA-256, если GitHub его сообщил) в своём потоке и ставить. Не совпал — удалить и, если есть
     * чем, скачать заново (orElse), иначе — «не скачалось». Главный поток.
     */
    private static void verifyThen(File apk, String expected, String note, Runnable orElse) {
        verifying = true;
        show(app.getString(R.string.ol_update_checking_file));
        Context context = app;
        new Thread(() -> {
            boolean ok;
            try {
                ok = expected == null || expected.equalsIgnoreCase(sha256(apk));
            } catch (Exception e) {
                ok = false;
            }
            boolean good = ok;
            main.post(() -> {
                verifying = false;
                if (good && !current(context, apk)) {
                    // Пока сверяли, вышла версия новее (или эта уже стоит) — эту не ставим.
                    apk.delete();
                    show(null);
                    return;
                }
                if (good) {
                    if (note != null) {
                        EventLog.add(note);
                    }
                    ready(context, apk);
                    return;
                }
                apk.delete();
                EventLog.add("Обновление: контрольная сумма не совпала — файл удалён");
                // Пока сверяли, вышла версия новее — эту заново не качаем.
                if (!current(context, apk)) {
                    show(null);
                } else if (orElse != null) {
                    orElse.run();
                } else {
                    show(context.getString(R.string.ol_update_failed));
                }
            });
        }, "ol-update-verify").start();
    }

    /** Запасной путь — своим потоком, как до загрузчика Android. Главный поток. */
    private static void downloadSelf(String url, File apk, String expected) {
        running = true;
        Context context = app;
        show(context.getString(R.string.ol_update_progress, 0));
        new Thread(() -> {
            String error = null;
            try {
                download(url, apk, expected, percent -> main.post(() -> show(context.getString(R.string.ol_update_progress, percent))));
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
                    show(context.getString(R.string.ol_update_failed));
                    return;
                }
                if (!current(context, apk)) {
                    // Пока качали, вышла версия новее (или эта уже стоит) — эту не ставим.
                    apk.delete();
                    show(null);
                    return;
                }
                ready(context, apk);
            });
        }, "ol-update-download").start();
    }

    /** Куда качает загрузчик Android: папка приложения в общей памяти (её он видит); её нет — кэш. */
    private static File target(Context context, String version) {
        File ext = context.getExternalFilesDir(null);
        return ext != null ? new File(new File(ext, "update"), name(version)) : cached(context, version);
    }

    /** Куда качаем сами: внутренний кэш — пишется, даже когда общая память недоступна. */
    private static File cached(Context context, String version) {
        return new File(new File(context.getCacheDir(), "update"), name(version));
    }

    private static String name(String version) {
        // Только безопасные символы: имя идёт и в путь загрузчика Android, где %XX раскрылось бы.
        return "OvertakeLink-" + version.replaceAll("[^A-Za-z0-9._-]", "_") + ".apk";
    }

    /** Файл — доступной сейчас версии (не установленной и не устаревшей). */
    private static boolean current(Context context, File apk) {
        String now = UpdateCheck.availableVersion(context);
        return now != null && (apk.equals(target(context, now)) || apk.equals(cached(context, now)));
    }

    /** Скачанный раньше файл версии (загрузчиком или сами) или null. */
    private static File found(Context context, String version) {
        File t = target(context, version);
        if (t.isFile()) {
            return t;
        }
        File c = cached(context, version);
        return c.isFile() ? c : null;
    }

    private static void forget(Context context) {
        prefs(context).edit().remove(PREF_ID).remove(PREF_VERSION).remove(PREF_SHA).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("overtakelink", Context.MODE_PRIVATE);
    }

    private static String sha256(File f) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                sha.update(buf, 0, n);
            }
        }
        return hex(sha.digest());
    }

    private static String hex(byte[] digest) {
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format(Locale.US, "%02x", b));
        }
        return hex.toString();
    }

    private interface Percent {
        void at(int percent);
    }

    /** Во временный файл: процесс убьют посреди загрузки — недокачанное не сойдёт за скачанное. */
    private static void download(String url, File apk, String expectedSha256, Percent percent) throws Exception {
        File dir = apk.getParentFile();
        if (dir != null) {
            dir.mkdirs();
        }
        File part = new File(apk.getPath() + ".part");
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
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(part)) {
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
            if (expectedSha256 != null && !hex(sha.digest()).equalsIgnoreCase(expectedSha256)) {
                throw new IllegalStateException("контрольная сумма не совпала");
            }
            if (!part.renameTo(apk)) {
                throw new IllegalStateException("не удалось сохранить файл");
            }
        } finally {
            part.delete();
            c.disconnect();
        }
    }
}
