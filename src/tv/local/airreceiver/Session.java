package tv.local.airreceiver;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;

/** What is currently being received over AirPlay, shared between the server threads and MirrorActivity. */
final class Session {
    static volatile boolean mirroring;     // screen mirroring video is running
    static volatile boolean audioOnly;     // music/podcast stream without screen
    static volatile String client = "";    // sending device name
    static volatile String title, artist, album;
    static volatile Bitmap art;
    static volatile int videoWidth, videoHeight;

    interface Listener { void onSessionChanged(); }

    static Listener listener;
    static final Handler main = new Handler(Looper.getMainLooper());

    static boolean active() { return mirroring || audioOnly; }

    static void changed() {
        main.post(() -> { if (listener != null) listener.onSessionChanged(); });
    }

    static void clear() {
        mirroring = false;
        audioOnly = false;
        title = artist = album = null;
        art = null;
        changed();
    }

    private Session() {}
}
