/*
 * MirrorBox JNI bridge: runs the UxPlay AirPlay core and forwards mirrored H.264 video and
 * decrypted audio to Java (tv.local.airreceiver.AirPlayServer).
 *
 * Copyright (C) 2026 MirrorBox contributors. GPL-3.0-or-later (see LICENSE).
 */
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include "raop.h"
#include "dnssd.h"
#include "logger.h"
#include "stream.h"

#define TAG "MirrorBoxNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

void *alac_glue_create(void);
int alac_glue_decode(void *dec, const uint8_t *in, int in_len, int16_t *out, int max_frames);
void alac_glue_destroy(void *dec);

static JavaVM *g_vm;
static jobject g_server;            /* global ref to the AirPlayServer instance */
static jmethodID m_onVideo, m_onVideoFormat, m_onAudio, m_onAudioFormat, m_onEvent, m_onVolume,
                 m_onMetadata, m_onCoverArt;
static pthread_key_t g_env_key;

static raop_t *g_raop;
static dnssd_t *g_dnssd;
static int g_log_level = LOGGER_INFO;
static double g_volume = 0.0;       /* AirPlay dB, -30..0, -144 = mute */

/* ALAC decoder state, used only from the audio thread */
static void *g_alac;
static int16_t g_pcm[4096 * 2];

/* Reset thread: httpd stop/start must not run on a raop thread */
static pthread_t g_reset_thread;
static pthread_mutex_t g_reset_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_cond_t g_reset_cond = PTHREAD_COND_INITIALIZER;
static bool g_reset_pending, g_reset_keep_connections, g_reset_quit;

/* Event codes shared with AirPlayServer.java */
enum { EV_CONN_INIT = 1, EV_CONN_DESTROY = 2, EV_CONN_RESET = 3, EV_VIDEO_RESET = 4, EV_AUDIO_FLUSH = 5,
       EV_VIDEO_FLUSH = 6, EV_VIDEO_PAUSE = 7, EV_VIDEO_RESUME = 8, EV_CLIENT = 9, EV_MIRROR_RUNNING = 10,
       EV_MIRROR_STOPPED = 11, EV_COVERART_STOP = 12 };

static void detach_thread(void *unused) {
    (*g_vm)->DetachCurrentThread(g_vm);
}

static JNIEnv *get_env(void) {
    JNIEnv *env = NULL;
    if ((*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6) == JNI_OK) return env;
    if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
    pthread_setspecific(g_env_key, env);   /* destructor detaches when the native thread exits */
    return env;
}

static void clear_exc(JNIEnv *env) {
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear(env);
    }
}

static void post_event(int code, const char *text) {
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    jstring s = text ? (*env)->NewStringUTF(env, text) : NULL;
    (*env)->CallVoidMethod(env, g_server, m_onEvent, code, s);
    if (s) (*env)->DeleteLocalRef(env, s);
    clear_exc(env);
}

static void post_bytes(jmethodID m, const void *data, int len) {
    JNIEnv *env = get_env();
    if (!env || !g_server || len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) { clear_exc(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) data);
    (*env)->CallVoidMethod(env, g_server, m, arr);
    (*env)->DeleteLocalRef(env, arr);
    clear_exc(env);
}

static void request_reset(bool keep_connections) {
    pthread_mutex_lock(&g_reset_mutex);
    g_reset_pending = true;
    g_reset_keep_connections = keep_connections;
    pthread_cond_signal(&g_reset_cond);
    pthread_mutex_unlock(&g_reset_mutex);
}

static void *reset_loop(void *arg) {
    for (;;) {
        pthread_mutex_lock(&g_reset_mutex);
        while (!g_reset_pending && !g_reset_quit) pthread_cond_wait(&g_reset_cond, &g_reset_mutex);
        bool quit = g_reset_quit;
        bool keep = g_reset_keep_connections;
        g_reset_pending = false;
        pthread_mutex_unlock(&g_reset_mutex);
        if (quit) break;
        if (!g_raop) continue;
        LOGI("resetting AirPlay server (keep connections: %d)", keep);
        raop_stop_httpd(g_raop);
        if (!keep) raop_remove_known_connections(g_raop);
        unsigned short port = raop_get_port(g_raop);
        raop_start_httpd(g_raop, &port);
        raop_set_port(g_raop, port);
    }
    return NULL;
}

