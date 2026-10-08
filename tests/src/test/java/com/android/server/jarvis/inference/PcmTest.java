package com.android.server.jarvis.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class PcmTest {

    /** A 16-bit PCM WAV file holding {@code samples}, with an extra chunk before the audio. */
    private static byte[] wav(int rate, int channels, short... samples) {
        ByteBuffer b = ByteBuffer.allocate(12 + 24 + 12 + 8 + samples.length * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes()).putInt(b.capacity() - 8).put("WAVE".getBytes());
        b.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2))
                .putShort((short) 16);
        b.put("LIST".getBytes()).putInt(4).put("abcd".getBytes());
        b.put("data".getBytes()).putInt(samples.length * 2);
        for (short s : samples) b.putShort(s);
        return b.array();
    }

    @Test
    void rawSamplesAreScaledToPlusMinusOne() {
        float[] out = Pcm.fromPcm16(new byte[] {0, 0, (byte) 0xff, 0x7f, 0, (byte) 0x80});
        assertEquals(3, out.length);
        assertEquals(0f, out[0], 0);
        assertEquals(32767 / 32768f, out[1], 1e-6);
        assertEquals(-1f, out[2], 0);
    }

    @Test
    void wavAtSixteenKilohertzIsReadAsIs() {
        float[] out = Pcm.fromWav(wav(16_000, 1, (short) 16384, (short) -16384));
        assertEquals(2, out.length);
        assertEquals(0.5f, out[0], 1e-6);
        assertEquals(-0.5f, out[1], 1e-6);
    }

    @Test
    void stereoIsAveragedToMono() {
        float[] out = Pcm.fromWav(wav(16_000, 2, (short) 16384, (short) 0, (short) -8192, (short) -8192));
        assertEquals(2, out.length);
        assertEquals(0.25f, out[0], 1e-6);
        assertEquals(-0.25f, out[1], 1e-6);
    }

    @Test
    void otherRatesAreResampled() {
        short[] second = new short[22_050];
        java.util.Arrays.fill(second, (short) 8192);
        float[] out = Pcm.fromWav(wav(22_050, 1, second));
        assertEquals(16_000, out.length);
        assertEquals(0.25f, out[8_000], 1e-6);
    }

    @Test
    void anythingElseIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Pcm.fromWav(new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class, () -> Pcm.fromWav("RIFFxxxxWAVE".getBytes()));
    }
}
