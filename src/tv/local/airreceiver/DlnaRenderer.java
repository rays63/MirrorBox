package tv.local.airreceiver;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP/DLNA MediaRenderer: SSDP discovery, device + service descriptions, AVTransport / RenderingControl /
 * ConnectionManager SOAP control and GENA events. Media is played by PlayerActivity.
 */
final class DlnaRenderer {
    private static final String TAG = "MirrorBox";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final int HTTP_PORT = 49494;
    private static final String DEVICE_TYPE = "urn:schemas-upnp-org:device:MediaRenderer:1";
    static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    static final String RC = "urn:schemas-upnp-org:service:RenderingControl:1";
    static final String CM = "urn:schemas-upnp-org:service:ConnectionManager:1";
    private static final String SERVER = "Android/" + Build.VERSION.RELEASE + " UPnP/1.0 MirrorBox/1.0";
    private static final String SINK_PROTOCOLS = buildSinkProtocols();

    private final Context ctx;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean running;
    private String name, uuid;
    private ServerSocket http;
    private MulticastSocket ssdp;
    private ExecutorService pool;
    private Thread httpThread, ssdpThread, notifyThread;
    private int port;

    // transport state (InstanceID 0 only)
    private volatile String uri = "", metadata = "", transportState = "NO_MEDIA_PRESENT";
    private volatile String nextUri = "", nextMetadata = "";
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    private static final class Subscription {
        final String service; final List<String> callbacks; long expires; int seq;
        Subscription(String service, List<String> callbacks) { this.service = service; this.callbacks = callbacks; }
    }

