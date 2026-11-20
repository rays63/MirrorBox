package tv.local.airreceiver;

import android.content.Context;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.nio.charset.StandardCharsets;

/** Java side of the UxPlay core (libairreceiver.so). Callbacks arrive on UxPlay's network threads. */
final class AirPlayServer {
    private static final String TAG = "MirrorBox";

    static { System.loadLibrary("airreceiver"); }

    private native int nativeStart(String name, String deviceId, String keyFile, int width, int height, int fps, boolean debug);
    private native void nativeStop();
    private native void nativeDisconnect();

    // event codes, kept in step with airreceiver_jni.c
    private static final int EV_CONN_INIT = 1, EV_CONN_DESTROY = 2, EV_CONN_RESET = 3, EV_VIDEO_RESET = 4,
            EV_AUDIO_FLUSH = 5, EV_VIDEO_FLUSH = 6, EV_VIDEO_PAUSE = 7, EV_VIDEO_RESUME = 8, EV_CLIENT = 9,
            EV_MIRROR_RUNNING = 10, EV_MIRROR_STOPPED = 11, EV_COVERART_STOP = 12;

    final VideoDecoder video = new VideoDecoder();
    final AudioPlayer audio = new AudioPlayer();
    private final Context ctx;
    private int connections;
    private boolean running;
    int port;
    volatile long lastAudioAt;

    AirPlayServer(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    synchronized boolean start(String name, String deviceId) {
        if (running) return true;
        File key = new File(ctx.getFilesDir(), "airplay.pem");
        port = nativeStart(name, deviceId, key.getAbsolutePath(), 1920, 1080, 30, false);
        running = port > 0;
        Log.i(TAG, running ? "AirPlay running on port " + port : "AirPlay start failed: " + port);
        return running;
    }

    synchronized void stop() {
        if (!running) return;
        nativeStop();
        running = false;
        endSession();
    }

    boolean isRunning() { return running; }

    /** Drops the connected sender (Back pressed on the TV). */
    void disconnect() {
        if (running) nativeDisconnect();
        endSession();
    }

    private void endSession() {
        video.release();
        audio.release();
        Session.clear();
    }

    private void showScreen() {
        Intent i = new Intent(ctx, MirrorActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the mirror screen", e);
        }
    }

    // ---- called from native ----

    @SuppressWarnings("unused")
    void onNativeEvent(int code, String text) {
        switch (code) {
            case EV_CONN_INIT:
                synchronized (this) { connections++; }
                break;
            case EV_CONN_DESTROY:
                boolean last;
                synchronized (this) { connections = Math.max(0, connections - 1); last = connections == 0; }
                if (last) endSession();
                break;
            case EV_CONN_RESET:
                synchronized (this) { connections = 0; }
                endSession();
                break;
            case EV_VIDEO_RESET:
            case EV_MIRROR_STOPPED:
                video.release();
                Session.mirroring = false;
                Session.changed();
                break;
            case EV_AUDIO_FLUSH:
                audio.flush();
                break;
            case EV_CLIENT:
                if (text != null) {
                    int bar = text.indexOf('|');
                    Session.client = bar >= 0 ? text.substring(0, bar) : text;
                }
                break;
            case EV_COVERART_STOP:
                Session.art = null;
                Session.changed();
                break;
            default:
                break;
        }
    }

    @SuppressWarnings("unused")
    int onNativeVideoFormat(boolean h265, int widthSource, int heightSource, int width, int height) {
        Log.i(TAG, "mirroring " + (h265 ? "H.265 " : "H.264 ") + widthSource + "x" + heightSource);
        boolean wasActive = Session.active();
        Session.mirroring = true;
        Session.audioOnly = false;
        Session.changed();
        video.start(h265);
        if (!wasActive || MirrorActivity.instance == null) showScreen();
        return 0;
    }

    @SuppressWarnings("unused")
    void onNativeVideo(byte[] data, long ntp) {
        video.feed(data);
    }

    @SuppressWarnings("unused")
    void onNativeAudioFormat(int ct, int spf, boolean usingScreen) {
        audio.configure(ct);
        if (!usingScreen && !Session.mirroring) {
            boolean wasActive = Session.active();
            Session.audioOnly = true;
            Session.changed();
            if (!wasActive || MirrorActivity.instance == null) showScreen();
        }
    }

    @SuppressWarnings("unused")
    void onNativeAudio(byte[] data, int ct, long ntp) {
        lastAudioAt = SystemClock.uptimeMillis();
        audio.write(data, ct);
    }

    @SuppressWarnings("unused")
    void onNativeVolume(float db) {
        audio.setVolumeDb(db);
    }

    @SuppressWarnings("unused")
    void onNativeMetadata(byte[] dmap) {
        parseDmap(dmap, 0, dmap.length);
        Session.changed();
    }

    @SuppressWarnings("unused")
    void onNativeCoverArt(byte[] jpeg) {
        Session.art = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        Session.changed();
    }

    /** DMAP: 4-byte tag, 4-byte big-endian length, payload; "mlit" is a container. */
    private static void parseDmap(byte[] b, int off, int end) {
        while (off + 8 <= end) {
            String tag = new String(b, off, 4, StandardCharsets.US_ASCII);
            int len = ((b[off + 4] & 0xff) << 24) | ((b[off + 5] & 0xff) << 16) | ((b[off + 6] & 0xff) << 8) | (b[off + 7] & 0xff);
            int start = off + 8;
            if (len < 0 || start + len > end) return;
            switch (tag) {
                case "mlit": parseDmap(b, start, start + len); break;
                case "minm": Session.title = new String(b, start, len, StandardCharsets.UTF_8); break;
                case "asar": Session.artist = new String(b, start, len, StandardCharsets.UTF_8); break;
                case "asal": Session.album = new String(b, start, len, StandardCharsets.UTF_8); break;
                default: break;
            }
            off = start + len;
        }
    }
}
