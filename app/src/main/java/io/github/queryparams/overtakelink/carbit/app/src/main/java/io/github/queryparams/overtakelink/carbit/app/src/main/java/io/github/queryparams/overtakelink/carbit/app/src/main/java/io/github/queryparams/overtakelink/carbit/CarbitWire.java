package io.github.queryparams.overtakelink.carbit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

final class CarbitWire {
    static final int MAX_BODY = 32767;

    interface Consumer {
        void message(int cmd, int extra, byte[] body) throws IOException;
    }

    private CarbitWire() {}

    static ByteBuffer little(byte[] bArr) {
        return ByteBuffer.wrap(bArr).order(ByteOrder.LITTLE_ENDIAN);
    }

    static byte[] mirror(int cmd, byte[] body) {
        if (body == null) {
            body = new byte[0];
        }
        if (body.length > MAX_BODY) {
            throw new IllegalArgumentException("Mirror payload exceeds limit");
        }
        return ByteBuffer.allocate(body.length + 8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) cmd)
                .putShort((short) body.length)
                .putInt(0)
                .put(body)
                .array();
    }

    static byte[] pxc(int cmd, byte[] body) {
        return pxc(cmd, body, 0);
    }

    static byte[] pxc(int cmd, byte[] body, int extra) {
        if (body == null) {
            body = new byte[0];
        }
        int total = body.length + 16;
        return ByteBuffer.allocate(total)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(cmd)
                .putInt(total)
                .putInt(cmd ^ total)
                .putInt(extra)
                .put(body)
                .array();
    }

    static byte[] video(byte[] nalData) {
        return ByteBuffer.allocate(nalData.length + 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(nalData.length)
                .put(nalData)
                .array();
    }

    static final class Parser {
        private final Consumer consumer;
        private int count;
        private final byte[] pending;
        private final boolean pxc;

        Parser(boolean pxc, Consumer consumer) {
            this.pxc = pxc;
            this.consumer = consumer;
            this.pending = new byte[(pxc ? 65536 : MAX_BODY) + 16];
        }

        void feed(byte[] bArr) throws IOException {
            int i = 0;
            while (i < bArr.length) {
                int copy = Math.min(bArr.length - i, this.pending.length - this.count);
                if (copy == 0) {
                    throw new IOException("Carbit message exceeds limit");
                }
                System.arraycopy(bArr, i, this.pending, this.count, copy);
                this.count += copy;
                i += copy;
                drain();
            }
        }

        private void drain() throws IOException {
            int hdrSize = this.pxc ? 16 : 8;
            while (this.count >= hdrSize) {
                ByteBuffer bb = CarbitWire.little(this.pending);
                int cmd;
                int extra;
                int total;
                if (this.pxc) {
                    cmd = bb.getInt();
                    total = bb.getInt();
                    if (total < 16 || total > 65536 || bb.getInt() != (cmd ^ total)) {
                        throw new IOException("Invalid PXC header");
                    }
                    extra = bb.getInt();
                } else {
                    cmd = bb.getShort() & 0xFFFF;
                    short bodyLen = bb.getShort();
                    extra = bb.getInt();
                    if (bodyLen < 0) {
                        throw new IOException("Invalid mirror body length");
                    }
                    total = bodyLen + hdrSize;
                }
                if (this.count < total) {
                    return;
                }
                byte[] body = Arrays.copyOfRange(this.pending, hdrSize, total);
                System.arraycopy(this.pending, total, this.pending, 0, this.count - total);
                this.count -= total;
                this.consumer.message(cmd, extra, body);
            }
        }
    }
}

