package tv.local.airreceiver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Locale;
import java.util.Random;
import java.util.UUID;

/** Keeps the AirPlay and DLNA receivers running and discoverable (foreground service, started at boot). */
public class ReceiverService extends Service {
    private static final String TAG = "MirrorBox";
    private static final String CHANNEL = "receiver";
    /** Name phones show until it is changed with "Rename this TV" in the app. */
    static final String DEFAULT_NAME = "NetTV";

    private static volatile ReceiverService instance;
    private AirPlayServer airplay;
    private DlnaRenderer dlna;
    private WifiManager.MulticastLock multicast;
    private WifiManager.WifiLock wifi;
    private HandlerThread worker;
    private Handler work;
    private ConnectivityManager.NetworkCallback netCallback;
    private String lastAddress;

    static void start(Context c) {
        c.startForegroundService(new Intent(c, ReceiverService.class));
    }

    static AirPlayServer airplay() {
        ReceiverService s = instance;
        return s == null ? null : s.airplay;
    }

    static DlnaRenderer dlna() {
        ReceiverService s = instance;
        return s == null ? null : s.dlna;
    }

    static boolean running() { return instance != null; }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Receiver", NotificationManager.IMPORTANCE_MIN));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.icon)
                .setContentTitle("MirrorBox")
                .setContentText("Ready for AirPlay and DLNA as \"" + name(this) + "\"")
                .setContentIntent(open)
                .setOngoing(true)
                .build();
        startForeground(1, n);

        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        multicast = wm.createMulticastLock("airreceiver-mdns");
        multicast.setReferenceCounted(false);
        multicast.acquire();
        wifi = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "airreceiver");
        wifi.setReferenceCounted(false);
        wifi.acquire();

        worker = new HandlerThread("receiver-control");
        worker.start();
        work = new Handler(worker.getLooper());
        airplay = new AirPlayServer(this);
        dlna = new DlnaRenderer(this);
        work.post(this::startAll);

        // Re-announce on a new network/address (DHCP renewals, Wi-Fi reconnects).
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        netCallback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { work.postDelayed(ReceiverService.this::checkAddress, 3000); }
            @Override public void onLost(Network network) { work.postDelayed(ReceiverService.this::checkAddress, 3000); }
        };
        cm.registerDefaultNetworkCallback(netCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "restart".equals(intent.getAction())) work.post(() -> { stopAll(); startAll(); });
        return START_STICKY;
    }

    private void startAll() {
        lastAddress = localAddress();
        String name = name(this);
        if (!airplay.start(name, deviceId(this))) Log.e(TAG, "AirPlay did not start");
        dlna.start(name, dlnaUuid(this));
    }

    private void stopAll() {
        airplay.stop();
        dlna.stop();
    }

    private void checkAddress() {
        String now = localAddress();
        if (now != null && !now.equals(lastAddress)) {
            Log.i(TAG, "network address changed " + lastAddress + " -> " + now + ", restarting receivers");
            stopAll();
            startAll();
        }
    }

    @Override
    public void onDestroy() {
        instance = null;
        getSystemService(ConnectivityManager.class).unregisterNetworkCallback(netCallback);
        work.post(() -> {
            stopAll();
            worker.quitSafely();
        });
        if (multicast.isHeld()) multicast.release();
        if (wifi.isHeld()) wifi.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- settings ----

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("receiver", MODE_PRIVATE);
    }

    static String name(Context c) {
        String n = prefs(c).getString("name", null);
        return n != null && !n.trim().isEmpty() ? n.trim() : DEFAULT_NAME;
    }

    /** Stable, random, locally administered MAC used as the AirPlay device id (Android hides the real one). */
    static String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("device_id", null);
        if (id == null) {
            byte[] b = new byte[6];
            new Random().nextBytes(b);
            b[0] = (byte) ((b[0] & 0xfc) | 0x02);
            id = String.format(Locale.US, "%02x:%02x:%02x:%02x:%02x:%02x", b[0], b[1], b[2], b[3], b[4], b[5]);
            p.edit().putString("device_id", id).apply();
        }
        return id;
    }

    static String dlnaUuid(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("dlna_uuid", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("dlna_uuid", id).apply();
        }
        return id;
    }

    /** First site-local IPv4 address (Wi-Fi or Ethernet). */
    static String localAddress() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && a.isSiteLocalAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) { }
        return null;
    }
}
