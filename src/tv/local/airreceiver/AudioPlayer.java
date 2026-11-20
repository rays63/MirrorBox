package tv.local.airreceiver;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import java.nio.ByteBuffer;

/**
 * Plays AirPlay audio as it arrives. ct 8 = AAC-ELD (screen mirroring), ct 4 = AAC-LC, both decoded with MediaCodec;
 * ALAC (ct 2) is decoded natively and arrives here as ct 0 (PCM s16 little-endian); ct 1 = PCM s16 big-endian.
 */
final class AudioPlayer {
    private static final String TAG = "MirrorBox";
    private static final int RATE = 44100;

    private AudioTrack track;
    private MediaCodec aac;
    private int aacType = -1;
    private float gain = 1f;
    private byte[] pcm = new byte[8192];
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    synchronized void configure(int ct) {
        if ((ct == 8 || ct == 4) && ct != aacType) startAac(ct);
    }

    synchronized void write(byte[] data, int ct) {
        if (!ensureTrack()) return;
        switch (ct) {
            case 0:
                track.write(data, 0, data.length);
                break;
            case 1:
                for (int i = 0; i + 1 < data.length; i += 2) { byte t = data[i]; data[i] = data[i + 1]; data[i + 1] = t; }
                track.write(data, 0, data.length);
                break;
            case 4:
            case 8:
                if (aacType != ct) startAac(ct);
                if (aac != null) decodeAac(data);
                break;
            default:
                break;
        }
    }

    synchronized void flush() {
        if (track != null) {
            track.pause();
            track.flush();
            track.play();
        }
        if (aac != null) {
            try { aac.flush(); } catch (IllegalStateException ignored) { }
        }
    }

    synchronized void release() {
        if (aac != null) {
            try { aac.stop(); } catch (Exception ignored) { }
            aac.release();
            aac = null;
            aacType = -1;
        }
        if (track != null) {
            try { track.stop(); } catch (Exception ignored) { }
            track.release();
            track = null;
        }
    }

    /** AirPlay volume: -30..0 dB, -144 = mute. */
    synchronized void setVolumeDb(float db) {
        gain = db <= -144f ? 0f : (float) Math.pow(10, Math.max(-30f, Math.min(0f, db)) / 20.0);
        if (track != null) track.setVolume(gain);
    }

    private boolean ensureTrack() {
        if (track != null) return true;
        try {
            int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min * 2, RATE * 4 / 5))   // ~200 ms caps added latency
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .build();
            track.setVolume(gain);
            track.play();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "could not open audio output", e);
            track = null;
            return false;
        }
    }

    private void startAac(int ct) {
        if (aac != null) {
            try { aac.stop(); } catch (Exception ignored) { }
            aac.release();
            aac = null;
        }
        try {
            MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2);
            byte[] asc = ct == 8 ? new byte[] {(byte) 0xF8, (byte) 0xE8, 0x50, 0x00} : new byte[] {0x12, 0x10};
            f.setByteBuffer("csd-0", ByteBuffer.wrap(asc));
            f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
            f.setInteger(MediaFormat.KEY_AAC_PROFILE, ct == 8 ? 39 : 2);
            aac = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            aac.configure(f, null, null, 0);
            aac.start();
            aacType = ct;
            Log.i(TAG, "audio decoder " + aac.getName() + " for " + (ct == 8 ? "AAC-ELD" : "AAC-LC"));
        } catch (Exception e) {
            Log.e(TAG, "could not start the AAC decoder", e);
            if (aac != null) aac.release();
            aac = null;
            aacType = -1;
        }
    }

    private void decodeAac(byte[] data) {
        try {
            int idx = aac.dequeueInputBuffer(10_000);
            if (idx >= 0) {
                ByteBuffer in = aac.getInputBuffer(idx);
                in.clear();
                in.put(data, 0, Math.min(data.length, in.remaining()));
                aac.queueInputBuffer(idx, 0, Math.min(data.length, in.capacity()), 0, 0);
            }
            for (int out = aac.dequeueOutputBuffer(info, 2_000); out != MediaCodec.INFO_TRY_AGAIN_LATER;
                 out = aac.dequeueOutputBuffer(info, 0)) {
                if (out < 0) continue;   // format/buffers changed
                ByteBuffer o = aac.getOutputBuffer(out);
                if (info.size > 0 && o != null) {
                    if (pcm.length < info.size) pcm = new byte[info.size];
                    o.position(info.offset);
                    o.get(pcm, 0, info.size);
                    track.write(pcm, 0, info.size);
                }
                aac.releaseOutputBuffer(out, false);
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "AAC decoder error, restarting", e);
            startAac(aacType);
        }
    }
}
