# 5. Benchmark y pruebas comparativas

## 5.1 Qué se ha verificado ya y qué no

| Verificación | Estado | Cómo |
|---|---|---|
| El código compila (Java + nativo, 4 ABIs) y R8/lint vital pasan | ✅ Hecho | `./gradlew assembleNonRootRelease` |
| Lógica nueva (histograma, jitter, protección de latencia, estadísticas, parser Annex B, regla de recomendación) | ✅ 50/50 tests pasan | `./gradlew testNonRootDebugUnitTest` |
| Los clips del benchmark tienen la estructura esperada (IDR + 119 P, SPS/PPS, 1 slice) | ✅ Test + `ffprobe` | `AnnexBClipTest` |
| Funcionamiento en el ZTE B866V2K | ❌ **Pendiente** | Requiere el dispositivo (no disponible en el entorno de desarrollo, sin KVM para emulador) |
| Mejora de latencia frente a Moonlight original | ❌ **Pendiente de medir** | Procedimiento de 5.3–5.5 |

**Primeros resultados reales del ZTE: [09-resultados-zte.md](09-resultados-zte.md).** Las cifras de
este documento marcadas como *cálculo* o *hipótesis* no son resultados. La tabla de 5.7 sigue
pendiente de una prueba con imagen en movimiento.

> Usa contenido en movimiento: con un escritorio estático Sunshine envía pocos fotogramas
> (43–51 FPS y ~3 Mbps reales en las primeras sesiones del ZTE), y las métricas de red y bitrate no
> significan nada.

## 5.2 Qué se mide y qué se estima

| Métrica | Tipo | Fuente |
|---|---|---|
| Recepción del fotograma (1er → último paquete) | **Medida** | Marcas nativas `receiveTimeUs` / `enqueueTimeUs` |
| Reensamblado → cola del decodificador; espera de buffer de entrada | **Medida** | `System.nanoTime()` |
| Decodificación (`queueInputBuffer` → `dequeueOutputBuffer`) | **Medida** | `System.nanoTime()` |
| Espera hasta liberar | **Medida** | `System.nanoTime()` |
| Liberación → presentación | **Reportada por Android** (`OnFrameRenderedListener`; puede faltar o llegar agrupada) | Sistema |
| Total cliente (1er paquete → liberación / → presentación) | **Medida** / reportada | Suma de lo anterior |
| FPS recibidos, decodificados, renderizados, presentados; fotogramas perdidos en red; reemplazados antes de mostrarse | **Medida** (contadores) | |
| Jitter RFC 3550 | **Medida** (variación del tránsito envío→llegada: PTS de captura + latencia del host; se ignoran los fotogramas duplicados de Sunshine) | PTS del host |
| Retardo de cola | **Estimación** (respecto al mínimo de 10 s, mismos fotogramas) | PTS del host |
| Procesamiento en el host | **Reportado por Sunshine** | Cabecera de fotograma |
| RTT de red | **Medida** por ENet (moonlight-common-c) | Overlay original |
| CPU del proceso y del hilo `VideoRecv` | **Medida** (/proc) | |
| Paquetes perdidos | **No disponible por paquete** | moonlight-common-c recupera paquetes con FEC internamente y no expone contadores por paquete (los campos `packetCountFecRecovered`/`packetCountOOS` de `RTP_VIDEO_STATS` existen pero upstream no los incrementa). Se reportan los **fotogramas** perdidos en red, que es lo que llega a la pantalla. Contar paquetes exigiría bifurcar moonlight-common-c. |
| Latencia de extremo a extremo (cristal a cristal) | **Medida externa** con cámara (5.5) | |
| Latencia entrada → pantalla | **Medida externa** con cámara (5.5) | |

**No se debe** declarar una reducción de latencia entrada→pantalla basándose solo en el tiempo de
decodificación: la decodificación es una parte; la red, el host, el vsync y el TV pueden dominar.

## 5.3 Prueba 1 — benchmark del decodificador (sin red)

