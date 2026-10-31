# 1. Investigación: repositorios y fuentes analizados

Criterio aplicado: no basta con que un proyecto "sea de baja latencia"; se revisó su código para ver
**qué hace exactamente**, si es aplicable a la arquitectura de Moonlight Android (MediaCodec →
Surface, protocolo de moonlight-common-c), si aporta algo **medible** y su licencia.

Leyenda de decisión: **Integrado** (código o cambio incorporado), **Adaptado** (idea reimplementada),
**Descartado** (con motivo), **Referencia** (usado para documentar/decidir).

## 1.1 Proyectos Moonlight y forks

| Proyecto | Código revisado | Hallazgos | Licencia | Decisión |
|---|---|---|---|---|
| [moonlight-stream/moonlight-android](https://github.com/moonlight-stream/moonlight-android) 12.2 (`b48494c`) | `MediaCodecDecoderRenderer.java`, `MediaCodecHelper.java`, `Game.java`, `callbacks.c` | Base del proyecto. Ya hace casi todo bien: salida directa a `SurfaceView`, *direct submit* en Codec2, parcheo del SPS (`num_ref_frames=1`, `max_dec_frame_buffering`), claves de baja latencia por fabricante, modo de presentación "menor latencia" por defecto, ALLM (`preferMinimalPostProcessing`) y `Surface.setFrameRate`. Puntos débiles en [02-auditoria.md](02-auditoria.md). | GPLv3 | Base |
| [moonlight-stream/moonlight-common-c](https://github.com/moonlight-stream/moonlight-common-c) | `VideoStream.c`, `RtpVideoQueue.c`, `VideoDepacketizer.c`, `PlatformSockets.c`, `Platform.c` | El submódulo estaba en `874ac95`. Upstream (`5d3c0ec`, 26-10-2026) añade: `62e0663` *Don't speculatively report losses if RFI is disabled* (evita hasta 120 fotogramas descartados), `be43885` IDR parcialmente perdido, `d85371c` RFI tras pérdida multibloque, `970b072` use-after-free, `41cedb8` desconexiones ENet. API pública compatible (solo se añade `MODIFIER_EXTENDED`). | GPLv3 + excepción sección 7 | **Integrado** (submódulo actualizado) |
| [moonlight-stream/moonlight-qt](https://github.com/moonlight-stream/moonlight-qt) | `streaming/video/*`, pacer, overlay | Sus renderers (D3D11VA, VAAPI, DRM, VideoToolbox) no aplican a Android. Su overlay separa *decode*, *frame queue delay* y *render*: se tomó ese desglose como modelo de métricas. | GPLv3 | Referencia |
| [moonlight-stream/moonlight-embedded](https://github.com/moonlight-stream/moonlight-embedded) | `src/video/aml.c` | Backend Amlogic con `libamcodec` (`codec_init`) y `/dev/video10`. En Android esas interfaces del kernel no son accesibles para apps (SELinux, permisos de sistema). | GPLv3 | Descartado (no aplicable en Android) |
| [ClassicOldSong/moonlight-android](https://github.com/ClassicOldSong/moonlight-android) (Artemis, rama `moonlight-noir`) | `MediaCodecHelper.java`, `MediaCodecDecoderRenderer.java` (diff contra upstream) | Añade claves de proveedor para Qualcomm (`vendor.qti-ext-output-fence…`), MediaTek (`vendor.mtk.vdec.*`) y NVIDIA, ninguna para Amlogic. Las envuelve en `safeSet()` con `try/catch`, que no protege nada: `MediaFormat.setInteger` no lanza con claves desconocidas. Ruta "latest-only" equivalente al modo "menor latencia" ya existente. Mide decodificación como *enqueue→dequeue* (idea correcta). PR #603: no usar `KEY_LOW_LATENCY` en `c2.amlogic.hevc`. | GPLv3 | Adaptado: medida enqueue→dequeue (reimplementada en ns con histogramas) y regla Amlogic HEVC. Claves MTK/QCOM descartadas (no aplican al ZTE, sin evidencia medida). |
| [MoonCastDev/moonlight-android](https://github.com/MoonCastDev/moonlight-android) issue #19 | Resultados publicados | Probaron las claves "ultra low latency" de Artemis: **sin mejora** en MediaTek (21→23 ms) ni en un stick Amlogic S905Y2, donde HEVC decodificaba en 22 ms frente a 29 ms de H.264. | GPLv3 | Referencia: justifica medir en cada dispositivo en vez de añadir claves a ciegas |
| [qiin2333/moonlight-vplus](https://github.com/qiin2333/moonlight-vplus) (reescritura en Kotlin) | `HevcLowLatencyPolicy.kt`, `PerformanceBoostManager.kt`, `FramePacingController.kt`, `FrameTimestampTracker.kt` | Commit `6158b74` (#575): sin opciones de baja latencia para HEVC en Amlogic. `PerformanceBoostManager`: sesiones ADPF (`PerformanceHintManager`). `FramePacingController`: modo PRECISE_SYNC con espera activa y "host cadence" (+1–3 ms según su propio comentario). | GPLv3 | Adaptado: política Amlogic HEVC. Descartados: espera activa (CPU A35, añade latencia), ADPF (ver 1.4). |
| moonlight-android issue [#1584](https://github.com/moonlight-stream/moonlight-android/issues/1584) | Logs del informe | Xiaomi TV Stick MiTV-AYFR0 (S905Y4, **Android TV 14**): `c2.amlogic.hevc.decoder` con `low-latency=1` presenta pocos FPS aunque decodifica 60. H.264 funciona normal. | — | Referencia directa para el ZTE (mismo SoC probable y misma versión de Android) |

## 1.2 Bibliotecas y frameworks multimedia

| Proyecto | Qué ofrece | Análisis para este caso | Decisión |
|---|---|---|---|
| [FFmpeg](https://ffmpeg.org) (libavcodec) | Decodificador H.264 software, `AV_CODEC_FLAG_LOW_DELAY`, `h264_mediacodec` | En un Cortex-A35 de 4 núcleos, H.264 1080p60 por software no es viable en tiempo real. `h264_mediacodec` es un envoltorio sobre el mismo MediaCodec con más capas. El efecto de `LOW_DELAY` (sin reordenación de fotogramas) ya lo consigue Moonlight parcheando el SPS. | Descartado |
| [GStreamer](https://gstreamer.freedesktop.org) (`androidmedia`) | Pipeline sobre MediaCodec | Añade colas y sincronización de reloj propias: más buffering, no menos. | Descartado |
| [ExoPlayer / Media3](https://github.com/androidx/media) | Reproductor | Diseñado para reproducción con buffer y sincronización A/V con el reloj de audio; su `MediaCodecVideoRenderer` libera fotogramas según ese reloj. Sus utilidades de capacidades duplican `MediaCodecHelper`. | Descartado |
| [SDL](https://www.libsdl.org) | Ventana + render GL | En Android crea su propia `SurfaceView` y render por GL: una pasada de GPU extra. | Descartado |
| [chiaki-ng](https://github.com/streetpea/chiaki-ng) (cliente Remote Play, AGPLv3; `android/app/src/main/cpp/video-decoder.c`) | MediaCodec NDK | Más simple que Moonlight: sin claves de baja latencia ni parcheo de SPS; libera cada fotograma inmediatamente. Nada que mejore lo existente. | Referencia |
| Clientes propietarios (Steam Link, Parsec, GeForce NOW) | — | Código cerrado: no analizable ni integrable. | Descartado |

## 1.3 Documentación de Android y de Sunshine

| Fuente | Uso |
|---|---|
| [MediaFormat](https://developer.android.com/reference/android/media/MediaFormat): `KEY_LOW_LATENCY` (API 30), `KEY_PRIORITY`, `KEY_OPERATING_RATE` | Modos de baja latencia implementados (estándar / proveedor / original / desactivado). |
| [CodecCapabilities.FEATURE_LowLatency](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#FEATURE_LowLatency), `PerformancePoint`, `getAchievableFrameRatesFor` | Informe de capacidades. |
| [MediaCodec.OnFrameRenderedListener](https://developer.android.com/reference/android/media/MediaCodec.OnFrameRenderedListener) | Tiempo de presentación reportado por el sistema (solo diagnóstico: la documentación indica que puede llegar retrasado o agrupado). |
| [MediaCodec.getSupportedVendorParameters](https://developer.android.com/reference/android/media/MediaCodec#getSupportedVendorParameters()) (API 31) | Lista real de parámetros de proveedor en el informe de capacidades. |
| [Display.isMinimalPostProcessingSupported](https://developer.android.com/reference/android/view/Display#isMinimalPostProcessingSupported()) | Saber si el TV acepta ALLM/Game Content Type (la latencia propia del TV puede superar a todo lo demás). |
| [Multimedia tunneling (AOSP)](https://source.android.com/docs/devices/tv/multimedia-tunneling) | Evaluado y descartado (ver 03). |
| [Process.setThreadPriority](https://developer.android.com/reference/android/os/Process#setThreadPriority(int,%20int)) | Prioridad de hilos nativos. |
| [Configuración de Sunshine](https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2configuration.html) | [07-sunshine.md](07-sunshine.md). |

## 1.4 Ideas evaluadas sin repositorio concreto

| Idea | Conclusión |
|---|---|
| ADPF (`PerformanceHintManager`, API 31) | Solo ayuda si el PowerHAL del fabricante la implementa; en TV boxes suele devolver `null`. No hay forma de validarlo aquí. Queda como trabajo futuro: el informe de capacidades muestra si el modo de rendimiento sostenido está soportado. |
| `recvmmsg()` para leer paquetes en lote | Reduce llamadas al sistema (~3600 paquetes/s a 40 Mbps), no latencia: el primer paquete se procesa igual de pronto. Ahorro de CPU estimado < 2 % de un núcleo. No justifica bifurcar moonlight-common-c. |
| Eliminar la copia nativo→Java del fotograma | Un `memcpy` de ~40 KB cuesta ~10–20 µs en un A35: < 0,1 % del intervalo de fotograma, con un cambio invasivo en la ruta de CSD/SPS. Descartado por beneficio no medible. |
| Marcas de tiempo del kernel (`SO_TIMESTAMPNS`) | Darían el instante exacto de llegada al socket. Requiere modificar `recvUdpSocket` en moonlight-common-c. En su lugar el retardo de cola se estima con el PTS del host (sin tocar el protocolo). |

## 1.5 Fuentes sobre el hardware del ZTE

Ver [06-zte-b866v2k.md](06-zte-b866v2k.md): página de producto de ZTE, androidtvbox.eu, cnx-software
(especificaciones del S905Y4), distribuidores, e issues de Moonlight con el mismo SoC.
