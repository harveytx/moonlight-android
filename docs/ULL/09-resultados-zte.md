# 9. Resultados medidos en el ZTE B866V2K (30-10-2026) y cambios derivados

Datos enviados por el usuario desde el propio equipo con Moonlight ULL 12.2-ULL.1:
2 informes de capacidades, 1 benchmark H.264, 1 benchmark HEVC y 3 sesiones de streaming.
Todo lo de este documento son **medidas** salvo lo marcado como *interpretación*.

## 9.1 Hardware confirmado

| Dato | Valor medido | Antes (fuentes públicas) |
|---|---|---|
| SoC | **Amlogic AMLS905Y4** (`Build.SOC_MODEL`) | S905Y4, no confirmado → **confirmado** |
| CPU | 4 núcleos a 2004 MHz, gobernador `schedutil` | — |
| Userspace | **solo 32 bits** (`armeabi-v7a`) | desconocido |
| RAM | 1920 MB (644–810 MB libres) | 2 GB |
| Sistema | Android 14 (API 34), build `UKG3.250115.001`, firmware `V86611396.0016` (19-08-2025) | Android TV 10/11 de fábrica |
| Decodificador H.264 | `c2.amlogic.avc.decoder` (Codec2), High/ConstrainedHigh@L5.1, hasta 4096×4096, `FEATURE_LowLatency`, punto de rendimiento 1080p60 y 4K30, el fabricante declara 134 FPS alcanzables a 1080p | 4K30 según spec del SoC → **confirmado** |
| Decodificador HEVC | `c2.amlogic.hevc.decoder`, `FEATURE_LowLatency`, 246 FPS declarados a 1080p, 4K60 | — |
| Parámetros de proveedor | 32, entre ellos `vendor.START.low-latency.enable`, `vendor.vdec.workmode.value`, `vendor.streammode.pipelinedelay.value`, `vendor.unstable-pts.enable`. **No** existe `vendor.low-latency.enable` (la clave que Moonlight usa para Amlogic) | — |
| Pantalla | 1080p60 activo; modos hasta 4K60, **ningún modo de 120 Hz**; HDR10 | — |
| ALLM / Game Content Type | **No soportado** por la pantalla conectada (un monitor de PC: no necesita Modo Juego) | — |
| Red | Ethernet, estimación 100 Mbps (velocidad sysfs no legible) | 10/100 → **confirmado** |
| Rendimiento sostenido / estado térmico | No soportado / normal | — |

## 9.2 Benchmark del decodificador (sin red, 1080p60, 2 rondas × 300 fotogramas)

H.264 (`c2.amlogic.avc.decoder`), decodificación = `queueInputBuffer` → `dequeueOutputBuffer`:

| Modo | Claves aplicadas | Media | p50 | p95 | máx | FPS presentados | Estado |
|---|---|---|---|---|---|---|---|
| original (Moonlight) | `low-latency=1` | 3,25 | 3,26 | 4,14 | 8,27 | 57,3 | OK |
| **standard** | `low-latency=1, priority=0` | **2,38** | **2,30** | **2,58** | 9,45 | 57,1 | OK → **recomendado** |
| vendor | + `vdec-lowlatency=1`, `vendor.low-latency.enable=1` | 2,92 | 2,89 | 3,48 | 8,37 | 57,3 | OK |
| off | ninguna | 10,08 | 9,90 | 10,79 | 17,90 | 46,8 | FALLO (pocos FPS presentados) |

* ~~standard frente a original: −0,87 ms de media y −1,56 ms en p95~~ — **corregido en 9.6: no se
  reprodujo.** En las dos ejecuciones siguientes original y standard dieron lo mismo (~2,3 ms). El
  "original" de esta primera ejecución (3,25 ms) fue un artefacto de arranque en frío: siempre se
  medía el primero. "Auto" lo aplicó en la sesión de las 22:30, pero las ejecuciones siguientes ya
  volvieron a recomendar "original".
* Sin claves de baja latencia el decodificador tarda ~10 ms y no sostiene 60 FPS: la clave
  estándar `low-latency` es imprescindible en este firmware.
* El modo "vendor" de ULL.1 ponía `vendor.low-latency.enable`, que este firmware no anuncia (se
  ignora). En ULL.2 aplica solo claves anunciadas, aquí `vendor.START.low-latency.enable`: **hay que
  volver a medir** si aporta algo.
* Solo se hizo **una** ejecución del benchmark. Hay que repetirlo 2–3 veces para confirmar que la
  recomendación es estable.

HEVC (`c2.amlogic.hevc.decoder`):

| Modo | Media | p50 | p95 | FPS presentados | Estado |
|---|---|---|---|---|---|
| original (`low-latency=1`) | **2,25** | 2,22 | **2,51** | 57,3 | OK |
| standard | 2,91 | 2,95 | 3,82 | 57,2 | OK |
| vendor | 2,35 | 2,31 | 2,79 | 54,1 | OK |
| off | 5,81 | 5,67 | 7,43 | 55,5 | OK |

