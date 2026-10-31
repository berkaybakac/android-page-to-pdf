#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")"
sdk="${ANDROID_SDK_ROOT:-$HOME/Library/Android/sdk}"
jdk="${BORAN_JDK:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
build_tools="$sdk/build-tools/36.0.0"
android_jar="$sdk/platforms/android-36/android.jar"
export JAVA_HOME="$jdk"
export PATH="$jdk/bin:$PATH"
mkdir -p build/classes build/dex build/res .signing
find build/classes -name '*.class' -delete
# Personal artwork is optional and stays outside version control.
rm -rf build/res-src
mkdir -p build/res-src
cp -R res/. build/res-src/
if [ "${BORAN_PERSONAL_RESOURCES:-1}" = "1" ] && [ -d local-res ]; then
    cp -R local-res/. build/res-src/
fi
"$build_tools/aapt2" compile --dir build/res-src -o build/res.zip
"$build_tools/aapt2" link -o build/base.apk --manifest AndroidManifest.xml -I "$android_jar" build/res.zip
find src -name '*.java' > build/sources.txt
"$jdk/bin/javac" -encoding UTF-8 -source 17 -target 17 -classpath "$android_jar" -d build/classes @build/sources.txt
"$jdk/bin/jar" cf build/classes.jar -C build/classes .
"$build_tools/d8" --lib "$android_jar" --min-api 34 --output build/dex build/classes.jar
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -u ../unsigned.apk classes.dex)
"$build_tools/zipalign" -f 4 build/unsigned.apk build/aligned.apk
if [ ! -f .signing/boran.jks ]; then
    umask 077
    openssl rand -hex 24 > .signing/password
    "$jdk/bin/keytool" -genkeypair -keystore .signing/boran.jks -storepass:file .signing/password -keypass:file .signing/password -alias boran -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Boran PDF'
fi
"$build_tools/apksigner" sign --ks .signing/boran.jks --ks-key-alias boran --ks-pass file:.signing/password --out build/Boran-PDF.apk build/aligned.apk
"$build_tools/apksigner" verify build/Boran-PDF.apk
echo "APK hazır: $(pwd)/build/Boran-PDF.apk"
