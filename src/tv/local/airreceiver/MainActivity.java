package tv.local.airreceiver;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Status screen: how to find this TV from a phone, and renaming it. */
public class MainActivity extends Activity {
    private TextView status;
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            updateStatus();
            status.postDelayed(this, 2000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ReceiverService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0F1F33, 0xFF1E4A7A});
        root.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.icon);
        root.addView(icon, new LinearLayout.LayoutParams(dp(120), dp(120)));

        TextView title = text(44, true);
        title.setText("MirrorBox");
        title.setPadding(0, dp(16), 0, dp(8));
        root.addView(title);

        status = text(22, false);
        status.setGravity(Gravity.CENTER);
        status.setLineSpacing(0, 1.3f);
        root.addView(status);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setPadding(0, dp(32), 0, 0);
        Button rename = button("Rename this TV", v -> rename());
        Button restart = button("Restart receiver", v -> {
            startService(new Intent(this, ReceiverService.class).setAction("restart"));
            status.setText("Restarting…");
        });
        buttons.addView(rename);
        buttons.addView(restart);
        root.addView(buttons);
        setContentView(root);
        rename.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        status.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        status.removeCallbacks(refresh);
    }

    private void updateStatus() {
        String name = ReceiverService.name(this);
        String ip = ReceiverService.localAddress();
        AirPlayServer ap = ReceiverService.airplay();
        DlnaRenderer dl = ReceiverService.dlna();
        boolean apOk = ap != null && ap.isRunning();
        boolean dlOk = dl != null && dl.isRunning();
        status.setText("Name: " + name + "\n"
                + "Address: " + (ip != null ? ip : "no network") + "\n"
                + "AirPlay: " + (apOk ? "ready (port " + ap.port + ")" : "not running") + "     "
                + "DLNA: " + (dlOk ? "ready" : "not running") + "\n\n"
                + "iPhone / iPad: Control Centre → Screen Mirroring → " + name + "\n"
                + "Mac: menu bar → Control Centre → Screen Mirroring → " + name + "\n"
                + "Music: AirPlay button in any app → " + name + "\n"
                + "DLNA apps (BubbleUPnP, VLC, …): pick " + name + " as the renderer");
    }

    private void rename() {
        EditText input = new EditText(this);
        input.setSingleLine();
        input.setText(ReceiverService.name(this));
        input.selectAll();
        new AlertDialog.Builder(this)
                .setTitle("Name shown on phones")
                .setView(input)
                .setPositiveButton("Save", (d, w) -> {
                    ReceiverService.prefs(this).edit().putString("name", input.getText().toString().trim()).apply();
                    startService(new Intent(this, ReceiverService.class).setAction("restart"));
                    status.setText("Restarting…");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private TextView text(int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setTextColor(Color.WHITE);
        t.setTextSize(sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(12), 0, dp(12), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