1. *Ajustes → Ultra Low Latency → Diagnóstico y benchmark → Benchmark del decodificador (H.264)*.
2. No toques el mando durante ~1 minuto. Comprueba que el contador grande avanza con fluidez en
   todas las pasadas (si una pasada se ve a saltos, esa configuración no es válida aunque los números
   parezcan buenos).
3. Repite **3 veces** (se guarda `decoder-bench-*.txt` cada vez). La recomendación debe ser estable.
4. Opcional: benchmark HEVC para comparar códecs en el ZTE.

Resultado: tabla `mode / mean / p50 / p95 / max / out% / dispFPS / dispMs / estado / claves`,
modo recomendado y rendimiento máximo (¿1080p120 viable?).

## 5.4 Prueba 2 — sesiones A/B de streaming

Condiciones idénticas en todas las ejecuciones: mismo PC, misma versión y configuración de Sunshine,
misma red y cable/puerto, misma resolución (1080p), mismos FPS (60), mismo bitrate, **H.264
forzado** en ambas apps, mismo contenido. Recomendado: abrir `tools/latency-clock.html` a pantalla
completa en el PC (contenido estable y reproducible) o una escena fija de un juego.

Configuraciones:

| ID | App | Ajustes |
|---|---|---|
| A | Moonlight oficial 12.2 (`com.limelight`, Play Store/GitHub) | Por defecto + H.264 forzado + bitrate igual + overlay activado |
| B | Moonlight ULL, perfil **Moonlight original** | Misma configuración; mismo comportamiento que A pero con instrumentación |
| C | Moonlight ULL, perfil **Ultra Low Latency** | Tras ejecutar el benchmark del decodificador |
| D | Moonlight ULL, ULL con prioridad **Normal** | Aísla el efecto de la prioridad de hilos |
| E | Moonlight ULL, ULL con protección **desactivada** | Aísla el efecto de la protección (solo cambia algo si el decodificador se satura) |

Procedimiento por configuración: 3 sesiones de 3 minutos, alternando el orden (A B C … C B A …) para
no favorecer a ninguna por temperatura o carga. Activa *Registro CSV por fotograma* en las sesiones
de ULL para poder aplicar el test estadístico.

Análisis:
```
tools/collect-diagnostics.sh                 # trae los informes del ZTE
tools/compare-sessions.py B.json C.json --frames
```
Para A (app oficial) solo hay overlay: anota "Average decoding time", "Average network latency",
"Frames dropped" y FPS, y compáralos con las mismas líneas del overlay de B y C (se conservan
idénticas a propósito). La comparación A↔B valida que la instrumentación no altera el resultado.

Criterio: una diferencia se considera real si se repite en las 3 sesiones y el test de Mann-Whitney
da p < 0,01 en el CSV por fotograma.

## 5.5 Prueba 3 — extremo a extremo con cámara

1. PC: `tools/latency-clock.html` a pantalla completa (muestra milisegundos, nº de fotograma y una
   barra en movimiento).
2. Graba con un móvil en cámara lenta (240 fps si es posible) el monitor del PC y el TV a la vez.
3. En 20 fotogramas del vídeo donde ambos relojes se lean: latencia = reloj PC − reloj TV.
4. Entrada → pantalla: con el mando conectado al ZTE, pulsa un botón; la página se pone blanca
   mientras está pulsado. Cuenta los fotogramas de cámara entre la pulsación y el blanco en el TV
   (a 240 fps, 1 fotograma = 4,17 ms).
5. Repite para A y C. Usa la mediana; anota también el modo del TV (Juego/ALLM).

Esta prueba incluye captura, codificación, red, decodificación, vsync, escaneo y el procesado del TV.

## 5.5b Prueba 4 — nitidez frente al original (capturas, desde ULL.7)

Separa lo que pierde la compresión (medible píxel a píxel) de lo que añade la salida de vídeo del
ZTE y el monitor (solo visible con foto).

