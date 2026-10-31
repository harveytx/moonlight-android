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
   decodificado a pantalla es menor que 14 ms: entre ~1 y ~8 ms según la sesión (ver 9.7).
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
3. **Presentación: liberar al instante fue ~6–8 ms mejor que "equilibrado" en estas sesiones** (en 9.7, ~1 ms en otra) en el tramo decodificado →
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

## 9.7 Tercera ronda (ULL.3, 31-10-2026)

Datos: benchmarks H.264 y HEVC con la metodología nueva, 2 sesiones largas (5,5 y 4 min) y el
primer **CSV por fotograma** (14 928 fotogramas). La pantalla se identifica por EDID como
**LG ULTRAGEAR** (2023), conexión directa.

### Benchmark con calentamiento, 3 rondas y orden alterno

| Modo | H.264 media / p95 | p95 por ronda | HEVC media / p95 | p95 por ronda |
|---|---|---|---|---|
| original | **2,35 / 2,68** | 2,50 / 3,00 / 2,54 | 2,63 / 3,29 | 4,88 / 2,50 / 2,48 |
| standard | 2,65 / 3,09 | 2,54 / 2,52 / 4,20 | 2,47 / 2,97 | 3,98 / 2,48 / 2,46 |
| vendor | 2,67 / 3,33 | 4,94 / 2,56 / 2,50 | 2,24 / 2,50 | 2,50 / 2,50 / 2,50 |
| off | 10,41 / 11,02 | — | 5,55 / 6,59 | — |

* H.264: "original" es el más rápido. Confirma, con la metodología corregida, que la configuración de
  Moonlight ya es óptima en este firmware.
* HEVC: "vendor" ganó en media (0,79 ms), pero no en la ronda 3 (2,50 frente a 2,48), así que la regla
  nueva mantuvo "original". Es el comportamiento buscado: en las rondas sin incidencias todas las
  configuraciones con baja latencia dan ~2,5 ms de p95, y las diferencias vienen de rondas sueltas con
  picos (~4–5 ms).
* **El "salto" que se veía al llegar el contador a 59 lo causaba el propio benchmark**, no el
  decodificador: tras los 60 fotogramas de calentamiento (0–59) se dejaba de enviar ≥ 150 ms para
  vaciar el decodificador. No afectaba a las medidas, que lo excluían, pero sí a la pregunta visual.
  Una respuesta "No" descarta el resultado, y por eso el informe de capacidades de las 07:58 ya no
  mostraba recomendación para H.264. **Corregido en ULL.4**: el envío es continuo y las estadísticas
  se reinician sin pausa.

### Sesiones

| Sesión | Códec / modo LL | Presentación | Mbps reales | Hasta decodif. p50 (nativo→Java) | Decodif. p50 / p99 | Decodif.→liberado p50 | Liberado→pantalla p50 | 1er paquete→pantalla p50 |
|---|---|---|---|---|---|---|---|---|
| 07:52 (5,5 min) | HEVC / priority-only | máx. fluidez (inmediata) | 9,0 | 1,28 (0,02) | 5,12 / 7,74 | 1,36 | 21,50 | 31,1 |
| 07:58 (4,2 min) | **H.264 / original** | equilibrado | 3,8 | 1,04 (0,02) | **2,38 / 4,38** | 10,28 | 13,34 | **27,1** |

1. **El tramo nativo → Java (JNI) cuesta ~0,02 ms.** Todo el ~1 ms de "hasta el decodificador" es la
   copia al buffer de entrada más `MediaCodec.queueInputBuffer()`, una llamada entre procesos al
   servicio de códecs de Codec2. Queda confirmado con datos que eliminar copias en JNI no aportaría
   nada (decisión de 03, ahora medida).
2. **Picos de "hasta el decodificador":** p99 de 3,1–3,4 ms en ULL.2/ULL.3, frente a 19–38 ms en las
   sesiones de ULL.1. En el CSV los pocos picos > 5 ms aparecen aislados (separados por 100–3000
   fotogramas), no una vez por segundo. *Interpretación*: sacar el cálculo de estadísticas y el formato
   del overlay del hilo de recepción eliminó los picos periódicos. Las sesiones de ULL.1 eran más
   cortas y con escritorio estático, así que no es un A/B controlado. Moonlight original formatea su
   overlay en ese mismo hilo cuando el overlay está activado.
