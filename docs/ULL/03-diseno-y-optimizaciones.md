# 3. Diseño y optimizaciones

## 3.1 Principios

1. **Optimizar el pipeline existente**, no sustituirlo. MediaCodec → `SurfaceView` es ya el camino de
   menor latencia en Android (sin GPU, sin copias, sin conversión de color).
2. **Medir en el dispositivo** antes de aplicar una configuración específica de fabricante.
3. **Nunca peor que el original**: si una configuración no mejora de forma medible, se usa la de
   Moonlight; el perfil "Moonlight original" reproduce exactamente su comportamiento.
4. **Protocolo intacto**: compatibilidad total con Sunshine; moonlight-common-c sin modificaciones
   (solo actualizado a upstream).

## 3.2 Arquitectura (componentes nuevos en negrita)

```
Game ──► MediaCodecDecoderRenderer (existente, instrumentado)
           ├── MediaCodecHelper (existente) + modos LL: original / estándar / proveedor / off / solo-prioridad
           ├── DecoderTuningStore            ← resultado del benchmark por decodificador+firmware
           ├── PipelineStats                 ← marcas de tiempo por fotograma, histogramas, contadores
           │     ├── LatencyHistogram        ← percentiles sin asignar memoria (20 µs de resolución)
           │     └── JitterEstimator         ← jitter RFC 3550 y retardo de cola con el PTS del host
           ├── LatencyGuard                  ← protección contra acumulación de latencia
           └── CpuUsageSampler               ← CPU del proceso y del hilo VideoRecv (/proc)
StreamThreadPriority                         ← prioridad de VideoRecv/VideoDec/render
LatencyProfile                               ← perfiles ULL / Equilibrado / Calidad / Original
diagnostics/
  DiagnosticsActivity                        ← menú navegable con mando
  DeviceCapabilityReport                     ← informe de capacidades reales
  DecoderBenchmark(+Activity) + AnnexBClip   ← benchmark del decodificador en el propio equipo
  SessionReport                              ← informes JSON/CSV por sesión y comparación A/B
```

Ningún componente nuevo está en el camino crítico salvo `PipelineStats` (unas pocas operaciones
`synchronized` por fotograma) y `LatencyGuard` (aritmética simple). El listener de presentación del
sistema solo se activa si se usa el overlay detallado o el registro de sesiones.

## 3.3 Optimizaciones implementadas

### A. Modo de baja latencia del decodificador elegido por medición