1. Moonlight ULL: *Ajustes → Ultra Low Latency → Ajustes avanzados → Capturas del vídeo para
   comparar la imagen*: **Cada 30 s**. Usa los FPS del refresco de la pantalla: en el ZTE, 60. La
   primera captura llega a los 5 s y un aviso dice si funcionó. Las capturas son PNG del fotograma
   decodificado, con el códec y el bitrate en el nombre, y se guardan en
   `ull-sessions/<versión>/capturas/moonlight/`. El informe de sesión las lista y el CSV por
   fotograma las marca (columna `capture`).
2. PC: `tools/image-test.html` a pantalla completa, con la ayuda oculta (H). Haz la sesión con
   movimiento (M) para ver la calidad en movimiento, o en pausa (P) para el límite en imagen
   estática.
3. Copia `ull-sessions/<versión>/` del ZTE al PC. En el patrón, también a pantalla completa en el
   mismo monitor, pulsa **B** y elige la carpeta `capturas`. Por cada captura de Moonlight del
   patrón, el navegador dibuja exactamente el mismo fotograma (lo lee del código de la cabecera) y
   lo guarda en `capturas/pc/` con el mismo nombre. Para otro contenido (un juego en pausa, el
   escritorio), pon tus capturas del PC en `capturas/pc/` con el mismo nombre que la de Moonlight.
4. `tools/compare-captures.html` → carpeta `capturas`. Muestra una tabla por pareja y medias por
   configuración: nitidez relativa (energía de gradiente entre píxeles vecinos), SSIM y PSNR de luma
   y croma, en la zona estática y en la de movimiento. Haciendo clic en una fila se abre la vista
   detallada: lado a lado, parpadeo y diferencia, con zoom.
5. Repite con cada configuración (bitrate, códec, ajustes de Sunshine). Lo que falte para "igual de
   nítido" en las capturas es compresión. Si las capturas son nítidas pero el monitor no, la
   pérdida está en la salida de vídeo del ZTE o en el monitor.

Las capturas no miden latencia. Mientras se toma una, el último fotograma queda retenido unos
milisegundos, así que esa opción debe estar desactivada en las sesiones de latencia.

## 5.6 Hipótesis a verificar (no son resultados)

| Cambio | Hipótesis | Cómo se verá |
|---|---|---|
| Modo LL elegido por benchmark | 0 a varios ms menos de decodificación, según firmware | Benchmark + "Decodificación" en sesiones B vs C |
| Prioridad Alta | Menos p95/p99 en recepción y en "hasta el decodificador" con carga | D vs C |
| Protección de latencia | Sin efecto si el decodificador sostiene 1080p60; latencia acotada si no | Contador de resincronizaciones; E vs C con FPS altos |
| moonlight-common-c actualizado | Menos fotogramas perdidos tras pérdidas puntuales (sin congelaciones de ~2 s) | "Fotogramas perdidos en red" y eventos de pérdida |
| Bitrate 20 → 10 Mbps por Ethernet 100 | ~2 ms menos de recepción por fotograma (*cálculo*, 02-auditoria 2.3) | "Recepción del fotograma" |

## 5.7 Plantilla de resultados (rellenar en el ZTE)

Condiciones: PC ______ · GPU/codificador ______ · Sunshine v____ · Red: Ethernet 100 / Wi-Fi ____ ·
1080p60 · ___ Mbps · H.264 · TV ______ (ALLM: sí/no)

| Métrica (media / p95) | A oficial | B ULL-original | C ULL | D ULL prio normal | E ULL sin protección |
|---|---|---|---|---|---|
| Decodificación (ms) | (overlay) | | | | |
| Recepción del fotograma (ms) | — | | | | |
| Hasta el decodificador (ms) | — | | | | |
| Total cliente 1er paquete → liberación (ms) | — | | | | |
| Liberación → presentación, sistema (ms) | — | | | | |
| Jitter (ms) | — | | | | |
| FPS renderizados | | | | | |
| Fotogramas perdidos en red (%) | | | | | |
| Resincronizaciones | — | — | | | — |
| CPU proceso (% de un núcleo) | — | | | | |
| **Cristal a cristal, mediana (ms)** | | | | | |
| **Entrada → pantalla, mediana (ms)** | | | | | |
