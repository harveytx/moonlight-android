# 8. Compilar futuras versiones

## 8.1 Requisitos

* JDK 17 o 21.
* Android SDK con `platforms;android-37`, `build-tools;37.0.0` y **NDK `29.0.14206865`**
  (versión fijada en `app/build.gradle`).
* Git con submódulos.

## 8.2 Linux / macOS (línea de comandos)

```sh
git clone --recursive -b claude/moonlight-ultra-low-latency-741cff https://github.com/harveytx/moonlight-android.git
cd moonlight-android
git submodule update --init --recursive     # si se clonó sin --recursive

# SDK (si no se usa Android Studio)
export ANDROID_HOME=$HOME/android-sdk
mkdir -p $ANDROID_HOME/cmdline-tools && cd $ANDROID_HOME/cmdline-tools
curl -O https://dl.google.com/android/repository/commandlinetools-linux-16111833_latest.zip
unzip commandlinetools-linux-*_latest.zip && mv cmdline-tools latest && cd -
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-37" \
    "build-tools;37.0.0" "ndk;29.0.14206865"
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew testNonRootDebugUnitTest      # 26 tests JVM
./gradlew assembleNonRootRelease        # APK: app/build/outputs/apk/nonRoot/release/
```

Variante para Android TV/sin root: `nonRoot`. (`root` es para Android < 8 con root y no hace falta).

## 8.3 Firma

Sin `keystore.properties`, el APK release se firma con la clave de depuración local. Cada máquina
tiene una clave distinta: para instalar una compilación hecha en otra máquina hay que desinstalar la
anterior (se pierden emparejamientos y ajustes). Para mantener una firma estable:

```sh
keytool -genkeypair -v -keystore release.jks -alias moonlight-ull -keyalg RSA -keysize 4096 -validity 10000
cat > keystore.properties <<EOF
storeFile=release.jks
storePassword=...
keyAlias=moonlight-ull
keyPassword=...
EOF
```

`keystore.properties` y `*.jks` están en `.gitignore`: **no los subas al repositorio**. Guarda una
copia de seguridad del `.jks`; sin él no se pueden publicar actualizaciones compatibles.

## 8.4 GitHub Actions

`.github/workflows/build-ull.yml` compila, ejecuta los tests y publica el APK como artefacto en cada
push. Para firmar con tu clave, añade los secretos `ULL_KEYSTORE_BASE64` (`base64 -w0 release.jks`),
`ULL_KEYSTORE_PASSWORD`, `ULL_KEY_ALIAS` y `ULL_KEY_PASSWORD`.

## 8.5 Actualizar desde upstream

```sh
git remote add upstream https://github.com/moonlight-stream/moonlight-android.git
git fetch upstream && git merge upstream/master        # resolver conflictos en los archivos de 04-modificaciones.md
cd app/src/main/jni/moonlight-core/moonlight-common-c && git fetch origin && git checkout origin/master
git submodule update --init --recursive && cd -
./gradlew testNonRootDebugUnitTest assembleNonRootRelease
```

Si upstream cambia la firma de `bridgeDrSubmitDecodeUnit` o `submitDecodeUnit`, mantener los dos
parámetros añadidos (`presentationTimeUs`, `callTimeUs`) en `callbacks.c`, `MoonBridge.java`,
`VideoDecoderRenderer.java` y `MediaCodecDecoderRenderer.java`.

## 8.6 Regenerar los clips del benchmark

```sh
tools/generate-bench-clips.sh     # requiere ffmpeg con libx264, libx265 y drawtext
```
