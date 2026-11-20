package tv.local.airreceiver;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Plays what a DLNA controller sent: video/audio with VideoView (MediaPlayer), images with ImageView. */
public class PlayerActivity extends Activity {
    private static final String TAG = "MirrorBox";
    static final String EXTRA_URI = "uri", EXTRA_TITLE = "title", EXTRA_KIND = "kind";

    static volatile PlayerActivity instance;
    static volatile long positionMs, durationMs;

    private VideoView video;
    private ImageView image;
    private TextView title, osd;
    private String uri = "", kind = "video";
    private boolean prepared, wantPlay;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (prepared && video.getVisibility() == View.VISIBLE) {
                positionMs = video.getCurrentPosition();
                durationMs = Math.max(0, video.getDuration());
            }
            video.postDelayed(this, 500);
        }
    };
    private final Runnable hideOsd = () -> osd.setVisibility(View.GONE);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        root.addView(image, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        title = new TextView(this);
        title.setTextColor(Color.WHITE);
        title.setTextSize(36);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER);
        root.addView(title, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        osd = new TextView(this);
        osd.setTextColor(Color.WHITE);
        osd.setTextSize(22);
        osd.setBackgroundColor(0xAA000000);
        osd.setPadding(32, 16, 32, 16);
        osd.setVisibility(View.GONE);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = 64;
        root.addView(osd, lp);
        setContentView(root);

        video.setOnPreparedListener(mp -> {
            prepared = true;
            durationMs = Math.max(0, mp.getDuration());
            if (wantPlay) {
                video.start();
                report("PLAYING");
            } else {
                report("PAUSED_PLAYBACK");
            }
        });
        video.setOnCompletionListener(mp -> {
            report("STOPPED");
            finish();
        });
        video.setOnErrorListener((mp, what, extra) -> {
            Log.w(TAG, "playback error " + what + "/" + extra + " for " + uri);
            showOsd("Cannot play this media");
            report("STOPPED");
            video.postDelayed(this::finish, 2500);
            return true;
        });
        load(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        load(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();
        instance = this;
        video.post(tick);
    }

    @Override
    protected void onStop() {
        super.onStop();
        video.removeCallbacks(tick);
        if (instance == this) instance = null;
        video.stopPlayback();
        positionMs = 0;
        report("STOPPED");
        finish();
    }

    String currentUri() { return uri; }

    private void load(Intent i) {
        String newUri = i.getStringExtra(EXTRA_URI);
        if (newUri == null) { finish(); return; }
        if (newUri.equals(uri)) {   // SetAVTransportURI + Play both open the player; don't load twice
            resumePlayback();
            return;
        }
        uri = newUri;
        kind = i.getStringExtra(EXTRA_KIND) == null ? "video" : i.getStringExtra(EXTRA_KIND);
        String t = i.getStringExtra(EXTRA_TITLE);
        prepared = false;
        wantPlay = true;
        positionMs = 0;
        durationMs = 0;
        if (kind.equals("image")) {
            video.stopPlayback();
            video.setVisibility(View.GONE);
            title.setVisibility(View.GONE);
            image.setVisibility(View.VISIBLE);
            image.setImageDrawable(null);
            final String u = uri;
            new Thread(() -> {
                Bitmap bm = fetchImage(u);
                runOnUiThread(() -> {
                    if (!u.equals(uri)) return;
                    if (bm != null) { image.setImageBitmap(bm); report("PLAYING"); }
                    else { showOsd("Cannot show this picture"); report("STOPPED"); }
                });
            }, "dlna-image").start();
        } else {
            image.setVisibility(View.GONE);
            video.setVisibility(View.VISIBLE);
            boolean audio = kind.equals("audio");
            title.setVisibility(audio ? View.VISIBLE : View.GONE);
            title.setText(t == null || t.isEmpty() ? "Playing audio" : t);
            report("TRANSITIONING");
            video.setVideoURI(Uri.parse(uri));
        }
        if (t != null && !t.isEmpty() && !kind.equals("audio")) showOsd(t);
    }

    // ---- DLNA control (main thread) ----

    void resumePlayback() {
        wantPlay = true;
        if (kind.equals("image")) { report("PLAYING"); return; }
        if (prepared) { video.start(); report("PLAYING"); }
    }

    void pausePlayback() {
        wantPlay = false;
        if (prepared && video.canPause()) { video.pause(); report("PAUSED_PLAYBACK"); }
    }

    void stopPlayback() {
        video.stopPlayback();
        report("STOPPED");
        finish();
    }

    void seekTo(int ms) {
        if (prepared) {
            video.seekTo(ms);
            positionMs = ms;
            showOsd(DlnaRenderer.formatTime(ms));
        }
    }

    private void report(String state) {
        DlnaRenderer r = ReceiverService.dlna();
        if (r != null) r.onPlayerState(state);
    }

    private void showOsd(String text) {
        osd.setText(text);
        osd.setVisibility(View.VISIBLE);
        osd.removeCallbacks(hideOsd);
        osd.postDelayed(hideOsd, 3000);
    }

    // ---- remote keys ----

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (video.isPlaying()) { pausePlayback(); showOsd("Paused"); } else { resumePlayback(); showOsd("Playing"); }
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                resumePlayback();
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                pausePlayback();
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                seekTo((int) Math.min(Math.max(0, durationMs - 1000), video.getCurrentPosition() + 10_000));
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                seekTo(Math.max(0, video.getCurrentPosition() - 10_000));
                return true;
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                stopPlayback();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    private Bitmap fetchImage(String u) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(u).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                byte[] chunk = new byte[65536];
                for (int n; (n = in.read(chunk)) > 0; ) buf.write(chunk, 0, n);
                byte[] data = buf.toByteArray();   // readAllBytes() needs API 33
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (o.outWidth / sample > 3840 || o.outHeight / sample > 2160) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                return BitmapFactory.decodeByteArray(data, 0, data.length, o);
            }
        } catch (Exception e) {
            Log.w(TAG, "image load failed " + u, e);
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