3. **Decodificación H.264 en streaming = benchmark**: p50 2,36 ms, p99 4,36 ms, p99,9 5,8 ms. No
   depende del tamaño del fotograma (correlación −0,02).
4. **Presentación:** en esta sesión H.264 "equilibrado" retuvo 10,3 ms (p50) y el fotograma tardó
   13,3 ms en verse, con una dispersión mínima (p95 13,66 ms: el plazo del sistema). Con liberación
   inmediata (sesión HEVC) fueron 1,4 + 21,5 ms. La diferencia neta fue de ~1 ms aquí, frente a 6–8 ms
   en las sesiones HEVC de 9.6, donde "equilibrado" retenía 15–18 ms. *Interpretación*: "equilibrado"
   puede acumular un fotograma de cola según el ritmo de llegada y la liberación inmediata no. Por eso
   esta nunca es peor, pero su ventaja varía entre ~1 y ~8 ms.
5. El contenido (`latency-clock.html`) comprime a ~3,8 Mbps aunque se pidan 15: sirve para medir
   latencia, pero no el efecto del bitrate. Para eso hace falta un juego o un vídeo.
6. **Mejor resultado medido hasta ahora: 27,1 ms (p50) del primer paquete a pantalla** (H.264,
   configuración original, presentación "equilibrada"). Con presentación inmediata (perfil ULL) se
   esperan entre ~1 y ~8 ms menos. Sigue faltando una sesión del perfil ULL tal cual.

### Cambios en ULL.4

| Cambio | Motivo |
|---|---|
| Benchmark: calentamiento y medida en un único flujo continuo; las estadísticas se reinician sin pausa | Salto visible en el fotograma 59 que invalidaba la comprobación visual |
| Pregunta visual aclarada (es normal que el contador vuelva a 0 al empezar cada pasada y se pare al terminarla) | Evitar falsos "No" |
| Informe de capacidades: tipo de conexión de la pantalla con nombre | Legibilidad |

### Próximas pruebas (ULL.4)

1. Benchmark H.264 y HEVC una vez cada uno. Si el contador va fluido dentro de cada pasada, contesta
   "Sí": en HEVC eso permite usar la baja latencia (~2,5 ms en lugar de ~5 ms).
2. **Perfil "Ultra Low Latency" sin tocar nada más** (H.264, presentación inmediata), con un juego o
   vídeo en movimiento, 2–3 sesiones de ≥ 1 min con CSV por fotograma.
3. Mismo contenido con el perfil "Moonlight original" para la comparación directa.

## 9.8 Cuarta ronda (ULL.3, 31-10-2026 08:18–08:24) y versión ULL.5

Datos: benchmarks H.264 y HEVC (aún con la pausa del fotograma 59), una sesión "custom" con
presentación equilibrada y CSV, y **la primera sesión del perfil Ultra Low Latency con imagen en
movimiento**.

### Benchmark

* H.264: "original" vuelve a ser el más rápido (2,32 / 2,53 ms, p95 por ronda 2,52 / 2,54 / 2,52). Es
  la cuarta confirmación.
* HEVC: "vendor" es el mejor en media (2,32 / 2,62), pero perdió una ronda frente a "original" (2,46
  frente a 2,44), así que se mantuvo "original".
* Hubo dos **fallos falsos** por "FPS presentados": "vendor" en H.264 (44,4 FPS) y "standard" en HEVC
  (40,9 FPS), cada uno en una sola ronda. *Interpretación*: Android puede entregar tarde o agrupadas
  las notificaciones de presentación, y el recuento se cerraba antes de que llegaran. Bastaba una
  ronda así para descartar la configuración entera. **Corregido en ULL.5**: se espera a que dejen de
  llegar notificaciones, se usa la proporción de fotogramas presentados frente a liberados (mediana
  de las rondas) y solo se rechaza por debajo del 50 %. El "efecto diapositiva" real queda muy por
  debajo de ese umbral.

### Sesiones: presentación equilibrada frente al perfil ULL (mismo códec y decodificador)