/* ---------- raop callbacks ---------- */

static void cb_log(void *cls, int level, const char *msg) {
    int prio = level <= LOGGER_ERR ? ANDROID_LOG_ERROR : level == LOGGER_WARNING ? ANDROID_LOG_WARN
             : level <= LOGGER_INFO ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_print(prio, "UxPlay", "%s", msg);
}

static void cb_conn_init(void *cls) { post_event(EV_CONN_INIT, NULL); }
static void cb_conn_destroy(void *cls) { post_event(EV_CONN_DESTROY, NULL); }
static void cb_conn_feedback(void *cls) { }

static void cb_conn_reset(void *cls, int reason) {
    LOGI("conn_reset reason %d", reason);
    post_event(EV_CONN_RESET, NULL);
    request_reset(false);
}

static void cb_video_reset(void *cls, reset_type_t type) {
    LOGI("video_reset type %d", (int) type);
    post_event(EV_VIDEO_RESET, NULL);
    if (type == RESET_TYPE_NOHOLD || type == RESET_TYPE_HLS_EOS) {
        /* NOHOLD: a new client took over; the old connections were already closed */
    }
}

static int cb_video_set_codec_and_size(void *cls, video_codec_t codec, unsigned short width_source,
                                       unsigned short height_source, unsigned short x_offset, unsigned short y_offset,
                                       unsigned short width, unsigned short height) {
    JNIEnv *env = get_env();
    if (!env || !g_server) return -1;
    jint ok = (*env)->CallIntMethod(env, g_server, m_onVideoFormat, codec == VIDEO_CODEC_H265,
                                    (jint) width_source, (jint) height_source, (jint) width, (jint) height);
    clear_exc(env);
    return ok;
}

static void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    JNIEnv *env = get_env();
    if (!env || !g_server || data->data_len <= 0) return;
    jbyteArray arr = (*env)->NewByteArray(env, data->data_len);
    if (!arr) { clear_exc(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, data->data_len, (const jbyte *) data->data);
    (*env)->CallVoidMethod(env, g_server, m_onVideo, arr, (jlong) data->ntp_time_remote);
    (*env)->DeleteLocalRef(env, arr);
    clear_exc(env);
}

static void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen,
                                bool *isMedia, uint64_t *audioFormat) {
    LOGI("audio format ct=%d spf=%d usingScreen=%d isMedia=%d", *ct, *spf, *usingScreen, *isMedia);
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    (*env)->CallVoidMethod(env, g_server, m_onAudioFormat, (jint) *ct, (jint) *spf, (jboolean) *usingScreen);
    clear_exc(env);
}

static void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    JNIEnv *env = get_env();
    if (!env || !g_server || data->data_len <= 0) return;
    const void *payload = data->data;
    int len = data->data_len;
    int ct = data->ct;
    if (ct == 2) {   /* ALAC: decode here, hand PCM to Java as ct 1 */
        if (!g_alac) g_alac = alac_glue_create();
        int frames = alac_glue_decode(g_alac, data->data, data->data_len, g_pcm, 4096);
        if (frames <= 0) return;
        payload = g_pcm;
        len = frames * 4;
        ct = 0;      /* 0 = little-endian PCM s16 stereo */
    }
    jbyteArray arr = (*env)->NewByteArray(env, len);
    if (!arr) { clear_exc(env); return; }
    (*env)->SetByteArrayRegion(env, arr, 0, len, (const jbyte *) payload);
    (*env)->CallVoidMethod(env, g_server, m_onAudio, arr, (jint) ct, (jlong) data->ntp_time_remote);
    (*env)->DeleteLocalRef(env, arr);
    clear_exc(env);
}

static void cb_audio_flush(void *cls) { post_event(EV_AUDIO_FLUSH, NULL); }
static void cb_video_flush(void *cls) { post_event(EV_VIDEO_FLUSH, NULL); }
static void cb_video_pause(void *cls) { post_event(EV_VIDEO_PAUSE, NULL); }
static void cb_video_resume(void *cls) { post_event(EV_VIDEO_RESUME, NULL); }

static double cb_audio_set_client_volume(void *cls) { return g_volume; }

