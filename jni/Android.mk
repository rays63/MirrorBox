LOCAL_PATH := $(call my-dir)

# OpenSSL libcrypto, cross-compiled by build.sh (build-cache/openssl-<abi>)
include $(CLEAR_VARS)
LOCAL_MODULE := crypto_static
LOCAL_SRC_FILES := ../build-cache/openssl-$(TARGET_ARCH_ABI)/lib/libcrypto.a
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/../build-cache/openssl-$(TARGET_ARCH_ABI)/include
include $(PREBUILT_STATIC_LIBRARY)

# libplist (LGPL-2.1)
include $(CLEAR_VARS)
LOCAL_MODULE := plist
LOCAL_SRC_FILES := $(addprefix libplist/src/, base64.c bytearray.c hashtable.c ptrarray.c time64.c common.c \
                     xplist.c bplist.c jsmn.c jplist.c oplist.c out-default.c out-plutil.c out-limd.c plist.c) \
                   libplist/libcnary/node.c libplist/libcnary/node_list.c
LOCAL_C_INCLUDES := $(LOCAL_PATH)/libplist/include $(LOCAL_PATH)/libplist/src $(LOCAL_PATH)/libplist/libcnary/include
LOCAL_CFLAGS := -O2 -DHAVE_STRNDUP -DHAVE_GMTIME_R -DHAVE_LOCALTIME_R -DHAVE_MEMMEM -DHAVE_TM_TM_GMTOFF -DHAVE_TM_TM_ZONE \
                -DPACKAGE_VERSION=\"2.7.0\" -Wno-everything
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/libplist/include
include $(BUILD_STATIC_LIBRARY)

# Apple ALAC decoder (Apache-2.0)
include $(CLEAR_VARS)
LOCAL_MODULE := alac
LOCAL_SRC_FILES := alac/ALACDecoder.cpp alac/ALACBitUtilities.c alac/EndianPortable.c alac/ag_dec.c alac/dp_dec.c alac/matrix_dec.c
LOCAL_CFLAGS := -O2 -Wno-everything
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/alac
include $(BUILD_STATIC_LIBRARY)

# UxPlay protocol core (lib/ of UxPlay: RAOP/AirPlay, FairPlay, mirroring, built-in mDNS responder)
include $(CLEAR_VARS)
LOCAL_MODULE := airplay
UX := uxplay
LOCAL_SRC_FILES := $(addprefix $(UX)/, airplay_video.c byteutils.c compat.c crypto.c dnssd.c fairplay_playfair.c \
                     http_request.c http_response.c httpd.c logger.c mirror_buffer.c netutils.c pairing.c raop.c \
                     raop_buffer.c raop_ntp.c raop_rtp.c raop_rtp_mirror.c srp.c utils.c \
                     playfair/hand_garble.c playfair/modified_md5.c playfair/omg_hax.c playfair/playfair.c playfair/sap_hash.c \
                     fairplay_v2/fairplay_v2.c \
                     llhttp/api.c llhttp/http.c llhttp/llhttp.c \
                     mdnsd/mdnsd.c mdnsd/dnssd_mdnsd.c)
LOCAL_C_INCLUDES := $(LOCAL_PATH)/$(UX) $(LOCAL_PATH)/$(UX)/playfair $(LOCAL_PATH)/$(UX)/fairplay_v2 $(LOCAL_PATH)/$(UX)/llhttp
LOCAL_CFLAGS := -O2 -DSTANDALONE -D__STDC_CONSTANT_MACROS -D__STDC_LIMIT_MACROS -DTARGET_POSIX -D_LINUX -D_REENTRANT \
                -DNOHOLD -DPLIST_210 -DPLIST_230 -DOPENSSL_API_COMPAT=0x10101000L -Wno-everything
LOCAL_STATIC_LIBRARIES := plist crypto_static
LOCAL_EXPORT_C_INCLUDES := $(LOCAL_PATH)/$(UX)
include $(BUILD_STATIC_LIBRARY)

# JNI bridge
include $(CLEAR_VARS)
LOCAL_MODULE := airreceiver
LOCAL_SRC_FILES := airreceiver_jni.c alac_glue.cpp
LOCAL_CFLAGS := -O2 -Wall -Wno-unused-parameter
LOCAL_STATIC_LIBRARIES := airplay plist alac crypto_static
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