| | 08:18 "custom" (equilibrado, 15 Mbps) | 08:20 **perfil ULL** (inmediata, 20 Mbps) | Diferencia |
|---|---|---|---|
| Mbps reales / FPS recibidos | 7,2 / 56,9 | 7,8 / 56,3 | similar |
| Decodificación media / p95 | 2,85 / 4,14 | 2,60 / 3,90 | −0,25 / −0,24 |
| Decodificado → liberado media | 14,29 | 1,29 | −13,0 |
| Liberado → pantalla media | 13,43 | 21,61 | +8,2 |
| **1er paquete → pantalla, media** | 32,72 | **27,89** | **−4,8 ms (−15 %)** |
| 1er paquete → pantalla, p50 / p95 | 34,08 / 38,24 | **28,38 / 34,74** | −5,7 / −3,5 |

Es la comparación más limpia hasta ahora: mismo H.264, misma configuración del decodificador, misma
prioridad y contenido similar. **Pero no es una mejora frente a Moonlight original**: la presentación
"menor latencia" es también la opción por defecto de Moonlight. Lo que mide es el coste de la
presentación "equilibrada" (que usan los perfiles Equilibrado y Máxima calidad) en este equipo: unos
5 ms.

### Estado frente a Moonlight original en este ZTE (resumen honesto)

| Aspecto | Moonlight original (por defecto) | Perfil ULL | Diferencia medida |
|---|---|---|---|
| Claves del decodificador | `low-latency=1` | La misma (el benchmark confirmó 4 veces que es la mejor) | 0 |
| Presentación | Menor latencia | Menor latencia | 0 |
| Códec | Auto → HEVC con `low-latency=1` | H.264 | ~0 en decodificación (HEVC 2,3–2,7 ms, H.264 2,3 ms en el benchmark) |
| Prioridad de `VideoRecv` | nice 0 | nice −8 | No medida por separado (afectaría a las colas, p99) |
| Protección de latencia | — | Agresiva | 0 resincronizaciones en todas las sesiones: sin efecto en condiciones normales |
| moonlight-common-c | Anterior | Con correcciones de pérdidas | 0 pérdidas en todas las sesiones: no se ha ejercitado |
| Overlay formateado en el hilo de recepción | Sí (si el overlay está activado) | No | Colas p99 con el overlay activado |

**Conclusión provisional.** En condiciones normales de este ZTE (cable, sin pérdidas, decodificador
holgado), **la latencia mediana del perfil ULL debería ser prácticamente igual a la de Moonlight
original**: la configuración óptima ya era la suya. Las diferencias de ULL están en la robustez
(colas, pérdidas, saturación del decodificador) y en haber medido y verificado esa configuración. Lo
que de verdad se ha medido que cuesta latencia en este equipo, y conviene evitar, es:

- Usar presentación "equilibrada": ~5 ms.
- Subir el bitrate: ~0,19 ms por Mbps real.
- HEVC con la regla de seguridad Amlogic: ~3 ms más de decodificación.

Falta la prueba directa contra la app oficial (5.4, configuración A).

**Presupuesto de latencia de la sesión ULL** (p50): host 4,5 ms · recepción 1,0 · hasta el
decodificador 1,1 · decodificación 2,4 · liberación 1,2 · **liberado → pantalla 22,8** (espera al
vsync más el plazo de SurfaceFlinger de 13,7 ms) · más el monitor. El mayor tramo que queda está en la
composición de Android. La única vía conocida para recortarlo desde el cliente es el **modo túnel**
de MediaCodec: el decodificador lo anuncia (`tunneled-playback`, parámetros `vendor.tunnel.*`) y
saltaría SurfaceFlinger. Es experimental, no está implementado y tiene riesgos (sincronización con el
audio). Se propone como siguiente experimento.

### Cambios en ULL.5

| Cambio | Motivo |
|---|---|
| **Una carpeta por versión**: los informes se guardan en `ull-sessions/<versión>/` (p. ej. `ull-sessions/12.2-ULL.5/`). Al primer arranque, los archivos de versiones anteriores se ordenan: cada sesión va a la carpeta de la versión que figura en su JSON y los demás (capacidades, benchmarks, que no llevaban versión) a `ull-sessions/unversioned/` | No mezclar datos de versiones distintas (petición del usuario) |
| La versión de la app figura en la cabecera del informe de capacidades y del benchmark, y en la lista de sesiones | Identificar cada archivo aunque se mueva |
| La comparación A/B sigue pudiendo elegir sesiones de cualquier versión | Comparar entre versiones es útil |
| Benchmark: comprobación de presentación por mediana de rondas, esperando las notificaciones tardías; columna `disp%` | Fallos falsos de "FPS presentados" |