* Con `low-latency=1` el HEVC presentó 57 FPS según Android: en este firmware **no se observó** el
  "efecto diapositiva" de los informes #1584/#1610. Pero Android podría reportar fotogramas que el
  plano de vídeo no muestra, así que ULL.2 pide confirmación visual tras el benchmark; solo si
  respondes "Sí, fluido" el modo Auto deja de usar la regla de seguridad (que en la sesión HEVC
  dejó la decodificación en ~6 ms en lugar de ~2,3 ms).

Rendimiento máximo: 120 FPS (H.264) y 125 FPS (HEVC). *Interpretación*: coincide con 2 × 60 Hz, es
decir, estaba limitado por la presentación en pantalla, no por el decodificador (el fabricante declara
134 y 246 FPS). **Corregido en ULL.2**: la prueba de rendimiento ya no presenta los fotogramas.

`dispMs` (liberación → pantalla) varía entre configuraciones (19–26 ms) por la fase entre el
temporizador del benchmark y el vsync, no por el decodificador; no se usa para decidir.

## 9.3 Sesiones de streaming

| Sesión | Perfil | Res. | Códec | Modo LL | Presentación | Decodif. p50 / p95 / p99 (ms) | Decodificado → liberado p50 | Liberado → pantalla media | 1er paquete → pantalla p50 |
|---|---|---|---|---|---|---|---|---|---|
| 22:27:34 | ULL | 720p | H.264 | original (antes del benchmark) | menor latencia | 2,88 / 4,76 / 59,2 | 1,82 | 20,4 | 25,0 |
| 22:30:54 | custom (80 Mbps) | 1080p | H.264 | **standard** (benchmark) | menor latencia | **2,30 / 3,36 / 7,26** | 1,14 | 20,7 | 26,2 |
| 22:31:16 | Máxima calidad | 1080p | HEVC | priority-only (regla Amlogic) | equilibrado | 5,64 / 8,14 / 11,7 | **15,34** | 21,2 | 41,8 |

Lo que muestran (con sus límites):

1. **El contenido era un escritorio casi estático**: 43–51 FPS recibidos y 2,6–3,6 Mbps reales
   (aunque se configuraron 10–80 Mbps). Sunshine solo envía fotogramas nuevos o duplicados cuando
   hay cambios. Por eso estas sesiones **no sirven** para evaluar bitrate, recepción ni red; hay que
   repetirlas con imagen en movimiento.
2. **Presentación "equilibrada" frente a "menor latencia":** retiene el fotograma ~14 ms más antes
   de liberarlo (15,3 frente a 1,1–1,8 ms de mediana). Pero, como se libera justo en el vsync, luego
   tarda menos en verse (~13 ms frente a ~20,7). **Corregido en 9.6:** la diferencia neta de
   decodificado a pantalla es de ~6–8 ms a favor de "menor latencia", no de 14 ms.
3. **Liberado → pantalla ≈ 20,5 ms en todas las sesiones**: es el mayor componente del cliente.
   *Interpretación*: cuadra con el plazo de presentación que reporta el sistema (13,67 ms) más medio
   refresco de media (8,3 ms) a 60 Hz, el camino SurfaceFlinger/HWC. La app no puede acortarlo con
   esta superficie, y el TV no ofrece modos de más de 60 Hz.
4. Decodificación en streaming 1080p con "standard": p50 2,3 ms, igual que en el benchmark.
5. Colas largas (p99): "hasta el decodificador" 19–42 ms y decodificación 59 ms en la sesión de 720p.
   No se pueden explicar sin el CSV por fotograma. *Hipótesis*: el trabajo de estadísticas que se
   hacía una vez por segundo en el hilo de recepción, y/o el decodificador reteniendo un fotograma
   durante pausas del escritorio estático.
6. "Retardo de cola" p95 de ~30 ms: era un **error de medida**. Sunshine marca los fotogramas
   duplicados con la hora de su control de ritmo y no con la de captura (`src/stream.cpp`,
   `frame_is_dupe`), y mezclar ambas bases de tiempo simulaba colas inexistentes. Corregido en ULL.2.
   La protección de latencia no se activó en ninguna sesión (0 resincronizaciones): su doble condición
   evitó IDR innecesarios pese a esa medida errónea.

## 9.4 Cambios en ULL.2 derivados de estos datos

