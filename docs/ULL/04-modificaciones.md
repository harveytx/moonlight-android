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
| `binding/video/BitrateBudget.java` | Contabilidad de bitrate de Sunshine (FEC, audio, techo por fotograma) y tiempo de recepción estimado con la medida del ZTE (ULL.6). |
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
| `app/src/test/...` (8 clases, 43 tests) | Tests JVM de histograma, jitter, protección, estadísticas, parser, recomendación, informes y presupuesto de bitrate. |
| `tools/*` | Reloj de latencia, patrón de imagen, script adb, comparador de sesiones, generador de clips. |
| `.github/workflows/build-ull.yml` | Compilación y tests en GitHub Actions, firma opcional con secretos. |
| `docs/ULL/*` | Esta documentación. |
| `dist/MoonlightULL-12.2-ULL.6.apk` | APK compilado (release, R8). |

## 4.3 Versión 12.2-ULL.2 (a partir de las medidas del ZTE)

| Archivo | Cambio |
|---|---|
| `binding/video/JitterEstimator.java` | Ignora los fotogramas duplicados de Sunshine (latencia de host 0) y descuenta la latencia de host del tránsito. |
| `binding/video/PipelineStats.java` | Ventanas con doble búfer: el hilo de vídeo solo intercambia histogramas; percentiles y totales se calculan fuera del bloqueo. |
| `binding/video/MediaCodecDecoderRenderer.java` | Hilo "Video - Stats" para resúmenes, CPU y overlay (antes en el hilo de recepción); modo de benchmark sin presentar; línea "TV sin ALLM"; la regla HEVC Amlogic se puede anular con un benchmark confirmado visualmente. |
| `binding/video/MediaCodecHelper.java` | Modo "vendor" en Amlogic: solo claves anunciadas por el códec (`vendor.START.low-latency.enable` en este firmware); caché de parámetros de proveedor. |
| `binding/video/DecoderTuningStore.java` | Confirmación visual del benchmark ("No" descarta el resultado). |
| `diagnostics/DecoderBenchmark*.java`, `activity_ull_decoder_benchmark.xml` | Pregunta visual al terminar; prueba de rendimiento sin presentar; aviso sobre `dispMs`. |
| `utils/StreamWarnings.java` (nuevo), `Game.java` | Avisos al iniciar: bitrate cerca del límite del Ethernet; TV sin ALLM (una vez). |
| `app/build.gradle` | Versión `12.2-ULL.2` (31502). |
| Tests | 30 (nuevos: fotogramas duplicados, latencia de host, ventanas repetidas, reinicio). |

## 4.4 Versión 12.2-ULL.3

| Archivo | Cambio |
|---|---|
| `diagnostics/DecoderBenchmark.java` | Ejecución de calentamiento descartada; 3 rondas con orden alterno; una configuración distinta de la original solo se recomienda si es más rápida en todas las rondas; p95 por ronda en el informe. |
| `binding/video/PipelineStats.java`, `MediaCodecDecoderRenderer.java`, `diagnostics/SessionReport.java`, `tools/compare-sessions.py` | Nueva etapa `jni` (reensamblado → entrada en Java), columna `jni_ms` en el CSV, línea en el overlay. |
| `utils/StreamWarnings.java`, `diagnostics/DeviceCapabilityReport.java`, strings | Aviso de ALLM válido para TV y monitores; nombre EDID de la pantalla. |
| `app/build.gradle` | Versión `12.2-ULL.3` (31503). |
| Tests | 32. |

## 4.5 Versión 12.2-ULL.4

| Archivo | Cambio |
|---|---|
| `diagnostics/DecoderBenchmark.java` | Calentamiento y medida en un flujo continuo (sin la pausa visible en el fotograma 59); porcentaje decodificado limitado al 100 %. |
| strings (`ull_bench_visual_question`, `ull_bench_running`) | Qué es normal en la comprobación visual. |
| `diagnostics/DeviceCapabilityReport.java` | Nombre del tipo de conexión de la pantalla. |
| `app/build.gradle` | Versión `12.2-ULL.4` (31504). |

## 4.6 Versión 12.2-ULL.5

| Archivo | Cambio |
|---|---|
| `diagnostics/SessionReport.java` | Informes en `ull-sessions/<versión>/`; migración única de los archivos de versiones anteriores (sesiones según su `app_version`, el resto a `unversioned/`); la lista de sesiones recorre todas las versiones y muestra la versión. |
| `diagnostics/DeviceCapabilityReport.java`, `DecoderBenchmark.java` | Versión de la app en la cabecera de los informes. |
| `diagnostics/DecoderBenchmark.java`, `binding/video/PipelineStats.java` | Comprobación de presentación por mediana de rondas (proporción presentados/liberados ≥ 50 %), espera a las notificaciones tardías, columna `disp%`. |
| `app/build.gradle` | Versión `12.2-ULL.5` (31505). |
| Tests | 36. |

## 4.7 Versión 12.2-ULL.6

| Archivo | Cambio |
|---|---|
| `binding/video/BitrateBudget.java` (nuevo) | Bitrate del codificador y techo por fotograma según el código de Sunshine; tiempo de recepción estimado (0,086 ms/kB medido a 100 Mbps). |
| `binding/video/PipelineStats.java` | Histograma de tamaño de fotograma (mismo mecanismo de doble búfer que las etapas) y contador de fotogramas en el techo (≥ 85 %). |
| `binding/video/MediaCodecDecoderRenderer.java` | Techo estimado al configurar; línea "Fotograma" del overlay detallado; modo de pantalla observado durante el stream; `frame_ceiling_kb_estimate`, `color_range`, `display_mode` y `client_scaling` en los diagnósticos. |
| `diagnostics/SessionReport.java` | Sección `frame_size_kb` en el informe; tamaño p95 y "en el techo" en el texto y el comparador A/B (sin veredicto mejor/peor). |
| `utils/StreamWarnings.java`, strings | Aviso de bitrate con la contabilidad de Sunshine (sin sumar el FEC otra vez) y tiempo por fotograma en el techo, en 2 líneas. |
| `tools/image-test.html` (nuevo), `tools/compare-sessions.py` | Patrón de imagen; filas de tamaño de fotograma. |
| `app/build.gradle` | Versión `12.2-ULL.6` (31506). |
| Tests | 43 (nuevos: `BitrateBudgetTest`, tamaños de fotograma en `PipelineStatsTest`). |

## 4.8 Compatibilidad

* Protocolo y negociación con Sunshine sin cambios.
* `minSdk 21` se mantiene; todas las API nuevas están protegidas por versión (p. ej.
  `OnFrameRenderedListener` API 23, `getSupportedVendorParameters` API 31,
  `isMinimalPostProcessingSupported` API 30).
* Los perfiles solo escriben preferencias: el comportamiento "Moonlight original" es seleccionable.