## 9.9 Quinta ronda (ULL.5, 31-10-2026 08:39–08:52) e imagen a 80 Mbps (ULL.6)

Datos en [data/zte-b866v2k/12.2-ULL.5/](data/zte-b866v2k/12.2-ULL.5/). Todas las sesiones son del
escritorio del PC ("Desktop") a 1080p60 en el monitor LG UltraGear, por Ethernet.

### Benchmark

| Códec | Recomendado | p50 / p95 | Alternativa más rápida | Comentario |
|---|---|---|---|---|
| H.264 | original (`low-latency=1`) | 2,65 / 3,50 ms | vendor 2,32 / 2,76 ms | vendor no fue más rápido en la 1.ª ronda (3,16 frente a 2,58): se mantiene original, por quinta vez |
| HEVC | **vendor** | 2,24 / 2,67 ms | — | Más rápido que original en las 3 rondas (p95 3,04/2,46/2,50 frente a 5,08/2,54/2,60) |

`disp%` = 100 en todos los modos: la comprobación por mediana de ULL.5 elimina los falsos rechazos.

### Sesiones

| Hora | Perfil | Códec | Bitrate configurado / real | Host p50 | Decodificación p50 | Recepción p50 / p95 | 1.er paquete → liberado p50 |
|---|---|---|---|---|---|---|---|
| 08:39:36 | ULL | H.264 | 20 / 3,6 Mbps | 4,82 ms | 2,26 ms | 0,22 / 2,00 ms | 4,78 ms |
| 08:40:46–08:41:32 (3) | original | HEVC | 20 / 1,4–1,7 | 5,62 | 2,26–2,38 | 0,04 / 1,3–2,0 | 4,52–5,20 |
| 08:42:11 | Máxima calidad | HEVC | 50 / 0,7 | 5,52 | 2,42 | 0,02 / 0,04 | 21,40 (presentación equilibrada) |
| 08:42:28 | Equilibrado | HEVC | 30 / 0,7 | 5,42 | 2,20 | 0,02 / 0,04 | 11,28 (presentación equilibrada) |
| 08:42:41, 08:43:08 | ULL | H.264 | 20 / 1,1–1,3 | 4,72 | 2,28–2,56 | 0,04 / 0,18–0,20 | 4,56–5,04 |
| 08:44:05 | personalizado (standard) | H.264 | 20 / 3,8 | 4,82 | 2,36 | 0,04 / 2,26 | 5,16 |
| 08:44:45 | personalizado (vendor) | H.264 | 20 / 11,7 | 4,92 | 2,32 | 2,02 / 2,74 | 6,86 |
| **08:48:05** (161 s) | personalizado (vendor) | H.264 | **50 / 22,8** | 5,02 | 2,50 | **5,62 / 6,62** | **10,84** |

0 pérdidas y 0 resincronizaciones en todas. Lo que muestran:

* **HEVC no cuesta decodificación en este ZTE** (2,2–2,4 ms frente a 2,3–2,6 ms en H.264). En el
  PC, la latencia de host sube ~0,9 ms (5,62 frente a 4,72 ms con el mismo escritorio estático).
* **El bitrate configurado no cuesta nada con la imagen quieta** (Máxima calidad, 50 Mbps: 0,7 Mbps
  reales, recepción 0,02 ms). Cuesta cuando la imagen cambia: en la sesión de 50 Mbps,
  1.er paquete → liberado pasó de ~5 ms a 10,84 ms. De esos ~6 ms, 5,6 ms son recepción.

### Coste medido de cada kB por fotograma

Con las 9 sesiones distintas de ULL.2 a ULL.5 (de 7 a 40 Mbps reales), la recepción media se ajusta
a **−0,30 + 0,086 ms por kB de fotograma** (R² = 0,999). 0,086 ms/kB equivale a ~93 Mbps: los fotogramas llegan
a la velocidad del puerto de 100 Mbps del ZTE. Es la cifra de 0,19 ms por Mbps real de 9.6,
expresada por fotograma, y la que usa ULL.6 para sus estimaciones (`BitrateBudget`).