| Cambio | Motivo (dato) |
|---|---|
| El jitter y el retardo de cola ignoran los fotogramas duplicados de Sunshine (latencia de host 0) y descuentan la latencia de host | Retardo de cola falso de ~30 ms en escritorio (9.3-6) |
| Percentiles, CPU y overlay se calculan en un hilo de estadísticas; el hilo de recepción solo intercambia buffers (O(1)) | Colas p99 de 19–42 ms en "hasta el decodificador" (9.3-5) |
| Prueba de rendimiento máximo sin presentar los fotogramas | Resultado limitado a 2× el refresco (9.2) |
| El modo "vendor" en Amlogic aplica solo las claves que el códec anuncia (`vendor.START.low-latency.enable` en este firmware) | `vendor.low-latency.enable` no existe aquí (9.1) |
| Confirmación visual tras el benchmark; un "Sí" permite que el resultado medido sustituya la regla de seguridad de HEVC en Amlogic, un "No" descarta el resultado | HEVC con `low-latency=1` presentó 57 FPS (9.2) |
| Aviso (una vez) de que el TV no admite ALLM y línea en el overlay detallado | `minimal post-processing supported: false` (9.1) |
| Aviso al iniciar si el bitrate con FEC supera el 60 % del enlace Ethernet | Sesión configurada a 80 Mbps sobre 100 Mbps (9.3) |

## 9.5 Próximas pruebas en el ZTE

1. Instalar **ULL.2** (`dist/`). Se actualiza encima de ULL.1 porque usa la misma firma de depuración.
2. **Benchmark H.264 ×3** y contestar a la pregunta visual. Comprobar si "vendor"
   (`vendor.START.low-latency.enable`) mejora a "standard".
3. **Benchmark HEVC ×1** y contestar a la pregunta visual (solo "Sí" si el contador fue fluido en
   todas las pasadas).
4. ~~Activar el Modo Juego del TV~~: la pantalla es un monitor de PC, no aplica (ver 9.6).
5. Sesiones con **imagen en movimiento**: `tools/latency-clock.html` a pantalla completa en el PC, o
   un juego. 1080p60, H.264, **20 Mbps**, con *Registro CSV por fotograma* activado. Perfil ULL y
   perfil "Moonlight original", 3 sesiones de ≥ 2 min de cada uno, alternando. Enviar los `.json` y
   los `-frames.csv`.
