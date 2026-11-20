package tv.local.airreceiver;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Hardware H.264 decoding of the mirrored screen straight onto a Surface, rendering each frame as soon as it is
 * decoded (lowest latency). Input is Annex-B; UxPlay puts SPS+PPS in front of every keyframe.
 * Frames that arrive before the Surface exists are kept from the last keyframe on, then replayed.
 */
final class VideoDecoder {
    private static final String TAG = "MirrorBox";
    private static final int MAX_PENDING = 600;

    interface SizeListener { void onVideoSize(int width, int height); }

    private MediaCodec codec;
    private Surface surface;
    private byte[] sps, pps;
    private int width = 1920, height = 1080;
    private boolean h265;
    private final ArrayList<byte[]> pending = new ArrayList<>();
    private Thread output;
    private volatile boolean outputRunning;
    volatile SizeListener sizeListener;
    private long dropped;

    synchronized void start(boolean h265) {
        if (codec != null && this.h265 != h265) releaseCodec();
        this.h265 = h265;
    }

    synchronized void setSurface(Surface s) {
        if (s == surface) return;
        if (s != null && !s.isValid()) return;   // destroyed before we got here
        releaseCodec();
        surface = s;
        if (s != null && !pending.isEmpty()) {
            ArrayList<byte[]> replay = new ArrayList<>(pending);
            for (byte[] frame : replay) feedLocked(frame, false);
        }
    }

    synchronized void feed(byte[] data) { feedLocked(data, true); }

    /** Stops decoding and forgets the stream (session over). */
    synchronized void release() {
        releaseCodec();
        pending.clear();
        sps = pps = null;
    }

    private void feedLocked(byte[] data, boolean remember) {
        boolean key = scanParameterSets(data);
        if (remember) {
            if (key) pending.clear();
            if (key || !pending.isEmpty()) {
                if (pending.size() >= MAX_PENDING) pending.clear();   // no keyframe for too long; wait for the next one
                else pending.add(data);
            }
        }
        if (surface == null || sps == null) return;
        if (codec == null && !configure()) return;
        try {
            int idx = -1;
            for (int tries = 0; tries < 25 && idx < 0; tries++) idx = codec.dequeueInputBuffer(20_000);
            if (idx < 0) {
                if (++dropped % 30 == 1) Log.w(TAG, "decoder busy, dropped " + dropped + " frames");
                return;
            }
            ByteBuffer in = codec.getInputBuffer(idx);
            in.clear();
            if (data.length > in.remaining()) {
                Log.w(TAG, "frame too large for decoder buffer: " + data.length);
                codec.queueInputBuffer(idx, 0, 0, System.nanoTime() / 1000, 0);
                return;
            }
            in.put(data);
            codec.queueInputBuffer(idx, 0, data.length, System.nanoTime() / 1000, 0);
        } catch (IllegalStateException e) {
            Log.w(TAG, "decoder error, restarting", e);
            releaseCodec();
        }
    }

    /** Finds SPS/PPS in an Annex-B buffer; reconfigures when they change. Returns true for a keyframe/parameter set. */
    private boolean scanParameterSets(byte[] d) {
        if (h265) return scanHevc(d);
        boolean key = false;
        byte[] newSps = null, newPps = null;
        int n = d.length;
        int i = nextStart(d, 0);
        while (i >= 0 && i < n) {
            int next = nextStart(d, i);
            int end = next < 0 ? n : startCodeBegin(d, next);
            int type = d[i] & 0x1f;
            if (type == 7) newSps = Arrays.copyOfRange(d, i, end);
            else if (type == 8) newPps = Arrays.copyOfRange(d, i, end);
            else if (type == 5) key = true;
            if (next < 0) break;
            i = next;
        }
        if (newSps != null) {
            key = true;
            if (sps == null || !Arrays.equals(sps, newSps) || (newPps != null && !Arrays.equals(pps, newPps))) {
                int[] size = SpsParser.size(newSps);
                if (size != null) { width = size[0]; height = size[1]; }
                if (codec != null) {
                    Log.i(TAG, "stream changed, reconfiguring decoder for " + width + "x" + height);
                    releaseCodec();
                }
                sps = newSps;
                pps = newPps != null ? newPps : pps;
            }
        }
        return key;
    }

    /** H.265: VPS 32, SPS 33, PPS 34, IRAP 16..21. Configuration goes in-band. */
    private boolean scanHevc(byte[] d) {
        boolean key = false;
        int i = nextStart(d, 0);
        while (i >= 0 && i < d.length) {
            int type = (d[i] >> 1) & 0x3f;
            if (type == 33) { key = true; if (sps == null) sps = new byte[0]; }
            else if (type >= 16 && type <= 21) key = true;
            i = nextStart(d, i);
        }
        return key;
    }

    /** Index of the first byte after the next 00 00 01 at or after from, or -1. */
    private static int nextStart(byte[] d, int from) {
        for (int i = from; i + 2 < d.length; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) return i + 3;
        }
        return -1;
    }

    /** Given the index after a start code, returns where that start code (3 or 4 bytes) begins. */
    private static int startCodeBegin(byte[] d, int afterStart) {
        int s = afterStart - 3;
        return (s > 0 && d[s - 1] == 0) ? s - 1 : s;
    }

    private boolean configure() {
        String mime = h265 ? "video/hevc" : "video/avc";
        try {
            MediaFormat f = MediaFormat.createVideoFormat(mime, width, height);
            if (!h265) {
                f.setByteBuffer("csd-0", ByteBuffer.wrap(withStartCode(sps)));
                if (pps != null) f.setByteBuffer("csd-1", ByteBuffer.wrap(withStartCode(pps)));
            }
            f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, Math.max(width * height, 1920 * 1080));
            f.setInteger(MediaFormat.KEY_PRIORITY, 0);   // realtime
            f.setInteger("low-latency", 1);              // honoured by some decoders before API 30
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(f, surface, null, 0);
            codec.start();
            Log.i(TAG, "decoder " + codec.getName() + " started for " + width + "x" + height);
        } catch (Exception e) {
            Log.e(TAG, "could not start the video decoder", e);
            if (codec != null) codec.release();
            codec = null;
            return false;
        }
        final MediaCodec c = codec;
        outputRunning = true;
        output = new Thread(() -> drain(c), "video-out");
        output.setPriority(Thread.MAX_PRIORITY);
        output.start();
        SizeListener l = sizeListener;
        if (l != null) l.onVideoSize(width, height);
        return true;
    }

    private void drain(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (outputRunning) {
            try {
                int idx = c.dequeueOutputBuffer(info, 20_000);
                if (idx >= 0) {
                    c.releaseOutputBuffer(idx, true);
                } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = c.getOutputFormat();
                    int w = f.getInteger(MediaFormat.KEY_WIDTH), h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                        w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1;
                        h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1;
                    }
                    Log.i(TAG, "decoder output " + w + "x" + h);
                    SizeListener l = sizeListener;
                    if (l != null) l.onVideoSize(w, h);
                }
            } catch (IllegalStateException e) {
                break;   // codec released
            }
        }
    }

    private void releaseCodec() {
        outputRunning = false;
        if (output != null) {
            try { output.join(500); } catch (InterruptedException ignored) { }
            output = null;
        }
        if (codec != null) {
            try { codec.stop(); } catch (Exception ignored) { }
            codec.release();
            codec = null;
        }
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }
}