### Qué daría 80 Mbps

Sunshine reserva el FEC dentro del bitrate configurado y limita cada fotograma a un fotograma de
bitrate de codificador (verificado en su código; tabla en [7.4](07-sunshine.md#74-bitrate-recomendado-para-el-zte)):

| Configurado | Techo por fotograma | Recepción de un fotograma en el techo (*estimada*) |
|---|---|---|
| 20 Mbps | 32 kB | 2,7 ms |
| 50 Mbps | 82 kB | 7,0 ms |
| 80 Mbps | 132 kB | 11,3 ms |

En la sesión de 50 Mbps, la recepción p50/p95 (5,62/6,62 ms) corresponde, con el ajuste anterior, a
fotogramas de ~69/80 kB (*estimado*). Es decir, cuando había cambios en pantalla, muchos fotogramas
estaban en el techo de 82 kB o cerca: **en esos momentos el bitrate sí limitaba la imagen**, y
80 Mbps les daría un 61 % más de bits. El coste estimado es de hasta ~11 ms de recepción por
fotograma en el techo (+4,3 ms frente a 50 Mbps, +8,6 ms frente a 20 Mbps). Además, el enlace iría
al ~80 % en escenas complejas, con poco margen para ráfagas y riesgo de pérdidas (el informe las
registra). Con la imagen quieta no cambia nada: Sunshine vuelve a codificar la imagen estática y la
afina igualmente.

**Conclusión.** En este ZTE, 80 Mbps es la forma más cara de mejorar la imagen. Antes conviene
probar, de mayor a menor beneficio esperado por milisegundo:

1. **Que nada escale la imagen.** El ZTE solo ofrece salidas de 720p, 1080p y 4K, no 1440p. El
   monitor (EDID `GSM 5B8D`, 2023) acepta 4K60, pero su resolución nativa no se puede leer desde
   Android y el identificador no aparece en las bases de datos públicas consultadas. Si el panel o
   el escritorio del PC no son 1080p, hay escalado (Sunshine al capturar, el monitor o ambos), y en
   un monitor de PC eso ablanda el texto más que cualquier bitrate. Si el panel es 4K, un stream 4K60
   en HEVC lo evitaría: el decodificador HEVC admite 4K60, H.264 solo ~30 FPS a 4K, y Moonlight
   cambia la salida del ZTE a 4K solo. Su coste de decodificación no está medido.
2. **HEVC con el mismo bitrate** (medido arriba: misma decodificación, +0,9 ms en el PC).
3. **Calidad del codificador en Sunshine** (preset, dos pasadas, AQ, FEC 10 %; ver
   [7.7](07-sunshine.md#77-mejorar-la-imagen-sin-perder-de-vista-la-latencia)). Su coste aparece en
   "Procesamiento en host".
4. **Más bitrate**, solo si el overlay de ULL.6 muestra muchos fotogramas "en el techo".

No es posible en este ZTE: croma 4:4:4. Ningún decodificador anuncia perfiles 4:4:4, así que el
texto de color siempre saldrá algo borroso. El decodificador recibe hoy rango limitado
(`color-range=2`); el rango completo es una opción por probar, no una mejora demostrada.

### Cambios en ULL.6

| Cambio | Motivo |
|---|---|
| **Tamaño de fotograma frente al techo de Sunshine.** En el overlay detallado: "Fotograma: p50 / p95 kB · techo ≈ X kB · en el techo Y %". En el informe: `frame_size_kb` (p50/p95/p99/máx, techo estimado, % de fotogramas ≥ 85 % del techo). También en el comparador A/B y en `compare-sessions.py` | Saber con datos si más bitrate puede mejorar algo, en lugar de suponerlo |
| **Aviso de bitrate** con la contabilidad real de Sunshine (el FEC va dentro del bitrate; el aviso anterior sumaba un 20 % que Sunshine ya descuenta) y el tiempo de recepción de un fotograma en el techo según la medida de 9.9. Cabe en 2 líneas | Android 14 corta en 2 líneas los avisos emergentes (toast); el texto anterior era más largo |
| **Pantalla en el informe:** modo de pantalla durante el stream (`display_mode`), escalado en el cliente (`client_scaling`) y rango de color (`color_range`) | Comparar resoluciones y salidas del ZTE sin anotarlas a mano |
| `tools/image-test.html` | Patrón en píxeles físicos del PC: escalado (líneas de 1 px), negros y blancos, croma, texto y textura en movimiento, y ruido para forzar el techo |

### Próximas pruebas (ULL.6)

1. En el PC, abre `tools/image-test.html` en el navegador a pantalla completa (F11) y conéctate
   al escritorio con el overlay detallado activado.
2. Mira las líneas de 1 px en el monitor del ZTE. Si no están nítidas, hay escalado: indica el
   modelo exacto del monitor (etiqueta trasera), la resolución del escritorio del PC y su escala de
   Windows.
3. Con el movimiento activado (M), 60 s por configuración, todas con el mismo patrón:
   A: H.264 50 Mbps · B: H.264 80 Mbps · C: HEVC 50 Mbps · D: HEVC 80 Mbps.
   Haz una foto del texto en movimiento en cada una y apunta "en el techo" del overlay.
4. Opcional: 30 s de ruido (N, 768 px) a 80 Mbps, para ver el límite de la red (pérdidas,
   recepción).
5. Envía `ull-sessions/12.2-ULL.6/` y las fotos.

## 9.10 Sexta ronda (ULL.6, 31-10-2026 09:48–09:59) y capturas para comparar la nitidez (ULL.7)

Datos en [data/zte-b866v2k/12.2-ULL.6/](data/zte-b866v2k/12.2-ULL.6/). 11 sesiones a 1080p60 y un
benchmark H.264 (10:01).

### Monitor: LG 27GP750-B, panel 1920×1080

Según la etiqueta, el monitor es un **LG 27GP750-B** (09/2023). Las fichas de producto lo describen
con panel IPS **1920×1080 a 240 Hz** y HDMI 2.0. Aunque su EDID anuncia modos 3840×2160, el panel es
1080p, así que una salida 4K del ZTE solo se reduciría en el monitor: no aporta nitidez. La salida
1080p del ZTE coincide con el panel y las 11 sesiones registran `display_mode` 1920x1080@60.00 y
`client_scaling` none. Si el escritorio del PC también está a 1920×1080, nada escala en toda la
cadena. Lo que falte de nitidez frente al PC vendrá entonces de la compresión (bitrate, croma 4:2:0)
o del procesado de vídeo del ZTE o del monitor.

### Tamaño de fotograma frente al techo (primera ronda con esta medida)

| Hora (duración) | Códec | Configurado / real | Fotograma p50 / p95 / máx | Techo estimado | En el techo | Recepción p50 / p95 | 1.er paquete → liberado p50 / p95 |
|---|---|---|---|---|---|---|---|
| 09:54:51 (64 s) | H.264 | 20 / 11,3 Mbps | 24,8 / 30,3 / 31,6 kB | 31,9 kB | 36 % | 1,78 / 2,52 ms | 6,98 / 8,36 ms |
| 09:58:11 (99 s) | H.264 | 20 / 10,6 | 30,3 / 31,7 / 33,0 | 31,9 | 69 % | 2,26 / 2,92 | 6,82 / 8,74 |
| 09:50:31 (124 s) | H.264 | 50 / 24,8 | 77,1 / 79,8 / 85,3 | 81,9 | 59 % | 6,02 / 6,68 | 10,96 / 12,76 |
| 09:48:19 (246 s) | H.264 | 50 / 3,9 | 4,1 / 53,7 / 85,3 | 81,9 | 2 % | 0,04 / 4,16 | 4,70 / 9,14 |
| 09:53:33 (143 s) | H.264 | 80 / 17,9 | 39,9 / 123,0 / 144,5 | 131,9 | 6 % | 3,06 / 10,18 | 8,00 / 15,78 |
| 09:55:24 (6 s) | HEVC | 50 / 34,8 | 77,1 / 78,4 / 79,8 | 81,9 | 85 % | 6,24 / 6,74 | (presentación equilibrada) |

0 pérdidas en todas las sesiones. Lo que muestran:

* **El modelo de coste de 9.9 se cumple.** A 80 Mbps, los fotogramas del p95 (123 kB) tardan
  10,18 ms en llegar; el modelo predecía 0,086 × 123 − 0,3 ≈ 10,3 ms.
* **El techo es aproximado.** Los máximos lo superan un 3–10 % (85,3 frente a 81,9 kB; 144,5 frente
  a 131,9): el búfer de NVENC no es un límite exacto. "En el techo" cuenta los fotogramas ≥ 85 % del
  techo, y con ese margen sigue siendo válido.
* **Con imagen que cambia, 20 Mbps está casi siempre en el techo** (36–100 % según la sesión; en las
  sesiones cortas, 100 %) **y 50 Mbps el 59 %**: en esas sesiones la imagen estaba limitada por el
  bitrate. En la sesión de 80 Mbps, el p95 (123 kB) supera en un 50 % el techo de 50 Mbps, así que
  esos fotogramas, a 50 Mbps, habrían salido con menos calidad. No se pueden comparar más a fondo:
  el contenido no fue el mismo en todas (la sesión de 80 Mbps tuvo menos bitrate real que la de 50).
* **Coste en latencia** (p95 de 1.er paquete → liberado): 8,4–8,7 ms a 20 Mbps, 12,8 ms a 50 y
  15,8 ms a 80.
* **HEVC con las claves "vendor"** (recomendadas por el benchmark de ULL.5) funciona en una sesión
  real, con decodificación de 2,44 ms (sesión de 6 s).
* **Benchmark H.264:** original otra vez (p95 2,55 ms; vendor 2,58).

### Capturas para comparar con el PC (ULL.7)

La nitidez frente al PC no se puede juzgar bien con fotos del monitor. ULL.7 añade:

* **Opción "Capturas del vídeo para comparar la imagen"** (cada 10, 30 o 60 s). Guarda el fotograma
  decodificado, tal como sale del decodificador y antes del procesado del ZTE y de la salida HDMI,
  como PNG del tamaño del stream en `ull-sessions/<versión>/`. El códec y el bitrate van en el
  nombre del archivo. Usa `PixelCopy`. Algunos equipos ponen el vídeo en un plano que las apps no
  pueden leer: entonces la captura sale negra o falla. Por eso la primera llega a los 5 s y avisa
  del resultado.
* **Patrón reproducible.** Cada fotograma de `tools/image-test.html` depende solo de su número. Con
  la tecla G y el número que aparece en la captura, el PC muestra exactamente ese contenido para
  hacer la captura de referencia.
* **`tools/compare-captures.html`.** Mide nitidez relativa, SSIM, PSNR de luma y croma y
  desplazamiento de nivel en la zona estática, la de movimiento o la que se elija.

Lo que falte en las capturas frente al PC es compresión: se corrige con bitrate, códec o ajustes de
Sunshine. Si las capturas son nítidas pero el monitor no, la pérdida está en la salida de vídeo del
ZTE (su procesado, una opción de zoom o posición de pantalla) o en el monitor (nitidez, modo de
imagen). Si la captura sale negra en el ZTE, la alternativa es una capturadora HDMI en el PC
conectada a la salida del ZTE. Esa medida incluiría también el procesado del ZTE.

**Hipótesis (por medir):**

* En la zona estática, la luma será casi idéntica a partir de 50 Mbps, porque Sunshine vuelve a
  codificar la imagen fija y la afina.
* El croma será siempre peor por el 4:2:0.
* En la zona de movimiento, la nitidez subirá con el bitrate y será mejor con HEVC que con H.264 al
  mismo bitrate.

### Próximas pruebas (ULL.7)

1. En la app: capturas cada 30 s y overlay detallado. En el PC: escritorio a 1920×1080 y
   `tools/image-test.html` a pantalla completa con la ayuda oculta (H), en movimiento (M) y sin
   ruido.
2. Unos 2 minutos por configuración: H.264 a 20, 50 y 80 Mbps; HEVC a 50 y 80 Mbps.
3. Para 2 o 3 capturas de cada configuración: tecla G con su número de fotograma y captura del PC.
   Compara en `compare-captures.html` la zona estática y la de movimiento.
4. Envía `ull-sessions/12.2-ULL.7/` (con los PNG) y las capturas del PC.
