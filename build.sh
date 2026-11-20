#!/bin/sh
# Builds and signs mirrorbox.apk (package tv.local.airreceiver) with the local Android SDK + NDK, no Gradle:
#   1. OpenSSL libcrypto for armeabi-v7a (once, cached in build-cache/)
#   2. ndk-build: UxPlay core + libplist + ALAC + JNI bridge -> libairreceiver.so
#   3. aapt2 + javac + d8 + zipalign + apksigner
set -e
cd "$(dirname "$0")"
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"; JBR="$JAVA_HOME/bin"
BT="$SDK/build-tools/36.1.0"; JAR="$SDK/platforms/android-36.1/android.jar"
NDK="${ANDROID_NDK_ROOT:-$(ls -d "$SDK"/ndk/* | sort -V | tail -1)}"
ABI=armeabi-v7a
OPENSSL_TAG=openssl-3.0.22

# 1. OpenSSL (static libcrypto only)
if [ ! -f "build-cache/openssl-$ABI/lib/libcrypto.a" ]; then
  mkdir -p build-cache
  [ -d build-cache/openssl-src ] || git clone --depth 1 -b "$OPENSSL_TAG" https://github.com/openssl/openssl.git build-cache/openssl-src
  ( cd build-cache/openssl-src
    export ANDROID_NDK_ROOT="$NDK" PATH="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin:$PATH"
    ./Configure android-arm -D__ANDROID_API__=26 no-shared no-tests no-ssl no-tls no-dtls no-engine no-module \
      no-legacy no-comp no-ui-console no-async -fPIC --prefix="$PWD/../openssl-$ABI" >/dev/null
    make -j8 build_libs >/dev/null && make install_dev >/dev/null )
fi

# 2. native library
"$NDK/ndk-build" -C . NDK_PROJECT_PATH=. APP_BUILD_SCRIPT=jni/Android.mk NDK_APPLICATION_MK=jni/Application.mk \
  NDK_OUT=build/obj NDK_LIBS_OUT=build/lib -j8 | grep -v "^\[.*Compile\|^make"

# 3. APK
rm -rf build/classes build/dex build/gen build/*.apk build/res.zip && mkdir -p build/classes build/dex build/gen
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$JAR" --manifest AndroidManifest.xml --java build/gen build/res.zip
"$JBR/javac" --release 17 -Xlint:-options -nowarn -cp "$JAR" -d build/classes $(find src build/gen -name '*.java')
"$BT/d8" --min-api 26 --lib "$JAR" --output build/dex $(find build/classes -name '*.class')
cp build/base.apk build/unsigned.apk
(cd build/dex && "$JBR/jar" uf ../unsigned.apk classes.dex)
# native libs must be stored uncompressed and page-aligned so Android can map them in place
(cd build && "$JBR/jar" uf0 unsigned.apk lib/$ABI/libairreceiver.so)
"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk
[ -f debug.keystore ] || "$JBR/keytool" -genkeypair -keystore debug.keystore -storepass android -keypass android -alias key -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=mirrorbox" >/dev/null 2>&1
"$BT/apksigner" sign --ks debug.keystore --ks-pass pass:android --out mirrorbox.apk build/aligned.apk
echo BUILT mirrorbox.apk