6. Opcional: prueba con cámara ([05-benchmark.md](05-benchmark.md#55-prueba-3--extremo-a-extremo-con-cámara))
   frente al Moonlight oficial.

## 9.6 Segunda ronda (ULL.2, misma noche): correcciones y nuevas medidas

Datos: 3 benchmarks más (2 de H.264 y 1 de HEVC) y 4 sesiones **con imagen en movimiento**
(~59,7 FPS recibidos). La pantalla es un **monitor de PC**: no tiene Modo Juego y no anuncia ALLM.
Los monitores normalmente no procesan la imagen, así que ese aviso no aplica (reformulado en ULL.3).

### Benchmark H.264: la mejora de "standard" no se reproduce

Media / p95 de decodificación (ms) en las tres ejecuciones:

| Modo | Ejecución 1 (22:29) | Ejecución 2 (23:05) | Ejecución 3 (23:07) |
|---|---|---|---|
| original (`low-latency=1`) | 3,25 / 4,14 | 2,40 / 2,73 | 2,32 / 2,52 |
| standard (+`priority=0`) | 2,38 / 2,58 | 2,32 / 2,52 | 2,32 / 2,54 |
| vendor (+`vendor.START.low-latency.enable` en 2 y 3) | 2,92 / 3,48 | 2,86 / 3,76 | 2,33 / 2,67 |
| off | 10,08 / 10,79 | 10,05 / 11,16 | 10,83 / 12,47 |

**Conclusión medida.** Con `low-latency=1`, el H.264 de este firmware decodifica 1080p en
**~2,3 ms (p95 ~2,5 ms)**, y ni `priority=0` ni `vendor.START.low-latency.enable` lo mejoran de
forma reproducible. Sin esa clave tarda ~10 ms. La configuración de Moonlight original ya es óptima
para la decodificación en este equipo. **Retiro la afirmación de ULL.2** ("−0,87 ms / −1,56 ms en
p95"): salía de una sola ejecución en la que "original", medido siempre el primero, iba en frío.

### Benchmark HEVC

| Modo | Ejecución 1 | Ejecución 2 |
|---|---|---|
| original | 2,25 / 2,51 | 2,86 / 3,94 |
| standard | 2,91 / 3,82 | 2,90 / 3,72 |
| vendor | 2,35 / 2,79 | 2,30 / 2,74 |
| off | 5,81 / 7,43 | 5,88 / 7,07 |

Con cualquier clave de baja latencia HEVC decodifica en ~2,3–2,9 ms, y en 58 FPS presentados. La
recomendación cambió entre ejecuciones ("original" en la primera, "vendor" en la segunda): la
variación entre ejecuciones (~0,6 ms) superaba el umbral de 0,5 ms. Ver los cambios de ULL.3.

### Rendimiento máximo (corregido en ULL.2, sin presentar)

H.264 1080p: **137 FPS**, coherente con los 134 declarados por el fabricante, lo que confirma que el
valor de 120 era el límite de la pantalla. HEVC: 93 FPS (con "vendor"; el fabricante declara 246),
sin explicación por ahora. En ambos casos 60 FPS tienen margen.

### Sesiones con imagen en movimiento (todas HEVC con la regla de seguridad, 1080p60)

| Sesión | Perfil / presentación | Mbps config. / reales | Recepción p50 (media) | Decodif. p50 | Decodif.→liberado p50 | Liberado→pantalla p50 | 1er paquete→pantalla p50 |
|---|---|---|---|---|---|---|---|
| 23:01 | Máx. calidad / equilibrado | 50 / 7,5 | 0,10 (1,51) | 5,54 | 14,74 | 13,14 | 36,3 |
| 23:02 | Equilibrado / equilibrado | 30 / 12,2 | 1,62 (1,80) | 5,70 | 17,78 | 13,14 | 39,5 |
| 23:03 | Equilibrado / equilibrado | 30 / 16,3 | 3,42 (2,61) | 6,72 | 16,20 | 13,08 | 42,2 |
| 23:04 | Custom / máx. fluidez (libera al instante) | 70 / 39,7 | 8,90 (6,91) | 6,94 | 1,76 | 20,70 | 37,7 |

1. **El bitrate cuesta latencia de recepción, tal como predecía el cálculo.** ~0,19 ms por Mbps real
   a 60 FPS (media), frente a 0,2 ms/Mbps calculados para 100 Mbps con 20 % de FEC: 1,8 ms a
   12 Mbps, 2,6 ms a 16 Mbps y 6,9 ms (p50 8,9) a 40 Mbps. Es la palanca más clara medida en el ZTE.
2. **HEVC con la regla de seguridad (`priority-only`) decodifica en 5,5–6,9 ms**, frente a ~2,3–2,9 en
   el benchmark con baja latencia. Las sesiones fueron antes de los benchmarks, sin confirmación
   visual, así que se aplicó la regla.
3. **Presentación: liberar al instante es ~6–8 ms mejor que "equilibrado"** en el tramo decodificado →
   pantalla: 1,8 + 20,7 ≈ 22,5 ms frente a 14,7–17,8 + 13,1 ≈ 28–31 ms.
4. **La corrección del jitter de ULL.2 funciona**: retardo de cola p95 3–4 ms y jitter 0,1–0,4 ms por
   cable (antes, un falso p95 de ~30 ms).
5. "Hasta el decodificador" sigue en ~1,4–1,7 ms (p50). ULL.3 lo divide en tramo nativo/JNI y tramo
   Java/MediaCodec para saber si merece la pena optimizarlo.

**Estimación, no medida directa.** Sumando las etapas medidas por separado, H.264 con baja latencia
(2,3 ms) + liberación inmediata (~1,5 + 20,7 ms) + 15 Mbps (~2,8 ms de recepción) + ~1,5 ms hasta el
decodificador da **~29 ms** de primer paquete a pantalla, frente a ~37–42 ms de estas sesiones HEVC.
Hay que confirmarlo con una sesión del perfil ULL con imagen en movimiento.

### Cambios en ULL.3

| Cambio | Motivo |
|---|---|
| Benchmark: una ejecución de calentamiento descartada, 3 rondas con orden alterno, y solo se recomienda una configuración distinta de la original si es más rápida **en todas las rondas** | Arranque en frío de la ejecución 1; recomendación HEVC inestable |
| El informe del benchmark muestra la p95 de cada ronda | Ver la consistencia a simple vista |
| Nueva etapa "nativo → Java" dentro de "hasta el decodificador" (overlay, informes, CSV `jni_ms`, comparador) | Localizar los ~1,5 ms de ese tramo |
| Aviso de ALLM reformulado (TV: Modo Juego; monitor: nada que hacer) y nombre EDID de la pantalla en el informe | Pruebas con monitor de PC |

### Próximas pruebas (ULL.3)

1. **Benchmark H.264 y HEVC** una vez cada uno (ahora tardan ~1,5 min); contesta a la pregunta visual.
2. **Perfil ULL** (H.264, liberación inmediata) con `latency-clock.html` o un juego, a **15 Mbps**, con
   *Registro CSV por fotograma*. 2–3 sesiones de ≥ 1 min.
3. Mismo contenido con el perfil **Moonlight original** (también H.264 forzado y 15 Mbps) para
   comparar dentro de la misma app.
4. Opcional: si el benchmark HEVC se confirma fluido, una sesión HEVC con el perfil ULL y códec "auto"
   (HEVC necesita menos bitrate para la misma calidad, es decir, menos recepción).