    DlnaRenderer(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    boolean isRunning() { return running; }

    synchronized void start(String name, String uuid) {
        if (running) return;
        this.name = name;
        this.uuid = uuid;
        try {
            http = new ServerSocket();
            http.setReuseAddress(true);
            try {
                http.bind(new InetSocketAddress(HTTP_PORT));
            } catch (IOException e) {
                http.bind(new InetSocketAddress(0));
            }
            port = http.getLocalPort();
            ssdp = new MulticastSocket(null);
            ssdp.setReuseAddress(true);
            ssdp.bind(new InetSocketAddress(SSDP_PORT));
            ssdp.joinGroup(InetAddress.getByName(SSDP_ADDR));
            ssdp.setTimeToLive(4);
        } catch (IOException e) {
            Log.e(TAG, "DLNA start failed", e);
            closeQuietly();
            return;
        }
        running = true;
        pool = Executors.newFixedThreadPool(6);
        httpThread = new Thread(this::acceptLoop, "dlna-http");
        httpThread.start();
        ssdpThread = new Thread(this::ssdpLoop, "dlna-ssdp");
        ssdpThread.start();
        notifyThread = new Thread(this::notifyLoop, "dlna-notify");
        notifyThread.start();
        Log.i(TAG, "DLNA renderer '" + name + "' on port " + port);
    }

    synchronized void stop() {
        if (!running) return;
        running = false;
        try { sendNotify("ssdp:byebye"); } catch (Exception ignored) { }
        closeQuietly();
        if (notifyThread != null) notifyThread.interrupt();
        if (pool != null) pool.shutdownNow();
        subscriptions.clear();
    }

    private void closeQuietly() {
        try { if (http != null) http.close(); } catch (IOException ignored) { }
        if (ssdp != null) ssdp.close();
    }

    // ---------------- SSDP ----------------

    private String[] notificationTypes() {
        return new String[] {"upnp:rootdevice", "uuid:" + uuid, DEVICE_TYPE, AVT, RC, CM};
    }

    private String usn(String nt) {
        return nt.equals("uuid:" + uuid) ? nt : "uuid:" + uuid + "::" + nt;
    }

    private String location() {
        String ip = ReceiverService.localAddress();
        return "http://" + (ip != null ? ip : "127.0.0.1") + ":" + port + "/description.xml";
    }

    private void notifyLoop() {
        try {
            for (int i = 0; running; i++) {
                sendNotify("ssdp:alive");
                Thread.sleep(i < 2 ? 1500 : 60_000);
                pruneSubscriptions();
            }
        } catch (InterruptedException ignored) {
        } catch (Exception e) {
            if (running) Log.w(TAG, "SSDP notify failed", e);
        }
    }

    private void sendNotify(String nts) throws IOException {
        InetAddress group = InetAddress.getByName(SSDP_ADDR);
        for (String nt : notificationTypes()) {
            StringBuilder m = new StringBuilder("NOTIFY * HTTP/1.1\r\n")
                    .append("HOST: 239.255.255.250:1900\r\n")
                    .append("NT: ").append(nt).append("\r\n")
                    .append("NTS: ").append(nts).append("\r\n")
                    .append("USN: ").append(usn(nt)).append("\r\n");
            if (nts.equals("ssdp:alive")) {
                m.append("CACHE-CONTROL: max-age=1800\r\n")
                 .append("LOCATION: ").append(location()).append("\r\n")
                 .append("SERVER: ").append(SERVER).append("\r\n");
            }
            m.append("\r\n");
            byte[] b = m.toString().getBytes(StandardCharsets.US_ASCII);
            ssdp.send(new DatagramPacket(b, b.length, group, SSDP_PORT));
        }
    }

    private void ssdpLoop() {
        byte[] buf = new byte[2048];
        Random random = new Random();
        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                ssdp.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII);
                if (!msg.startsWith("M-SEARCH")) continue;
                Map<String, String> h = headers(msg);
                String st = h.get("st");
                if (st == null) continue;
                List<String> matches = new ArrayList<>();
                if (st.equals("ssdp:all")) {
                    for (String nt : notificationTypes()) matches.add(nt);
                } else {
                    for (String nt : notificationTypes()) if (nt.equalsIgnoreCase(st)) matches.add(nt);
                    if (st.startsWith("urn:schemas-upnp-org:service:AVTransport:") ) matches.add(AVT);
                    if (st.startsWith("urn:schemas-upnp-org:device:MediaRenderer:") && !matches.contains(DEVICE_TYPE)) matches.add(DEVICE_TYPE);
                }
                if (matches.isEmpty()) continue;
                final InetAddress to = p.getAddress();
                final int toPort = p.getPort();
                int mx = 1;
                try { mx = Math.max(1, Math.min(3, Integer.parseInt(h.getOrDefault("mx", "1").trim()))); } catch (NumberFormatException ignored) { }
                final long delay = random.nextInt(mx * 300);
                pool.execute(() -> {
                    try {
                        Thread.sleep(delay);
                        for (String nt : matches) {
                            String r = "HTTP/1.1 200 OK\r\n"
                                    + "CACHE-CONTROL: max-age=1800\r\n"
                                    + "DATE: " + httpDate() + "\r\n"
                                    + "EXT:\r\n"
                                    + "LOCATION: " + location() + "\r\n"
                                    + "SERVER: " + SERVER + "\r\n"
                                    + "ST: " + nt + "\r\n"
                                    + "USN: " + usn(nt) + "\r\n\r\n";
                            byte[] b = r.getBytes(StandardCharsets.US_ASCII);
                            ssdp.send(new DatagramPacket(b, b.length, to, toPort));
                        }
                    } catch (Exception ignored) { }
                });
            } catch (SocketException e) {
                if (running) Log.w(TAG, "SSDP socket closed", e);
                return;
            } catch (Exception e) {
                if (running) Log.w(TAG, "SSDP error", e);
            }
        }
    }

    // ---------------- HTTP ----------------

    private void acceptLoop() {
        while (running) {
            try {
                Socket s = http.accept();
                pool.execute(() -> serve(s));
            } catch (IOException e) {
                if (running) Log.w(TAG, "DLNA accept failed", e);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(10_000);
            InputStream in = sock.getInputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) return;
            String method = req[0], path = req[1];
            Map<String, String> h = headers(head);
            int len = 0;
            try { len = Integer.parseInt(h.getOrDefault("content-length", "0").trim()); } catch (NumberFormatException ignored) { }
            byte[] body = readBody(in, len);
            OutputStream out = sock.getOutputStream();
            switch (method) {
                case "GET":
                case "HEAD":
                    String xml = document(path);
                    if (xml == null) respond(out, "404 Not Found", null, null, null);
                    else respond(out, "200 OK", "text/xml; charset=\"utf-8\"", method.equals("HEAD") ? null : xml, null);
                    break;
                case "POST":
                    handleSoap(out, path, h.get("soapaction"), new String(body, StandardCharsets.UTF_8));
                    break;
                case "SUBSCRIBE":
                    handleSubscribe(out, path, h);
                    break;
                case "UNSUBSCRIBE":
                    String sid = h.get("sid");
                    if (sid != null) subscriptions.remove(sid.trim());
                    respond(out, "200 OK", null, null, null);
                    break;
                default:
                    respond(out, "405 Method Not Allowed", null, null, null);
            }
        } catch (IOException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "DLNA request failed", e);
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c, state = 0;
        while ((c = in.read()) != -1) {
            b.write(c);
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return b.toString("ISO-8859-1");
            if (b.size() > 16384) return null;
        }
        return null;
    }

    private static byte[] readBody(InputStream in, int len) throws IOException {
        if (len <= 0) return new byte[0];
        byte[] b = new byte[Math.min(len, 1 << 20)];
        int n = 0;
        while (n < b.length) {
            int r = in.read(b, n, b.length - n);
            if (r < 0) break;
            n += r;
        }
        return b;
    }

    private static Map<String, String> headers(String head) {
        Map<String, String> h = new HashMap<>();
        for (String line : head.split("\r\n")) {
            int c = line.indexOf(':');
            if (c > 0) h.put(line.substring(0, c).trim().toLowerCase(Locale.US), line.substring(c + 1).trim());
        }
        return h;
    }

    private static void respond(OutputStream out, String status, String type, String body, Map<String, String> extra) throws IOException {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder r = new StringBuilder("HTTP/1.1 ").append(status).append("\r\n")
                .append("SERVER: ").append(SERVER).append("\r\n")
                .append("DATE: ").append(httpDate()).append("\r\n")
                .append("CONTENT-LENGTH: ").append(b.length).append("\r\n")
                .append("CONNECTION: close\r\n");
        if (type != null) r.append("CONTENT-TYPE: ").append(type).append("\r\n");
        if (extra != null) for (Map.Entry<String, String> e : extra.entrySet()) r.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        r.append("\r\n");
        out.write(r.toString().getBytes(StandardCharsets.UTF_8));
        out.write(b);
        out.flush();
    }

    private static String httpDate() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }

    // ---------------- descriptions ----------------

    private String document(String path) {
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        switch (path) {
            case "/description.xml": return deviceDescription();
            case "/AVTransport/scpd.xml": return Scpd.AVTRANSPORT;
            case "/RenderingControl/scpd.xml": return Scpd.RENDERING_CONTROL;
            case "/ConnectionManager/scpd.xml": return Scpd.CONNECTION_MANAGER;
            default: return null;
        }
    }

    private String deviceDescription() {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                + "<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">"
                + "<specVersion><major>1</major><minor>0</minor></specVersion><device>"
                + "<deviceType>" + DEVICE_TYPE + "</deviceType>"
                + "<friendlyName>" + esc(name) + "</friendlyName>"
                + "<manufacturer>MirrorBox</manufacturer><manufacturerURL>https://github.com/rays63/MirrorBox</manufacturerURL>"
                + "<modelDescription>AirPlay and DLNA receiver</modelDescription>"
                + "<modelName>MirrorBox</modelName><modelNumber>1.0</modelNumber>"
                + "<serialNumber>" + uuid + "</serialNumber>"
                + "<UDN>uuid:" + uuid + "</UDN>"
                + "<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>"
                + "<serviceList>"
                + service(AVT, "AVTransport") + service(RC, "RenderingControl") + service(CM, "ConnectionManager")
                + "</serviceList></device></root>";
    }

    private static String service(String type, String id) {
        return "<service><serviceType>" + type + "</serviceType><serviceId>urn:upnp-org:serviceId:" + id + "</serviceId>"
                + "<SCPDURL>/" + id + "/scpd.xml</SCPDURL><controlURL>/" + id + "/control</controlURL>"
                + "<eventSubURL>/" + id + "/event</eventSubURL></service>";
    }

    // ---------------- SOAP ----------------

    private void handleSoap(OutputStream out, String path, String soapAction, String body) throws IOException {
        String service = path.startsWith("/AVTransport") ? AVT : path.startsWith("/RenderingControl") ? RC
                : path.startsWith("/ConnectionManager") ? CM : null;
        String action = null;
        if (soapAction != null) {
            int hash = soapAction.indexOf('#');
            action = soapAction.substring(hash + 1).replace("\"", "").trim();
        }
        if (service == null || action == null) {
            respond(out, "404 Not Found", null, null, null);
            return;
        }
        Map<String, String> result = new java.util.LinkedHashMap<>();
        int error = 0;
        try {
            error = invoke(service, action, body, result);
        } catch (Exception e) {
            Log.w(TAG, "DLNA " + action + " failed", e);
            error = 501;
        }
        if (error != 0) {
            String fault = "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                    + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><s:Fault><faultcode>s:Client</faultcode>"
                    + "<faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">"
                    + "<errorCode>" + error + "</errorCode><errorDescription>" + (error == 401 ? "Invalid Action" : "Action Failed")
                    + "</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>";
            respond(out, "500 Internal Server Error", "text/xml; charset=\"utf-8\"", fault, null);
            return;
        }
        StringBuilder r = new StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>");
        r.append("<u:").append(action).append("Response xmlns:u=\"").append(service).append("\">");
        for (Map.Entry<String, String> e : result.entrySet()) {
            r.append('<').append(e.getKey()).append('>').append(esc(e.getValue())).append("</").append(e.getKey()).append('>');
        }
        r.append("</u:").append(action).append("Response></s:Body></s:Envelope>");
        respond(out, "200 OK", "text/xml; charset=\"utf-8\"", r.toString(), null);
    }

    private int invoke(String service, String action, String body, Map<String, String> r) {
        if (service.equals(AVT)) {
            switch (action) {
                case "SetAVTransportURI":
                    uri = arg(body, "CurrentURI");
                    metadata = arg(body, "CurrentURIMetaData");
                    Log.i(TAG, "DLNA SetAVTransportURI " + uri);
                    transportState = "STOPPED";
                    PlayerActivity p = PlayerActivity.instance;
                    if (p != null) main.post(() -> openPlayer(true));   // already showing: switch now
                    sendAvtEvent();
                    return 0;
                case "SetNextAVTransportURI":
                    nextUri = arg(body, "NextURI");
                    nextMetadata = arg(body, "NextURIMetaData");
                    return 0;
                case "Play":
                    if (uri.isEmpty()) return 701;
                    transportState = "TRANSITIONING";
                    main.post(() -> openPlayer(false));
                    sendAvtEvent();
                    return 0;
                case "Pause":
                    main.post(() -> { PlayerActivity a = PlayerActivity.instance; if (a != null) a.pausePlayback(); });
                    return 0;
                case "Stop":
                    main.post(() -> { PlayerActivity a = PlayerActivity.instance; if (a != null) a.stopPlayback(); });
                    if (!uri.isEmpty()) transportState = "STOPPED";
                    sendAvtEvent();
                    return 0;
                case "Seek": {
                    String unit = arg(body, "Unit"), target = arg(body, "Target");
                    if (!unit.equals("REL_TIME") && !unit.equals("ABS_TIME")) return 710;
                    final int ms = parseTime(target);
                    main.post(() -> { PlayerActivity a = PlayerActivity.instance; if (a != null) a.seekTo(ms); });
                    return 0;
                }
                case "Next":
                    if (nextUri.isEmpty()) return 711;
                    uri = nextUri; metadata = nextMetadata; nextUri = ""; nextMetadata = "";
                    main.post(() -> openPlayer(true));
                    return 0;
                case "Previous":
                    return 0;
                case "GetTransportInfo":
                    r.put("CurrentTransportState", transportState);
                    r.put("CurrentTransportStatus", "OK");
                    r.put("CurrentSpeed", "1");
                    return 0;
                case "GetPositionInfo": {
                    String dur = formatTime(PlayerActivity.durationMs), pos = formatTime(PlayerActivity.positionMs);
                    r.put("Track", uri.isEmpty() ? "0" : "1");
                    r.put("TrackDuration", dur);
                    r.put("TrackMetaData", metadata.isEmpty() ? "NOT_IMPLEMENTED" : metadata);
                    r.put("TrackURI", uri);
                    r.put("RelTime", pos);
                    r.put("AbsTime", pos);
                    r.put("RelCount", "2147483647");
                    r.put("AbsCount", "2147483647");
                    return 0;
                }
                case "GetMediaInfo":
                    r.put("NrTracks", uri.isEmpty() ? "0" : "1");
                    r.put("MediaDuration", formatTime(PlayerActivity.durationMs));
                    r.put("CurrentURI", uri);
                    r.put("CurrentURIMetaData", metadata);
                    r.put("NextURI", nextUri);
                    r.put("NextURIMetaData", nextMetadata);
                    r.put("PlayMedium", "NETWORK");
                    r.put("RecordMedium", "NOT_IMPLEMENTED");
                    r.put("WriteStatus", "NOT_IMPLEMENTED");
                    return 0;
                case "GetDeviceCapabilities":
                    r.put("PlayMedia", "NETWORK");
                    r.put("RecMedia", "NOT_IMPLEMENTED");
                    r.put("RecQualityModes", "NOT_IMPLEMENTED");
                    return 0;
                case "GetTransportSettings":
                    r.put("PlayMode", "NORMAL");
                    r.put("RecQualityMode", "NOT_IMPLEMENTED");
                    return 0;
                case "GetCurrentTransportActions":
                    r.put("Actions", "Play,Pause,Stop,Seek");
                    return 0;
                default:
                    return 401;
            }
        } else if (service.equals(RC)) {
            AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            switch (action) {
                case "GetVolume":
                    r.put("CurrentVolume", String.valueOf(Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max)));
                    return 0;
                case "SetVolume": {
                    int v = Integer.parseInt(arg(body, "DesiredVolume").trim());
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(Math.max(0, Math.min(100, v)) * max / 100f), AudioManager.FLAG_SHOW_UI);
                    sendRcEvent();
                    return 0;
                }
                case "GetMute":
                    r.put("CurrentMute", am.isStreamMute(AudioManager.STREAM_MUSIC) ? "1" : "0");
                    return 0;
                case "SetMute": {
                    String m = arg(body, "DesiredMute").trim();
                    boolean mute = m.equals("1") || m.equalsIgnoreCase("true");
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, mute ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI);
                    sendRcEvent();
                    return 0;
                }
                case "ListPresets":
                    r.put("CurrentPresetNameList", "FactoryDefaults");
                    return 0;
                case "SelectPreset":
                    return 0;
                default:
                    return 401;
            }
        } else {
            switch (action) {
                case "GetProtocolInfo":
                    r.put("Source", "");
                    r.put("Sink", SINK_PROTOCOLS);
                    return 0;
                case "GetCurrentConnectionIDs":
                    r.put("ConnectionIDs", "0");
                    return 0;
                case "GetCurrentConnectionInfo":
                    r.put("RcsID", "0");
                    r.put("AVTransportID", "0");
                    r.put("ProtocolInfo", "");
                    r.put("PeerConnectionManager", "");
                    r.put("PeerConnectionID", "-1");
                    r.put("Direction", "Input");
                    r.put("Status", "OK");
                    return 0;
                default:
                    return 401;
            }
        }
    }

    /** Opens (or retargets) the player on the current URI. Main thread. */
    private void openPlayer(boolean force) {
        PlayerActivity a = PlayerActivity.instance;
        if (a != null && !force && uri.equals(a.currentUri())) {
            a.resumePlayback();
            return;
        }
        Intent i = new Intent(ctx, PlayerActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(PlayerActivity.EXTRA_URI, uri)
                .putExtra(PlayerActivity.EXTRA_TITLE, didl(metadata, "dc:title"))
                .putExtra(PlayerActivity.EXTRA_KIND, mediaKind(metadata, uri));
        try {
            ctx.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the player", e);
        }
    }

    /** Called by PlayerActivity on the main thread. */
    void onPlayerState(String state) {
        if (state.equals(transportState)) return;
        transportState = state;
        sendAvtEvent();
    }

    // ---------------- GENA events ----------------

    private void handleSubscribe(OutputStream out, String path, Map<String, String> h) throws IOException {
        String service = path.startsWith("/AVTransport") ? AVT : path.startsWith("/RenderingControl") ? RC : CM;
        String sid = h.get("sid");
        long timeout = 1800;
        Map<String, String> extra = new HashMap<>();
        if (sid != null) {   // renewal
            Subscription s = subscriptions.get(sid.trim());
            if (s == null) { respond(out, "412 Precondition Failed", null, null, null); return; }
            s.expires = System.currentTimeMillis() + timeout * 1000;
            extra.put("SID", sid.trim());
            extra.put("TIMEOUT", "Second-" + timeout);
            respond(out, "200 OK", null, null, extra);
            return;
        }
        String cb = h.get("callback");
        if (cb == null) { respond(out, "412 Precondition Failed", null, null, null); return; }
        List<String> urls = new ArrayList<>();
        Matcher m = Pattern.compile("<([^>]+)>").matcher(cb);
        while (m.find()) urls.add(m.group(1));
        if (urls.isEmpty()) { respond(out, "412 Precondition Failed", null, null, null); return; }
        Subscription s = new Subscription(service, urls);
        s.expires = System.currentTimeMillis() + timeout * 1000;
        String newSid = "uuid:" + UUID.randomUUID();
        subscriptions.put(newSid, s);
        extra.put("SID", newSid);
        extra.put("TIMEOUT", "Second-" + timeout);
        respond(out, "200 OK", null, null, extra);
        // initial event
        pool.execute(() -> sendEvent(newSid, s, initialProperties(service)));
    }

    private String initialProperties(String service) {
        if (service.equals(AVT)) return property("LastChange", avtLastChange());
        if (service.equals(RC)) return property("LastChange", rcLastChange());
        return property("SourceProtocolInfo", "") + property("SinkProtocolInfo", SINK_PROTOCOLS) + property("CurrentConnectionIDs", "0");
    }

    private void sendAvtEvent() { broadcast(AVT, property("LastChange", avtLastChange())); }

    private void sendRcEvent() { broadcast(RC, property("LastChange", rcLastChange())); }

    private void broadcast(String service, String props) {
        if (pool == null || !running) return;
        for (Map.Entry<String, Subscription> e : subscriptions.entrySet()) {
            if (e.getValue().service.equals(service)) {
                String sid = e.getKey();
                Subscription s = e.getValue();
                try { pool.execute(() -> sendEvent(sid, s, props)); } catch (Exception ignored) { }
            }
        }
    }

    private String avtLastChange() {
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"
                + "<TransportState val=\"" + transportState + "\"/>"
                + "<TransportStatus val=\"OK\"/>"
                + "<CurrentPlayMode val=\"NORMAL\"/>"
                + "<TransportPlaySpeed val=\"1\"/>"
                + "<NumberOfTracks val=\"" + (uri.isEmpty() ? 0 : 1) + "\"/>"
                + "<CurrentTrack val=\"" + (uri.isEmpty() ? 0 : 1) + "\"/>"
                + "<AVTransportURI val=\"" + esc(uri) + "\"/>"
                + "<CurrentTrackURI val=\"" + esc(uri) + "\"/>"
                + "<CurrentTrackDuration val=\"" + formatTime(PlayerActivity.durationMs) + "\"/>"
                + "<CurrentMediaDuration val=\"" + formatTime(PlayerActivity.durationMs) + "\"/>"
                + "<CurrentTransportActions val=\"Play,Pause,Stop,Seek\"/>"
                + "</InstanceID></Event>";
    }

    private String rcLastChange() {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int vol = Math.round(100f * am.getStreamVolume(AudioManager.STREAM_MUSIC) / max);
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">"
                + "<Volume channel=\"Master\" val=\"" + vol + "\"/>"
                + "<Mute channel=\"Master\" val=\"" + (am.isStreamMute(AudioManager.STREAM_MUSIC) ? 1 : 0) + "\"/>"
                + "</InstanceID></Event>";
    }

    private static String property(String name, String value) {
        return "<e:property><" + name + ">" + esc(value) + "</" + name + "></e:property>";
    }

    private void sendEvent(String sid, Subscription s, String props) {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">"
                + props + "</e:propertyset>";
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        int seq;
        synchronized (s) { seq = s.seq++; }
        for (String cb : s.callbacks) {
            try {
                URL u = new URL(cb);
                int p = u.getPort() > 0 ? u.getPort() : 80;
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress(u.getHost(), p), 3000);
                    sock.setSoTimeout(3000);
                    String path = u.getFile().isEmpty() ? "/" : u.getFile();
                    String head = "NOTIFY " + path + " HTTP/1.1\r\n"
                            + "HOST: " + u.getHost() + ":" + p + "\r\n"
                            + "CONTENT-TYPE: text/xml; charset=\"utf-8\"\r\n"
                            + "CONTENT-LENGTH: " + b.length + "\r\n"
                            + "NT: upnp:event\r\nNTS: upnp:propchange\r\n"
                            + "SID: " + sid + "\r\nSEQ: " + seq + "\r\nCONNECTION: close\r\n\r\n";
                    OutputStream o = sock.getOutputStream();
                    o.write(head.getBytes(StandardCharsets.US_ASCII));
                    o.write(b);
                    o.flush();
                    sock.getInputStream().read();   // wait for the status line
                }
                return;
            } catch (Exception ignored) { }
        }
    }

    private void pruneSubscriptions() {
        long now = System.currentTimeMillis();
        subscriptions.entrySet().removeIf(e -> e.getValue().expires < now);
    }

    // ---------------- helpers ----------------

    private static String arg(String body, String name) {
        Matcher m = Pattern.compile("<(?:[A-Za-z0-9_]+:)?" + name + "(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_]+:)?" + name + ">",
                Pattern.DOTALL).matcher(body);
        if (m.find()) return unesc(m.group(1).trim());
        return "";
    }

    /** Reads a DIDL-Lite element (e.g. dc:title, upnp:class) from metadata. */
    static String didl(String meta, String tag) {
        if (meta == null || meta.isEmpty()) return "";
        Matcher m = Pattern.compile("<" + Pattern.quote(tag) + "(?:\\s[^>]*)?>(.*?)</" + Pattern.quote(tag) + ">", Pattern.DOTALL).matcher(meta);
        return m.find() ? unesc(m.group(1).trim()) : "";
    }

    /** "video", "audio" or "image", from upnp:class, protocolInfo or the URL extension. */
    static String mediaKind(String meta, String uri) {
        String cls = didl(meta, "upnp:class");
        if (cls.contains("imageItem")) return "image";
        if (cls.contains("audioItem")) return "audio";
        if (cls.contains("videoItem")) return "video";
        Matcher m = Pattern.compile("protocolInfo=\"[^:]*:[^:]*:([a-z]+)/").matcher(meta == null ? "" : meta);
        if (m.find()) return m.group(1).equals("image") ? "image" : m.group(1).equals("audio") ? "audio" : "video";
        String u = uri.toLowerCase(Locale.US);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (u.matches(".*\\.(jpe?g|png|gif|webp|bmp|heic)$")) return "image";
        if (u.matches(".*\\.(mp3|m4a|aac|flac|ogg|opus|wav|wma)$")) return "audio";
        return "video";
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String unesc(String s) {
        return s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&");
    }

    static String formatTime(long ms) {
        if (ms <= 0) return "0:00:00";
        long s = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    static int parseTime(String t) {
        try {
            String[] p = t.trim().split(":");
            double sec = 0;
            for (String part : p) sec = sec * 60 + Double.parseDouble(part);
            return (int) (sec * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String buildSinkProtocols() {
        String[] mimes = {"video/mp4", "video/x-matroska", "video/webm", "video/mpeg", "video/x-msvideo", "video/avi",
                "video/quicktime", "video/3gpp", "video/mp2t", "video/vnd.dlna.mpeg-tts", "video/x-flv", "video/x-ms-wmv",
                "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac",
                "audio/flac", "audio/x-flac", "audio/ogg", "audio/opus", "audio/wav", "audio/x-wav", "audio/L16",
                "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"};
        StringBuilder b = new StringBuilder();
        for (String m : mimes) b.append("http-get:*:").append(m).append(":*,");
        b.append("http-get:*:*:*");
        return b.toString();
    }
}
