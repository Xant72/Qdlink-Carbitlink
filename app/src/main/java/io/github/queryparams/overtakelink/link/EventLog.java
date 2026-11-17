package io.github.queryparams.overtakelink.link;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Журнал: в памяти (для экрана), в logcat и в файл приложения.
 * Файл переживает падения; выгрузка в «Загрузки» перезаписывает один и тот же файл за запуск.
 * Процесс может стартовать до разблокировки телефона (приёмник загрузки), когда файлы приложения
 * ещё недоступны: тогда файл открывается при первой записи после разблокировки.
 * Частые одинаковые сообщения (сердцебиения ГУ, повторы AA — чаще раза в {@link #RAPID_MS}) пишутся полностью в
 * первый раз, дальше — не чаще раза в {@link #REPEAT_WINDOW_MS} со счётчиком; редкие повторы (переподключения
 * кабеля) пишутся каждый раз со своим временем; на диск — пакетами раз в секунду в фоне (ошибки — сразу); файл
 * ограничен {@link #MAX_FILE} и при записи, прежний уходит в .old.
 */
public final class EventLog {
    private static final String TAG = "OL";
    private static final int MAX_LINES = 5000;
    private static final long MAX_FILE = 8L << 20;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private static long version;
    private static File file;
    private static BufferedWriter writer;
    private static Uri exportUri;
    /** Строк в памяти, ещё не записанных в файл. */
    private static int unsaved;
    private static String exportName;
    /** Повтор того же сообщения раньше этого — только в счётчик. */
    private static final long REPEAT_WINDOW_MS = 60_000;
    /** Повтор сворачивается, только если пришёл раньше этого после предыдущего: события сеанса идут реже. */
    private static final long RAPID_MS = 2_000;
    private static final int MAX_TRACKED = 256;
    /** Сообщение → [когда последний раз записано, сколько повторов пропущено с тех пор, когда пришло последний раз]. */
    private static final java.util.LinkedHashMap<String, long[]> recent = new java.util.LinkedHashMap<>(64, 0.75f, true);
    /** Сколько записано в текущий файл (растёт и в долгом процессе — лимит проверяется при записи). */
    private static long fileSize;
    private static android.os.Handler flusher;
    private static boolean flushScheduled;
    private static final Runnable flushTask = EventLog::flushNow;
    /** Выгрузки идут по одной; сам журнал при этом занят только на время снимка. */
    private static final Object EXPORT = new Object();

    private EventLog() {
    }

    public static synchronized void init(Context context) {
        if (file != null) {
            return;
        }
        file = new File(context.getFilesDir(), "overtakelink-log.txt");
        android.os.HandlerThread t = new android.os.HandlerThread("ol-log");
        t.start();
        flusher = new android.os.Handler(t.getLooper());
        open();
        // Падение приложения — в журнал: иначе после вылета в нём не видно, что случилось.
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, e) -> {
            error("ПАДЕНИЕ приложения в потоке «" + thread.getName() + "»", e);
            if (previous != null) {
                previous.uncaughtException(thread, e);
            }
        });
    }

    /** Журнал запущен приложением (в модульных тестах Open Headunit — нет: там нет ни Context, ни logcat). */
    public static synchronized boolean isStarted() {
        return file != null;
    }

    /** Открыть файл, дописав в него то, что накопилось в памяти, пока он был недоступен. */
    private static boolean open() {
        try {
            if (file.length() > MAX_FILE) {
                File old = new File(file.getParentFile(), "overtakelink-log.old.txt");
                old.delete();
                file.renameTo(old);
            }
            writer = new BufferedWriter(new FileWriter(file, true));
            fileSize = file.length();
            if (unsaved > 0) {
                int skip = Math.max(0, lines.size() - unsaved);
                for (String line : lines) {
                    if (skip-- > 0) {
                        continue;
                    }
                    writer.write(line);
                    writer.newLine();
                }
                writer.flush();
                unsaved = 0;
            }
            return true;
        } catch (IOException e) {
            writer = null;
            return false;
        }
    }

    public static synchronized void add(String msg) {
        long now = System.currentTimeMillis();
        long[] seen = recent.get(msg);
        if (seen != null && now - seen[2] < RAPID_MS && now - seen[0] < REPEAT_WINDOW_MS) {
            seen[1]++;
            seen[2] = now;
            return;
        }
        if (seen != null && seen[1] > 0) {
            msg = msg + "\n    (повторилось ещё " + seen[1] + " раз за " + (now - seen[0]) / 1000 + " с)";
        }
        if (seen == null) {
            seen = new long[3];
            recent.put(msg, seen);
            if (recent.size() > MAX_TRACKED) {
                java.util.Iterator<java.util.Map.Entry<String, long[]>> it = recent.entrySet().iterator();
                java.util.Map.Entry<String, long[]> eldest = it.next();
                it.remove();
                if (eldest.getValue()[1] > 0) {
                    write("(«" + firstLine(eldest.getKey()) + "» повторилось ещё " + eldest.getValue()[1] + " раз)", false);
                }
            }
        }
        seen[0] = now;
        seen[1] = 0;
        seen[2] = now;
        write(msg, msg.startsWith("ОШИБКА") || msg.startsWith("ПАДЕНИЕ") || msg.startsWith("AA ОШИБКА"));
    }

    private static String firstLine(String s) {
        int nl = s.indexOf('\n');
        return nl < 0 ? s : s.substring(0, nl);
    }

    /** Записать строку: в память, logcat и файл; urgent — сразу на диск (ошибка, падение). */
    private static void write(String msg, boolean urgent) {
        String line = TIME.format(new Date()) + "  " + msg;
        Log.i(TAG, msg);
        lines.addLast(line);
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
        version++;
        unsaved = Math.min(unsaved + 1, MAX_LINES);
        if (file == null || (writer == null && !open())) {
            return;
        }
        try {
            if (fileSize > MAX_FILE) {
                // Долгий процесс: файл не растёт бесконечно — прежний уходит в .old.
                writer.close();
                writer = null;
                if (!open()) {
                    return;
                }
            }
            writer.write(line);
            writer.newLine();
            fileSize += line.length() * 2L + 1;
            unsaved = 0;
            if (urgent || flusher == null) {
                writer.flush();
            } else if (!flushScheduled) {
                flushScheduled = true;
                flusher.postDelayed(flushTask, 1000);
            }
        } catch (IOException e) {
            writer = null;
        }
    }

    private static synchronized void flushNow() {
        flushScheduled = false;
        if (writer != null) {
            try {
                writer.flush();
            } catch (IOException e) {
                writer = null;
            }
        }
    }

    /** Пропущенные повторы — итогом в журнал (перед выгрузкой: иначе последняя минута повторов не видна). */
    private static void writeRepeatTotals() {
        for (java.util.Map.Entry<String, long[]> e : recent.entrySet()) {
            if (e.getValue()[1] > 0) {
                write("(«" + firstLine(e.getKey()) + "» повторилось ещё " + e.getValue()[1] + " раз)", false);
                e.getValue()[1] = 0;
            }
        }
    }

    /**
     * Новое подключение машины: итоги повторов прошлого — в журнал, счёт заново. Строки подключения при быстром
     * переподключении иначе свернулись бы в счётчик без своего времени.
     */
    public static synchronized void newConnection() {
        writeRepeatTotals();
        recent.clear();
    }

    public static void error(String what, Throwable t) {
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        add("ОШИБКА: " + what + "\n" + sw);
    }

    public static synchronized long version() {
        return version;
    }

    public static synchronized String tail(int count) {
        StringBuilder sb = new StringBuilder();
        int skip = Math.max(0, lines.size() - count);
        for (String line : lines) {
            if (skip-- > 0) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    public static synchronized void clear() {
        lines.clear();
        version++;
    }

    /** Копирует файл журнала в Download/OvertakeLink/ (один файл на запуск приложения). */
    public static String export(Context context) throws Exception {
        return export(context, false);
    }

    private static String export(Context context, boolean retried) throws Exception {
        synchronized (EXPORT) {
            return exportLocked(context, retried);
        }
    }

    private static String exportLocked(Context context, boolean retried) throws Exception {
        // Под журналом — только снимок: итоги повторов, сброс буфера и длина файла. Чтение и запись копии — вне его,
        // чтобы потоки USB/AA и экран не ждали выгрузку.
        long len;
        String memory = null;
        java.io.FileInputStream opened = null;
        java.io.FileInputStream openedOld = null;
        long oldLen = 0;
        synchronized (EventLog.class) {
            writeRepeatTotals();
            if (writer != null) {
                writer.flush();
            }
            len = file != null && unsaved == 0 && file.exists() ? file.length() : -1;
            if (len < 0) {
                memory = tail(MAX_LINES);
            } else {
                // Открыт под журналом: ротация потом лишь переименует этот же файл — читаем его, а не новый.
                // Очистка (wipeFile) ждёт конца выгрузки.
                opened = new java.io.FileInputStream(file);
                // Прежняя часть (файл перевалил за лимит и ушёл в .old) — тоже в выгрузку, открыта под тем же замком:
                // ротация между открытиями не подменит её текущей частью.
                File old = new File(file.getParentFile(), "overtakelink-log.old.txt");
                if (old.exists()) {
                    oldLen = old.length();
                    openedOld = new java.io.FileInputStream(old);
                }
            }
        }
        byte[] data;
        if (memory != null) {
            data = memory.getBytes(StandardCharsets.UTF_8);
        } else {
            byte[] head = new byte[0];
            if (openedOld != null) {
                head = new byte[(int) Math.min(oldLen, MAX_FILE * 2)];
                try (java.io.FileInputStream in = openedOld) {
                    int off = 0;
                    int n;
                    while (off < head.length && (n = in.read(head, off, head.length - off)) > 0) {
                        off += n;
                    }
                    if (off < head.length) {
                        head = java.util.Arrays.copyOf(head, off);
                    }
                }
            }
            data = new byte[(int) Math.min(len, MAX_FILE * 2)];
            try (java.io.FileInputStream in = opened) {
                int off = 0;
                int n;
                while (off < data.length && (n = in.read(data, off, data.length - off)) > 0) {
                    off += n;
                }
                if (off < data.length) {
                    data = java.util.Arrays.copyOf(data, off);
                }
            }
            if (head.length > 0) {
                byte[] all = new byte[head.length + data.length];
                System.arraycopy(head, 0, all, 0, head.length);
                System.arraycopy(data, 0, all, head.length, data.length);
                data = all;
            }
        }
        if (exportUri == null) {
            exportName = "overtakelink-log-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, exportName);
            values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OvertakeLink");
            exportUri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (exportUri == null) {
                throw new IllegalStateException("MediaStore не создал файл");
            }
            pruneExports(context);
        }
        try (OutputStream out = context.getContentResolver().openOutputStream(exportUri, "wt")) {
            out.write(data);
        } catch (java.io.FileNotFoundException | IllegalStateException | SecurityException e) {
            // Файл удалили из «Загрузок» — запись MediaStore пропала: создать заново.
            if (!retried) {
                exportUri = null;
                return exportLocked(context, true);
            }
            throw e;
        }
        return "Download/OvertakeLink/" + exportName;
    }

    /** Сколько выгрузок журнала держать в «Загрузках/OvertakeLink» (по одной на запуск): старше — удаляем. */
    private static final int KEEP_EXPORTS = 5;

    /** «Загрузки» не копят журналы бесконечно: остаются последние {@link #KEEP_EXPORTS} своих выгрузок. */
    private static void pruneExports(Context context) {
        String[] cols = {MediaStore.Downloads._ID};
        // Текущую выгрузку не трогаем, даже если её дата «старше» (часы перевели назад).
        long current = exportUri == null ? -1 : android.content.ContentUris.parseId(exportUri);
        String where = MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + " LIKE ? AND "
                + MediaStore.Downloads._ID + "<>?";
        String[] args = {Environment.DIRECTORY_DOWNLOADS + "/OvertakeLink/", "overtakelink-log-%", String.valueOf(current)};
        try (android.database.Cursor c = context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                cols, where, args, MediaStore.Downloads.DATE_ADDED + " DESC")) {
            if (c == null) {
                return;
            }
            int n = 0;
            while (c.moveToNext()) {
                if (++n < KEEP_EXPORTS) {
                    continue;
                }
                Uri old = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0));
                try {
                    context.getContentResolver().delete(old, null, null);
                } catch (RuntimeException ignored) {
                    // Не своя (после переустановки) — Android не даст удалить; не беда.
                }
            }
        } catch (RuntimeException e) {
            Log.i(TAG, "старые выгрузки журнала не убраны: " + e);
        }
    }

    /** Полностью очищает файл журнала (по кнопке); идущая выгрузка сначала дочитает его. */
    public static void wipeFile() {
        synchronized (EXPORT) {
            wipeLocked();
        }
    }

    private static synchronized void wipeLocked() {
        if (file == null) {
            return;
        }
        try {
            if (writer != null) {
                writer.close();
            }
            writer = new BufferedWriter(new FileWriter(file, false));
            fileSize = 0;
        } catch (IOException ignored) {
        }
        // Прежняя часть журнала тоже уходит в выгрузку — очищенное не должно в неё вернуться.
        new File(file.getParentFile(), "overtakelink-log.old.txt").delete();
        recent.clear();
        lines.clear();
        version++;
    }

    public static String hex(byte[] data, int off, int len, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(len, max);
        for (int i = 0; i < n; i++) {
            if (i > 0 && i % 32 == 0) {
                sb.append("\n    ");
            } else if (i > 0) {
                sb.append(' ');
            }
            sb.append(String.format(Locale.US, "%02x", data[off + i] & 0xff));
        }
        if (len > max) {
            sb.append(" … (+").append(len - max).append(" байт)");
        }
        return sb.toString();
    }

    public static String text(byte[] data, int off, int len) {
        return new String(data, off, len, StandardCharsets.UTF_8).replaceAll("[^\\x20-\\x7e\\u0400-\\u04ff]", "·");
    }
}