static void cb_audio_set_volume(void *cls, float volume) {
    g_volume = volume;
    JNIEnv *env = get_env();
    if (!env || !g_server) return;
    (*env)->CallVoidMethod(env, g_server, m_onVolume, (jfloat) volume);
    clear_exc(env);
}

static void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) { post_bytes(m_onMetadata, buffer, buflen); }
static void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) { post_bytes(m_onCoverArt, buffer, buflen); }
static void cb_audio_stop_coverart(void *cls) { post_event(EV_COVERART_STOP, NULL); }
static void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) { }
static void cb_audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote) { }
static void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) { }

static void cb_mirror_video_running(void *cls, bool running) {
    post_event(running ? EV_MIRROR_RUNNING : EV_MIRROR_STOPPED, NULL);
}

static void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    char buf[256];
    snprintf(buf, sizeof(buf), "%s|%s", name ? name : "", model ? model : "");
    post_event(EV_CLIENT, buf);
    *admit = true;
}

static void cb_display_pin(void *cls, char *pin) { }
static void cb_register_client(void *cls, const char *device_id, const char *pk, const char *name) { }
static bool cb_check_register(void *cls, const char *pk) { return true; }
static const char *cb_passwd(void *cls, int *len) { *len = 0; return NULL; }

/* HLS (AirPlay video URL) is not advertised; stubs keep the core safe if a client tries anyway */
static void cb_on_video_play(void *cls, const char *location, const float start) { }
static void cb_on_video_scrub(void *cls, const float position) { }
static void cb_on_video_rate(void *cls, const float rate) { }
static void cb_on_video_stop(void *cls) { }
static void cb_on_video_acquire_playback_info(void *cls, playback_info_t *info) { }
static float cb_on_video_playlist_remove(void *cls) { return 0.0f; }
static void cb_get_custom_profile(void *cls, const char **profile) { *profile = NULL; }

/* ---------- JNI ---------- */

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    g_vm = vm;
    pthread_key_create(&g_env_key, detach_thread);
    return JNI_VERSION_1_6;
}

/*
 * nativeStart(name, deviceId "aa:bb:cc:dd:ee:ff", keyFile, width, height, fps, debug) -> port, or negative error
 */