**Problema.** Moonlight decide las claves de MediaCodec por prefijo de nombre de fabricante. Para un
decodificador Amlogic que anuncia `FEATURE_LowLatency`, el intento 0 solo pone `low-latency=1` y
termina (`MediaCodecHelper.java:509` original). Nadie ha medido si en el S905Y4/Android 14 conviene
añadir `vendor.low-latency.enable`, prioridad de tiempo real, o ninguna clave. Los forks que añadieron
más claves no midieron mejora (MoonCastDev #19).

**Solución.**
* `MediaCodecHelper.setDecoderLowLatencyOptions(format, info, try, mode)` con modos:
  `original` (heurística de Moonlight, intacta), `standard` (`KEY_LOW_LATENCY` + `KEY_PRIORITY=0`),
  `vendor` (todas las claves aplicables a la vez, con caída a la secuencia original si `configure()`
  falla), `off`, y el interno `priority-only`.
* El **benchmark del decodificador** reproduce un flujo sintético idéntico en estructura al de
  Sunshine (H.264 High, sin B-frames, 1 referencia, 1 slice, VBV de 1 fotograma) **a través del mismo
  `MediaCodecDecoderRenderer` del streaming** y sobre una `SurfaceView` real. Mide cada modo
  (2 rondas × 300 fotogramas tras 60 de calentamiento), valida que todos los fotogramas salgan, que
  no haya errores y que se presenten (si el sistema lo reporta), y mide además el rendimiento máximo
  (¿es viable 1080p120?).
* Regla de recomendación: el modo válido con menor p95 de decodificación, **pero solo si mejora al
  original en ≥ 0,5 ms y ≥ 5 %**; si no, se mantiene el original. Se guarda ligado a la huella del
  firmware (`Build.FINGERPRINT`): una actualización invalida el resultado.
* "Auto" usa: regla conocida Amlogic HEVC → resultado del benchmark → heurística original.

**Impacto.** Desconocido hasta ejecutar el benchmark en el ZTE; por construcción nunca peor que el
original. Verificación: tabla del benchmark + sesiones A/B.

### B. Corrección Amlogic HEVC (efecto diapositiva)

`c2.amlogic.hevc.decoder` acepta `low-latency=1` pero presenta solo unos pocos FPS (issue #1584,
S905Y4/Android TV 14; PR #603 de Artemis; commit `6158b74` de moonlight-vplus). `configure()` no falla,
así que el mecanismo de reintento no lo detecta. En modo Auto se usa `priority-only` para HEVC en
Amlogic. No afecta a H.264.

### C. Protección contra acumulación de latencia (`LatencyGuard`)

**Problema.** Sin búfer de jitter, un decodificador más lento que el flujo no descarta nada: con
*direct submit* el hilo de red queda bloqueado en `dequeueInputBuffer()` y los paquetes esperan en el
socket (hasta ~1 s de vídeo). La latencia crece y no vuelve a bajar hasta que algo vacía la cola.

**Solución.** Por cada fotograma se calcula:
* *backlog* = retardo de cola respecto al mínimo de los últimos ~10 s, usando el PTS del host que
  Sunshine ya envía (`JitterEstimator`), + tiempo desde el reensamblado hasta `queueInputBuffer`.
* *presión del decodificador* = media móvil del tiempo bloqueado esperando buffer de entrada, y
  fotogramas dentro del decodificador.

Solo si el *backlog* supera el umbral (agresivo: max(2,5 fotogramas, 30 ms) durante 0,4 s; normal:
max(5 fotogramas, 60 ms) durante 1 s) **y** el decodificador es el cuello de botella, se devuelve
`DR_NEED_IDR`. moonlight-common-c entonces vacía su cola, descarta hasta el siguiente IDR y lo pide
al host (`requestDecoderRefresh`): el hilo de red vacía el socket sin decodificar y el vídeo vuelve
a estar "en vivo". Límites: 3 s entre resincronizaciones y, si hay 3 en 60 s, se desactiva y avisa
("el decodificador no sostiene esta transmisión") en lugar de provocar una tormenta de IDR.
El retardo que es solo de red se muestra como aviso pero **nunca** provoca un IDR (empeoraría la
congestión).

**Impacto.** Ninguno si el decodificador va sobrado (no se activa: probado en tests). Si no da abasto,
cambia "latencia creciente sin límite" por "latencia acotada + breve congelación al resincronizar".

### D. Prioridad de los hilos de vídeo (`StreamThreadPriority`)

Los hilos nativos `VideoRecv` y `VideoDec` de moonlight-common-c se crean con `pthread_create` y
heredan nice 0. Tras `connectionStarted()` se localizan por nombre en `/proc/self/task/*/comm` y se
suben con `Process.setThreadPriority(tid, …)`; el hilo de render se sube a sí mismo. Niveles:
Normal (original), Alta (`URGENT_DISPLAY`, -8, predeterminado) y Máxima (`AUDIO`, -16). Sin tiempo
real (no disponible para apps). Se ve en el informe de sesión (`native_thread_priority`).

**Impacto esperado.** Reducción de las colas (p95/p99) de "recepción" y "hasta el decodificador"
cuando hay carga de fondo; sin cambio en reposo. Verificable comparando sesiones con prioridad
Normal y Alta.

### E. moonlight-common-c actualizado a upstream

Incluye `62e0663`: sin RFI (caso del H.264 en Amlogic), una predicción de pérdida podía dejar al
depacketizer esperando un IDR que nunca se pedía, descartando **hasta 120 fotogramas** (~2 s a
60 FPS). También `be43885` (IDR parcialmente perdido), `d85371c`, `970b072`, `41cedb8`.

### F. Instrumentación y diagnóstico

* Todas las marcas en el reloj de `System.nanoTime()`. Las marcas nativas (época indefinida) se
  traducen con el reloj nativo muestreado justo antes de cada llamada JNI (`callTimeUs`), así que
  no hay suposiciones sobre relojes.
* Etapas: recepción (1er→último paquete), reensamblado→cola del decodificador, espera de buffer de
  entrada, decodificación, espera hasta liberar, liberación→presentación (sistema), total cliente,
  retardo de cola (estimado), jitter RFC 3550, procesamiento del host (reportado por Sunshine).
* Overlay detallado (media / p95 del último segundo), CPU del proceso y de `VideoRecv`.
* Informe JSON por sesión + CSV por fotograma opcional + comparación A/B en la app y con
  `tools/compare-sessions.py` (incluye test de Mann-Whitney).

### F2. Cambios de la versión ULL.2 (medidas del ZTE)

* El hilo de recepción ya no calcula estadísticas: cada segundo solo intercambia los histogramas
  activos por un juego de repuesto (O(1)) y delega percentiles, CPU y overlay en el hilo
  "Video - Stats". Esto incluye el formateo del overlay original, que Moonlight hacía en el hilo de
  recepción.
* Jitter y retardo de cola sin fotogramas duplicados de Sunshine y descontando la latencia del host.
* Ver [09-resultados-zte.md](09-resultados-zte.md#94-cambios-en-ull2-derivados-de-estos-datos).

### G. Perfiles y ajustes avanzados

| | Ultra Low Latency (predet.) | Equilibrado | Máxima calidad | Moonlight original |
|---|---|---|---|---|
| Sincronización de presentación | menor latencia | equilibrado | equilibrado | menor latencia |
| Códec | H.264 | auto | auto | auto |
| Modo LL del decodificador | auto | auto | auto | original |
| Protección de latencia | agresiva | normal | normal | desactivada |
| Prioridad | alta | alta | normal | normal |
| Bitrate | 1,0× predet. de Moonlight | 1,5× | 2,5× | 1,0× |

Resolución y FPS no forman parte del perfil (dependen del TV y del decodificador). Cambiar a mano un
ajuste gestionado pone el perfil en "Personalizado". "Restaurar valores predeterminados" vuelve al
perfil ULL y borra los ajustes avanzados.

Opciones peligrosas: el selector de decodificador solo ofrece decodificadores **hardware** aceptados
por Moonlight; si el elegido desaparece se vuelve a automático. El modo "todas las claves de
proveedor" muestra un aviso y cae automáticamente a configuraciones seguras si `configure()` falla.

## 3.4 Opciones evaluadas y descartadas

| Opción | Motivo |
|---|---|
| Renderizar con OpenGL ES / Vulkan / TextureView | El decodificador ya escribe en la superficie que compone el HWC (overlay de vídeo). Cualquier render por GPU añade una pasada (y en una Mali-G31 MP2, carga) y típicamente un fotograma más de cola. No se asume que Vulkan sea más rápido: aquí no hay trabajo de GPU que acelerar. |
| Túnel multimedia (`FEATURE_TunneledPlayback`) | Diseñado para reproducción sincronizada con audio: por defecto no muestra vídeo hasta que el audio arranca y sincroniza contra el reloj de audio del HAL. Para streaming interactivo añade riesgo sin beneficio demostrable; no validable sin el dispositivo. |
| FFmpeg software / GStreamer / ExoPlayer / SDL | Ver [01-investigacion.md](01-investigacion.md#12-bibliotecas-y-frameworks-multimedia). |
| Claves MediaTek/Qualcomm/NVIDIA de Artemis | No aplican al Amlogic del ZTE y no hay medidas que demuestren mejora. |
| Espera activa para alinear con vsync (vplus PRECISE_SYNC) | Consume CPU en un A35 y, según su autor, añade 1–3 ms. |
| Búfer de jitter adaptativo | Añadir búfer aumenta la latencia; Moonlight ya entrega cada fotograma en cuanto se reconstruye y el modo "menor latencia" sustituye fotogramas atrasados por el más reciente. Lo que sí faltaba es evitar la acumulación (opción C). |
| Copia cero nativo→MediaCodec | ~10–20 µs por fotograma; no medible frente a 16,7 ms. |
| `recvmmsg` / marcas de tiempo del kernel | Requieren bifurcar moonlight-common-c; beneficio en CPU o precisión de medida, no en latencia. |
| ADPF / modo de rendimiento sostenido | Soporte dudoso en TV boxes; no validable aquí. El informe de capacidades indica si existe. |
