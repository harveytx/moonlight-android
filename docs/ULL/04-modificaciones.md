# 4. Lista de modificaciones

Todas las modificaciones del código original están marcadas en comentarios con `ULL:` (Java/C).

## 4.1 Archivos modificados

| Archivo | Cambio |
|---|---|
| `app/build.gradle` | `applicationId` propio (`com.limelight.ull`, `com.limelight.ull.root`), nombre "Moonlight ULL", versión `12.2-ULL.1` (31501), firma release opcional con `keystore.properties` (si no existe, firma de depuración local), clips del benchmark sin comprimir, JUnit para tests. |
| `.gitignore` | Excluye `keystore.properties`, `*.jks`, `*.keystore`. |
| `app/src/main/jni/moonlight-core/moonlight-common-c` | Submódulo actualizado de `874ac95` a `5d3c0ec` (upstream, sin cambios propios). |
| `app/src/main/jni/moonlight-core/callbacks.c` | `bridgeDrSubmitDecodeUnit` pasa además el PTS del host (`presentationTimeUs`) y el reloj nativo en el momento de la llamada (`LiGetMicroseconds()`). Firma JNI `([BIIIICJJJJ)I`. |
| `nvstream/jni/MoonBridge.java` | Nueva firma de `bridgeDrSubmitDecodeUnit`. |
| `nvstream/av/video/VideoDecoderRenderer.java` | Nueva firma de `submitDecodeUnit` (documentada). |
| `binding/video/MediaCodecHelper.java` | Constantes y dispatcher de modos de baja latencia; `applyAllLowLatencyOptions`; `describeLowLatencyKeys`; `getHardwareDecodersForMime`, `findDecoderByName`, `getSupportedVendorParameters`, `isAmlogicDecoder`, `decoderAdvertisesLowLatency`. La heurística original no se toca. |
| `binding/video/MediaCodecDecoderRenderer.java` | Selección manual de decodificador H.264; resolución del modo LL (`resolveLowLatencyMode`); registro de claves aplicadas; listener de presentación (diagnóstico); instrumentación por etapa (entrada, salida, liberación, pérdidas, recuperación del códec); medición de espera de buffers de entrada; `LatencyGuard` y `DR_NEED_IDR`; prioridad del hilo de render; overlay detallado; accesores para informes y benchmark. Las líneas del overlay original se mantienen idénticas para comparar con la app oficial. |
| `preferences/PreferenceConfiguration.java` | Nuevas preferencias ULL y su lectura; aplica el perfil ULL en la primera ejecución. |
| `preferences/StreamSettings.java` | Perfil, detección de "Personalizado", restaurar valores, acceso a Diagnóstico, lista real de decodificadores H.264 hardware, aviso del modo "proveedor", bitrate escalado por perfil al cambiar resolución/FPS. |
| `Game.java` | Sube prioridad de hilos nativos en `connectionStarted()`; guarda el informe de sesión tras `conn.stop()`. |
| `AndroidManifest.xml` | Registra `DiagnosticsActivity` y `DecoderBenchmarkActivity`. |
| `res/xml/preferences.xml` | Categoría "Ultra Low Latency" con perfil, diagnóstico, ajustes avanzados y restaurar. |

## 4.2 Archivos nuevos

| Archivo | Descripción |
|---|---|
| `binding/video/LatencyHistogram.java` | Histograma de latencias (20 µs hasta 100 ms, 1 ms hasta 2 s), sin asignaciones. |
| `binding/video/JitterEstimator.java` | Jitter RFC 3550 y retardo de cola con el PTS del host (mínimo deslizante de 10 s). |
| `binding/video/LatencyGuard.java` | Protección contra acumulación de latencia. |
| `binding/video/PipelineStats.java` | Registro por fotograma, ventanas de 1 s, totales de sesión, CSV por fotograma. |
| `binding/video/CpuUsageSampler.java` | CPU del proceso y del hilo `VideoRecv` desde `/proc`. |
| `binding/video/DecoderTuningStore.java` | Persistencia del modo recomendado por el benchmark (ligado a la huella del firmware). |
| `utils/StreamThreadPriority.java` | Prioridad de hilos nativos de vídeo y del hilo actual. |
| `preferences/LatencyProfile.java` | Perfiles, aplicar, comprobar coincidencia, restaurar. |
| `diagnostics/DiagnosticsActivity.java` | Pantalla de diagnóstico (D-pad). |
| `diagnostics/DeviceCapabilityReport.java` | Informe de capacidades del dispositivo. |
| `diagnostics/DecoderBenchmark.java`, `DecoderBenchmarkActivity.java` | Benchmark del decodificador en el equipo. |
| `diagnostics/AnnexBClip.java` | Divide un flujo Annex B en unidades como las entrega moonlight-common-c. |
| `diagnostics/SessionReport.java` | Informes JSON/CSV, listado, texto legible y comparación A/B. |
| `res/layout/activity_ull_diagnostics.xml`, `activity_ull_decoder_benchmark.xml` | Interfaces. |
| `res/values/strings_ull.xml`, `arrays_ull.xml` (+ `values-es`) | Textos en inglés y español. |
| `assets/bench/h264_1080p60.h264`, `hevc_1080p60.h265` | Clips sintéticos de 120 fotogramas con contador visible (generados con `tools/generate-bench-clips.sh`). |
| `app/src/test/...` (6 clases, 26 tests) | Tests JVM de histograma, jitter, protección, estadísticas, parser y recomendación. |
| `tools/*` | Reloj de latencia, script adb, comparador de sesiones, generador de clips. |
| `.github/workflows/build-ull.yml` | Compilación y tests en GitHub Actions, firma opcional con secretos. |
| `docs/ULL/*` | Esta documentación. |
| `dist/MoonlightULL-12.2-ULL.1.apk` | APK compilado (release, R8). |

## 4.3 Compatibilidad

* Protocolo y negociación con Sunshine sin cambios.
* `minSdk 21` se mantiene; todas las API nuevas están protegidas por versión (p. ej.
  `OnFrameRenderedListener` API 23, `getSupportedVendorParameters` API 31,
  `isMinimalPostProcessingSupported` API 30).
* Los perfiles solo escriben preferencias: el comportamiento "Moonlight original" es seleccionable.
