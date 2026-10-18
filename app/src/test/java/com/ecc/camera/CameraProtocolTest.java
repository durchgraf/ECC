package com.ecc.camera;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class CameraProtocolTest {
    @Test public void openVideoMessageUsesExpectedWireHeaderAndPortPayload() {
        byte[] message = CameraProtocol.message(4, 1, new byte[] {0x34, 0x12}, 0);
        assertArrayEquals(new byte[] {
                (byte) 0xee, (byte) 0xff, (byte) 0xee, (byte) 0xff,
                1, 0, 4, 0, 1, 0, 0, 0, 0x34, 0x12
        }, message);
    }

    @Test public void decodesCapturedGravityAngle() {
        byte[] packet = hex("01a6c10013015e7e292062fc8002e001");
        assertEquals(-135.3f, CameraProtocol.decodeAngle(packet, packet.length), 0.2f);
        assertNull(CameraProtocol.decodeAngle(new byte[8], 8));
    }

    @Test public void reassemblesJpegAndDropsIncompleteSequence() {
        byte[] packet = hex("01a7990014010000a22162fc8002e001");
        byte[] first = withPayload(packet, new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2});
        byte[] last = withPayload(hex("01a8990014010000a22162fc8002e001"),
                new byte[] {3, (byte) 0xff, (byte) 0xd9});
        CameraProtocol.FrameAssembler assembler = new CameraProtocol.FrameAssembler();
        assertNull(assembler.accept(first, first.length));
        CameraProtocol.Frame frame = assembler.accept(last, last.length);
        assertNotNull(frame);
        assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0xd8, 1, 2, 3, (byte) 0xff, (byte) 0xd9}, frame.jpeg);
        assertEquals(640, frame.width);
        assertEquals(480, frame.height);
        assertEquals(0f, frame.angle, 0.1f);

        CameraProtocol.FrameAssembler broken = new CameraProtocol.FrameAssembler();
        broken.accept(first, first.length);
        byte[] skipped = withPayload(hex("01aa990014010000a22162fc8002e001"), new byte[] {3});
        assertNull(broken.accept(skipped, skipped.length));
    }

    private static byte[] withPayload(byte[] header, byte[] payload) {
        byte[] packet = new byte[header.length + payload.length];
        System.arraycopy(header, 0, packet, 0, header.length);
        System.arraycopy(payload, 0, packet, header.length, payload.length);
        return packet;
    }

    private static byte[] hex(String value) {
        byte[] bytes = new byte[value.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return bytes;
    }
}
