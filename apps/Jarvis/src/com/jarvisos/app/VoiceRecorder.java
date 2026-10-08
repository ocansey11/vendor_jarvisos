package com.jarvisos.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;

/**
 * Records one spoken request from the microphone as 16 kHz mono 16-bit PCM,
 * the format JarvisManager.queryWithAudio takes. Recording ends by itself
 * shortly after the speaker stops, so the request is on its way without a
 * second tap; {@link #stop} ends it sooner.
 */
class VoiceRecorder {

    interface Listener {
        /** Called on the main thread. {@code pcm} is null if the microphone could not be opened. */
        void onRecorded(byte[] pcm);
    }

    private static final int SAMPLE_RATE = 16_000;
    private static final int CHUNK_SAMPLES = SAMPLE_RATE / 20;   // 50 ms
    /** A chunk this loud (mean absolute sample) counts as speech. */
    private static final int SPEECH_LEVEL = 500;
    /** Quiet for this long after speech ends the recording. */
    private static final int QUIET_AFTER_SPEECH_MS = 700;
    /** Give up if nobody speaks. */
    private static final int WAIT_FOR_SPEECH_MS = 5_000;
    /** 15 s of audio is 480 KB, safely inside one Binder call. */
    private static final int LONGEST_MS = 15_000;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mStop;
    private volatile boolean mRecording;

    boolean isRecording() { return mRecording; }

    void stop() { mStop = true; }

    void start(Listener listener) {
        if (mRecording) return;
        mRecording = true;
        mStop = false;
        new Thread(() -> {
            final byte[] pcm = record();
            mMain.post(() -> {
                mRecording = false;
                listener.onRecorded(pcm);
            });
        }, "VoiceRecorder").start();
    }

    private byte[] record() {
        final int minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        AudioRecord recorder;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minimum, CHUNK_SAMPLES * 8));
        } catch (SecurityException | IllegalArgumentException e) {
            return null;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            return null;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        short[] chunk = new short[CHUNK_SAMPLES];
        boolean spoke = false;
        int quietMs = 0;
        int totalMs = 0;
        try {
            recorder.startRecording();
            while (!mStop && totalMs < LONGEST_MS) {
                int read = recorder.read(chunk, 0, chunk.length);
                if (read <= 0) break;
                long sum = 0;
                for (int i = 0; i < read; i++) {
                    sum += Math.abs(chunk[i]);
                    out.write(chunk[i] & 0xff);
                    out.write((chunk[i] >> 8) & 0xff);
                }
                final int ms = read * 1000 / SAMPLE_RATE;
                totalMs += ms;
                if (sum / read >= SPEECH_LEVEL) {
                    spoke = true;
                    quietMs = 0;
                } else {
                    quietMs += ms;
                }
                if (spoke ? quietMs >= QUIET_AFTER_SPEECH_MS : totalMs >= WAIT_FOR_SPEECH_MS) break;
            }
        } finally {
            recorder.stop();
            recorder.release();
        }
        return out.toByteArray();
    }
}