JNIEXPORT jint JNICALL
Java_tv_local_airreceiver_AirPlayServer_nativeStart(JNIEnv *env, jobject thiz, jstring jname, jstring jdevice_id,
                                                    jstring jkeyfile, jint width, jint height, jint fps, jboolean debug) {
    if (g_raop) return -100;
    jclass cls = (*env)->GetObjectClass(env, thiz);
    m_onVideo = (*env)->GetMethodID(env, cls, "onNativeVideo", "([BJ)V");
    m_onVideoFormat = (*env)->GetMethodID(env, cls, "onNativeVideoFormat", "(ZIIII)I");
    m_onAudio = (*env)->GetMethodID(env, cls, "onNativeAudio", "([BIJ)V");
    m_onAudioFormat = (*env)->GetMethodID(env, cls, "onNativeAudioFormat", "(IIZ)V");
    m_onEvent = (*env)->GetMethodID(env, cls, "onNativeEvent", "(ILjava/lang/String;)V");
    m_onVolume = (*env)->GetMethodID(env, cls, "onNativeVolume", "(F)V");
    m_onMetadata = (*env)->GetMethodID(env, cls, "onNativeMetadata", "([B)V");
    m_onCoverArt = (*env)->GetMethodID(env, cls, "onNativeCoverArt", "([B)V");
    if ((*env)->ExceptionCheck(env)) return -101;
    g_server = (*env)->NewGlobalRef(env, thiz);
    g_log_level = debug ? LOGGER_DEBUG : LOGGER_INFO;

    const char *name = (*env)->GetStringUTFChars(env, jname, NULL);
    const char *device_id = (*env)->GetStringUTFChars(env, jdevice_id, NULL);
    const char *keyfile = (*env)->GetStringUTFChars(env, jkeyfile, NULL);
    int ret = 0;

    /* hw_addr bytes from "aa:bb:cc:dd:ee:ff" */
    char hw_addr[6];
    unsigned int b[6];
    if (sscanf(device_id, "%x:%x:%x:%x:%x:%x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6) { ret = -102; goto out; }
    for (int i = 0; i < 6; i++) hw_addr[i] = (char) b[i];

    ntp_global_init();

    int err = 0;
    g_dnssd = dnssd_init(name, (int) strlen(name), hw_addr, 6, 0, &err);
    if (!g_dnssd || err) { LOGE("dnssd_init failed %d", err); ret = -103; goto out; }
    /* features: same choices as uxplay defaults, no HLS, H.264 only, legacy pairing on */
    dnssd_set_airplay_features(g_dnssd, 0, 0);
    dnssd_set_airplay_features(g_dnssd, 4, 0);
    dnssd_set_airplay_features(g_dnssd, 42, 0);
    dnssd_set_airplay_features(g_dnssd, 27, 1);

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.conn_reset = cb_conn_reset;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.audio_remote_control_id = cb_audio_remote_control_id;
    cbs.export_dacp = cb_export_dacp;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = NULL;   /* no password */
    (void) cb_passwd;
    cbs.video_reset = cb_video_reset;
    cbs.video_set_codec_and_size = cb_video_set_codec_and_size;
    cbs.get_custom_profile = cb_get_custom_profile;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;

    g_raop = raop_init(&cbs);
    if (!g_raop) { ret = -104; goto out; }
    raop_set_log_callback(g_raop, cb_log, NULL);
    raop_set_log_level(g_raop, g_log_level);
    if (raop_init2(g_raop, 1, device_id, keyfile)) { ret = -105; goto out; }

    if (width) raop_set_plist(g_raop, "width", width);
    if (height) raop_set_plist(g_raop, "height", height);
    raop_set_plist(g_raop, "refreshRate", 60);          /* the TV panel */
    if (fps) raop_set_plist(g_raop, "maxFPS", fps);     /* cap what the sender encodes */

    unsigned short tcp[2] = {0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);
    unsigned short port = raop_get_port(g_raop);
    if (raop_start_httpd(g_raop, &port) < 0) { ret = -106; goto out; }
    raop_set_port(g_raop, port);
    raop_set_dnssd(g_raop, g_dnssd);

    if (dnssd_register_raop(g_dnssd, port)) { LOGE("raop mDNS registration failed"); ret = -107; goto out; }
    if (dnssd_register_airplay(g_dnssd, port)) { LOGE("airplay mDNS registration failed"); ret = -108; goto out; }

    g_reset_quit = false;
    pthread_create(&g_reset_thread, NULL, reset_loop, NULL);
    LOGI("AirPlay server '%s' running on port %d (features 0x%llX)", name, port,
         (unsigned long long) dnssd_get_airplay_features(g_dnssd));
    ret = port;

out:
    (*env)->ReleaseStringUTFChars(env, jname, name);
    (*env)->ReleaseStringUTFChars(env, jdevice_id, device_id);
    (*env)->ReleaseStringUTFChars(env, jkeyfile, keyfile);
    if (ret < 0) {
        if (g_raop) { raop_destroy(g_raop); g_raop = NULL; }
        if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = NULL; }
        (*env)->DeleteGlobalRef(env, g_server);
        g_server = NULL;
    }
    return ret;
}

JNIEXPORT void JNICALL
Java_tv_local_airreceiver_AirPlayServer_nativeStop(JNIEnv *env, jobject thiz) {
    if (!g_raop) return;
    pthread_mutex_lock(&g_reset_mutex);
    g_reset_quit = true;
    pthread_cond_signal(&g_reset_cond);
    pthread_mutex_unlock(&g_reset_mutex);
    pthread_join(g_reset_thread, NULL);
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    raop_destroy(g_raop);
    g_raop = NULL;
    if (g_dnssd) { dnssd_destroy(g_dnssd); g_dnssd = NULL; }
    if (g_alac) { alac_glue_destroy(g_alac); g_alac = NULL; }
    if (g_server) { (*env)->DeleteGlobalRef(env, g_server); g_server = NULL; }
}

/* Drops the current client (e.g. the user pressed Back on the TV). */
JNIEXPORT void JNICALL
Java_tv_local_airreceiver_AirPlayServer_nativeDisconnect(JNIEnv *env, jobject thiz) {
    if (g_raop) request_reset(false);
}
