package tv.local.airreceiver;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Full-screen output for an AirPlay session: mirrored screen, or a now-playing card for audio-only streams. */
public class MirrorActivity extends Activity implements SurfaceHolder.Callback, Session.Listener, VideoDecoder.SizeListener {
    static MirrorActivity instance;

    private FrameLayout root;
    private SurfaceView surface;
    private LinearLayout nowPlaying;
    private ImageView art;
    private TextView title, subtitle, client;
    private int videoW = 16, videoH = 9;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(1, 1, Gravity.CENTER));

        nowPlaying = new LinearLayout(this);
        nowPlaying.setOrientation(LinearLayout.HORIZONTAL);
        nowPlaying.setGravity(Gravity.CENTER_VERTICAL);
        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setImageResource(R.drawable.icon);
        nowPlaying.addView(art, new LinearLayout.LayoutParams(dp(360), dp(360)));
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(56), 0, 0, 0);
        title = label(40, true);
        subtitle = label(26, false);
        client = label(20, false);
        client.setAlpha(0.6f);
        text.addView(title);
        text.addView(subtitle);
        text.addView(client);
        nowPlaying.addView(text, new LinearLayout.LayoutParams(dp(640), LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(nowPlaying, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol || b - t != ob - ot) fitVideo(); });
        setContentView(root);
    }

    @Override
    protected void onStart() {
        super.onStart();
        instance = this;
        Session.listener = this;
        AirPlayServer s = ReceiverService.airplay();
        if (s != null) s.video.sizeListener = this;
        onSessionChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (instance == this) instance = null;
        if (Session.listener == this) Session.listener = null;
        AirPlayServer s = ReceiverService.airplay();
        if (s != null && s.video.sizeListener == this) s.video.sizeListener = null;
        // The mirrored picture cannot continue without this screen; audio-only keeps playing in the background.
        if (Session.mirroring && !isChangingConfigurations() && s != null) s.disconnect();
        finish();
    }

    @Override
    public void onSessionChanged() {
        if (!Session.active()) {
            finish();
            return;
        }
        boolean video = Session.mirroring;
        surface.setVisibility(video ? View.VISIBLE : View.GONE);
        nowPlaying.setVisibility(video ? View.GONE : View.VISIBLE);
        if (!video) {
            title.setText(Session.title != null ? Session.title : "AirPlay");
            String sub = join(Session.artist, Session.album);
            subtitle.setText(sub);
            client.setText(Session.client == null || Session.client.isEmpty() ? "" : "from " + Session.client);
            if (Session.art != null) art.setImageBitmap(Session.art); else art.setImageResource(R.drawable.icon);
        }
    }

    @Override
    public void onVideoSize(int w, int h) {
        root.post(() -> {
            videoW = w;
            videoH = h;
            fitVideo();
        });
    }

    private void fitVideo() {
        int rw = root.getWidth(), rh = root.getHeight();
        if (rw == 0 || rh == 0 || videoW == 0 || videoH == 0) return;
        int w = rw, h = (int) ((long) rw * videoH / videoW);
        if (h > rh) { h = rh; w = (int) ((long) rh * videoW / videoH); }
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        if (lp.width != w || lp.height != h) {
            lp.width = w;
            lp.height = h;
            surface.setLayoutParams(lp);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            AirPlayServer s = ReceiverService.airplay();
            if (s != null) s.disconnect();
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        AirPlayServer s = ReceiverService.airplay();
        if (s != null) new Thread(() -> s.video.setSurface(holder.getSurface()), "surface-on").start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        AirPlayServer s = ReceiverService.airplay();
        if (s != null) s.video.setSurface(null);   // must finish before the surface goes away
    }

    private TextView label(int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        t.setMaxLines(2);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        if (b == null || b.isEmpty()) return a;
        return a + " — " + b;
    }
}
