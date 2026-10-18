package com.ecc.camera;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class CameraProtocol {
    static final int CHUNK_HEADER_SIZE = 16;
    static final String CAMERA_IP = "192.168.1.1";
    static final int COMMAND_PORT = 10005;
    static final int OPEN_VIDEO_PORT = 10006;

    private CameraProtocol() {}

    static byte[] message(int type, int id, byte[] payload, int declaredLength) {
        ByteBuffer buffer = ByteBuffer.allocate(12 + payload.length).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0xffeeffee);
        buffer.putShort((short) id);
        buffer.putShort((short) type);
        buffer.put((byte) 1);
        buffer.put((byte) 0);
        buffer.putShort((short) declaredLength);
        buffer.put(payload);
        return buffer.array();
    }

    static Float decodeAngle(byte[] packet, int length) {
        if (length < CHUNK_HEADER_SIZE) return null;
        int word = (packet[6] & 0xff)
                | ((packet[7] & 0xff) << 8)
                | ((packet[8] & 0xff) << 16)
                | ((packet[9] & 0xff) << 24);
        int x = signedMagnitude10(word & 0x3ff);
        int y = signedMagnitude10((word >>> 10) & 0x3ff);
        if (Math.hypot(x, y) < 16) return null;
        return (float) Math.toDegrees(Math.atan2(x, y));
    }

    private static int signedMagnitude10(int value) {
        int magnitude = value & 0x1ff;
        return (value & 0x200) != 0 ? -magnitude : magnitude;
    }

    static int littleEndianShort(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8);
    }

    static final class Frame {
        final byte[] jpeg;
        final Float angle;
        final int width;
        final int height;

        Frame(byte[] jpeg, Float angle, int width, int height) {
            this.jpeg = jpeg;
            this.angle = angle;
            this.width = width;
            this.height = height;
        }
    }

    static final class FrameAssembler {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 * 1024);
        private int previousSequence = -1;
        private int width;
        private int height;
        private Float angle;

        Frame accept(byte[] packet, int length) {
            if (length < CHUNK_HEADER_SIZE) return null;
            int sequence = ((packet[0] & 0xff) << 8) | (packet[1] & 0xff);
            width = littleEndianShort(packet, 12);
            height = littleEndianShort(packet, 14);
            int payloadLength = length - CHUNK_HEADER_SIZE;
            boolean startsFrame = payloadLength >= 2
                    && packet[CHUNK_HEADER_SIZE] == (byte) 0xff
                    && packet[CHUNK_HEADER_SIZE + 1] == (byte) 0xd8;
            if (startsFrame) {
                bytes.reset();
                angle = decodeAngle(packet, length);
            } else if (previousSequence < 0 || sequence != ((previousSequence + 1) & 0xffff)) {
                reset();
                return null;
            }
            previousSequence = sequence;
            int end = jpegEnd(packet, CHUNK_HEADER_SIZE, length);
            bytes.write(packet, CHUNK_HEADER_SIZE,
                    end >= 0 ? end + 2 - CHUNK_HEADER_SIZE : payloadLength);
            if (end < 0) return null;
            byte[] jpeg = bytes.toByteArray();
            Float frameAngle = angle;
            int frameWidth = width;
            int frameHeight = height;
            reset();
            return new Frame(jpeg, frameAngle, frameWidth, frameHeight);
        }

        private static int jpegEnd(byte[] packet, int start, int length) {
            for (int i = start; i + 1 < length; i++) {
                if ((packet[i] & 0xff) == 0xff && (packet[i + 1] & 0xff) == 0xd9) return i;
            }
            return -1;
        }

        private void reset() {
            bytes.reset();
            previousSequence = -1;
            angle = null;
        }
    }
}
