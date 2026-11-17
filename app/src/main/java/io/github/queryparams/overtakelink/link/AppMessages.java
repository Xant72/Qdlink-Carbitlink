package io.github.queryparams.overtakelink.link;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Прикладной слой QDLink: текстовые сообщения «A6A6…» внутри пакетов !BIN типа 3.
 *
 * Формат: "A6A6", flowId %02x, totalLen %08x, len %02x + AppID, len %02x + LogicID,
 * count %02x, затем count × (len %08x + data), в конце CRC16 %04x по всей предыдущей строке.
 * Поле data — JSON: одно значение {"D":0,"T":"s","V":…}
 * или кортеж {"D":1,"T":"(is)","V":[…]}.
 */
public final class AppMessages {
    public static final String PHONE_APP_ID = "QDRIVE_ASSISTANT";

    private AppMessages() {
    }

    public static final class Message {
        public String appId;
        public String logicId;
        public final List<String> data = new ArrayList<>();
        public int flowId;
    }

    public static int crc16(byte[] b, int from, int to) {
        int crc = 0;
        for (int i = from; i < to; i++) {
            for (int mask = 0x80; mask != 0; mask >>= 1) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                if ((b[i] & mask) != 0) {
                    crc ^= 0x1021;
                }
            }
        }
        return crc & 0xffff;
    }

    public static String create(String appId, String logicId, String... data) {
        int appLen = appId.getBytes(StandardCharsets.UTF_8).length;
        int logicLen = logicId.getBytes(StandardCharsets.UTF_8).length;
        int total = appLen + 16 + 2 + logicLen + 2;
        int[] lens = new int[data.length];
        for (int i = 0; i < data.length; i++) {
            lens[i] = data[i].getBytes(StandardCharsets.UTF_8).length;
            total += lens[i] + 8;
        }
        StringBuilder sb = new StringBuilder("A6A6");
        sb.append(String.format("%02x", 0));
        sb.append(String.format("%08x", total + 4));
        sb.append(String.format("%02x", appLen)).append(appId);
        sb.append(String.format("%02x", logicLen)).append(logicId);
        sb.append(String.format("%02x", data.length));
        for (int i = 0; i < data.length; i++) {
            sb.append(String.format("%08x", lens[i])).append(data[i]);
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        sb.append(String.format("%04x", crc16(body, 0, body.length)));
        return sb.toString();
    }

    /** Разбор сообщения; null, если формат или CRC не сошлись. Длины полей — в байтах UTF-8, разбор — по байтам. */
    public static Message parse(byte[] b, int off, int len) {
        try {
            if (len < 20 || !hexAscii(b, off, 4).equalsIgnoreCase("A6A6")) {
                return null;
            }
            int end = off + len;
            int crc = hex(b, end - 4, 4);
            if (crc16(b, off, end - 4) != crc) {
                return null;
            }
            Message m = new Message();
            m.flowId = hex(b, off + 4, 2);
            int p = off + 14;
            int appLen = hex(b, p, 2);
            p += 2;
            m.appId = utf8(b, p, appLen, end - 4);
            p += appLen;
            int logicLen = hex(b, p, 2);
            p += 2;
            m.logicId = utf8(b, p, logicLen, end - 4);
            p += logicLen;
            int count = hex(b, p, 2);
            p += 2;
            for (int i = 0; i < count; i++) {
                int dl = hex(b, p, 8);
                p += 8;
                m.data.add(utf8(b, p, dl, end - 4));
                p += dl;
            }
            return m;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String hexAscii(byte[] b, int from, int n) {
        return new String(b, from, n, StandardCharsets.US_ASCII);
    }

    private static int hex(byte[] b, int from, int n) {
        return Integer.parseInt(hexAscii(b, from, n), 16);
    }

    /** Поле из n байт с from; выходит за тело сообщения (до CRC) — сообщение битое. */
    private static String utf8(byte[] b, int from, int n, int limit) {
        if (n < 0 || from + n > limit) {
            throw new IllegalArgumentException("поле за пределами сообщения");
        }
        return new String(b, from, n, StandardCharsets.UTF_8);
    }

    // ---------- значения в поле data ----------

    public static String unitInt(int v) {
        return "{\"D\":0,\"T\":\"i\",\"V\":" + v + "}";
    }

    public static String unitString(String v) {
        return "{\"D\":0,\"T\":\"s\",\"V\":" + JSONObject.quote(v) + "}";
    }

    /** Кортеж из уже собранных значений, например tuple("(is)", unitInt(2), unitString("")). */
    public static String tuple(String format, String... units) {
        return "{\"D\":1,\"T\":" + JSONObject.quote(format) + ",\"V\":[" + String.join(",", units) + "]}";
    }

    /** Значения из поля данных: для одиночного — один элемент, для кортежа — все по порядку. */
    public static List<Object> values(String data) {
        List<Object> out = new ArrayList<>();
        try {
            JSONObject o = new JSONObject(data);
            Object v = o.opt("V");
            if (v instanceof JSONArray) {
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    JSONObject u = a.optJSONObject(i);
                    out.add(u == null ? a.opt(i) : u.opt("V"));
                }
            } else {
                out.add(v);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Пакет !BIN типа 3: заголовок 512 байт, затем текст, дополнение до 512. */
    public static byte[] packet(String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        int total = Packets.padded(OldPackets.BLOCK + data.length);
        byte[] out = new byte[total];
        ByteBuffer b = ByteBuffer.wrap(out);
        b.put((byte) '!').put((byte) 'B').put((byte) 'I').put((byte) 'N');
        b.putInt(OldPackets.DT_HU_MSG).putInt(total).putInt(OldPackets.BLOCK).putInt(64).putInt(64).putInt(0).putInt(2);
        for (int i = 0; i < 32; i++) {
            b.put((byte) (i + 32));
        }
        b.putInt(64, data.length).putInt(68, 0);
        System.arraycopy(data, 0, out, OldPackets.BLOCK, data.length);
        return out;
    }
}
