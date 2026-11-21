package io.github.queryparams.overtakelink.carbit;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class AdbPacket {
    static final int AUTH = 1213486401;
    static final int CLSE = 1163086915;
    static final int CNXN = 1314410051;
    static final int MAX_PAYLOAD = 262144;
    static final int OKAY = 1497451343;
    static final int OPEN = 1313165391;
    static final int VERSION = 16777216;
    static final int WRTE = 1163154007;

    final int arg0;
    final int arg1;
    final int command;
    final int declaredChecksum;
    final byte[] payload;

    AdbPacket(int command, int arg0, int arg1, byte[] payload) {
        this(command, arg0, arg1, payload, payload == null ? 0 : checksum(payload));
    }

    private AdbPacket(int command, int arg0, int arg1, byte[] payload, int declaredChecksum) {
        this.command = command;
        this.arg0 = arg0;
        this.arg1 = arg1;
        this.payload = payload == null ? new byte[0] : payload;
        this.declaredChecksum = declaredChecksum;
    }

    static final class AoaReader {
        private final InputStream input;
        private int zeroRecords;

        AoaReader(InputStream inputStream) {
            this.input = inputStream;
        }

        int zeroRecords() {
            return this.zeroRecords;
        }

        AdbPacket read() throws IOException {
            int i;
            do {
                byte[] header = AdbPacket.readHeader(this.input);
                if (header == null) {
                    return null;
                }
                for (byte b : header) {
                    if (b != 0) {
                        return AdbPacket.readBody(this.input, header, true, false);
                    }
                }
                i = this.zeroRecords + 1;
                this.zeroRecords = i;
            } while (i <= 4);
            throw new IOException("Carbit ADB zero-header limit exceeded");
        }
    }

    private static byte[] readHeader(InputStream inputStream) throws IOException {
        byte[] bArr = new byte[24];
        int i = inputStream.read();
        if (i < 0) {
            return null;
        }
        bArr[0] = (byte) i;
        readFully(inputStream, bArr, 1, 23);
        return bArr;
    }

    private static AdbPacket readBody(InputStream inputStream, byte[] bArr, boolean skipChecksum, boolean allowZeroChecksum) throws IOException {
        ByteBuffer bb = ByteBuffer.wrap(bArr).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = bb.getInt();
        int a0 = bb.getInt();
        int a1 = bb.getInt();
        int len = bb.getInt();
        int csum = bb.getInt();
        if (bb.getInt() != (~cmd)) {
            throw new IOException("Invalid ADB command magic");
        }
        if (len < 0 || len > MAX_PAYLOAD) {
            throw new IOException("ADB payload exceeds limit");
        }
        byte[] data = new byte[len];
        readFully(inputStream, data, 0, len);
        if (!skipChecksum && ((!allowZeroChecksum || csum != 0) && checksum(data) != csum)) {
            throw new IOException("Invalid ADB payload checksum");
        }
        return new AdbPacket(cmd, a0, a1, data, csum);
    }

    void write(OutputStream outputStream, boolean zeroChecksum) throws IOException {
        ByteBuffer bb = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(this.command)
          .putInt(this.arg0)
          .putInt(this.arg1)
          .putInt(this.payload.length)
          .putInt(zeroChecksum ? 0 : checksum(this.payload))
          .putInt(~this.command);
        outputStream.write(bb.array());
        outputStream.write(this.payload);
        outputStream.flush();
    }

    static int checksum(byte[] bArr) {
        int sum = 0;
        for (byte b : bArr) {
            sum += b & 0xFF;
        }
        return sum;
    }

    static void readFully(InputStream inputStream, byte[] bArr, int off, int len) throws IOException {
        while (len > 0) {
            int stalls = 0;
            int n = inputStream.read(bArr, off, len);
            if (n < 0) {
                throw new EOFException("Truncated ADB packet");
            }
            if (n == 0) {
                stalls++;
                if (stalls > 16) {
                    throw new IOException("ADB input made no progress");
                }
            } else {
                off += n;
                len -= n;
            }
        }
    }
}

