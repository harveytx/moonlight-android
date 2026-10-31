# 7. Configuración recomendada de Sunshine

Basada en la [documentación de configuración de Sunshine](https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2configuration.html).
Se indica el valor predeterminado y si conviene cambiarlo. Todo se ajusta en la interfaz web de
Sunshine (`https://<pc>:47990` → Configuration) o en `sunshine.conf`.

## 7.1 Red y transporte

| Opción | Predeterminado | Recomendado | Motivo |
|---|---|---|---|
| `lan_encryption_mode` | 0 (sin cifrado en LAN) | **0** | El ZTE (Cortex-A35) tendría que descifrar AES-GCM cada paquete de vídeo. En LAN no aporta nada a la latencia. |
| `wan_encryption_mode` | 1 | 1 | Solo fuera de casa. |
| `fec_percentage` | 20 | **20**; 10 solo si los informes de sesión muestran 0 % de pérdidas por Ethernet durante varias sesiones | Sunshine **resta el FEC del bitrate configurado** (ver 7.4): con el mismo bitrate, 10 % deja un 12,5 % más de bits al vídeo (mejor imagen) con el mismo uso de red, pero protege menos ante pérdidas y los fotogramas en el techo tardan ese 12,5 % más en llegar. |
| `ping_timeout` | 10000 | sin cambios | |

PC conectado por cable al mismo switch que el ZTE. El ZTE solo tiene Ethernet de 100 Mbps: evita
bitrates que se acerquen a esa velocidad (el FEC ya va incluido en el bitrate configurado; ver 7.4).

## 7.2 Codificador

### NVIDIA (NVENC)

| Opción | Predeterminado | Recomendado | Motivo |
|---|---|---|---|
| `nvenc_preset` | 1 (P1, el más rápido) | **1** | Menor latencia de codificación. |
| `nvenc_twopass` | quarter_res | **quarter_res** | Desactivarlo puede generar picos de bitrate y pérdidas: en un enlace de 100 Mbps es peor. |
| `nvenc_vbv_increase` | 0 | **0** | Mantener el VBV de un fotograma evita ráfagas que el puerto de 100 Mbps tardaría más en recibir. |
| `nvenc_latency_over_power` | enabled | **enabled** | Fuerza el modo de alto rendimiento de la GPU. |
| `nvenc_realtime_hags` | enabled | enabled | Prioridad de GPU en tiempo real con HAGS activado en Windows. |
| `nvenc_spatial_aq` | disabled | disabled (o enabled a bitrates bajos si se ve bloqueo en zonas planas) | Calidad, no latencia. |

### AMD (AMF)

| Opción | Predeterminado | Recomendado |
|---|---|---|
| `amd_usage` | ultralowlatency | **ultralowlatency** |
| `amd_rc` | vbr_latency | **vbr_latency** (probar `cbr` si hay pérdidas en ráfaga por el enlace de 100 Mbps) |
| `amd_preanalysis` | disabled | **disabled** (añade latencia) |
| `amd_vbaq` | enabled | enabled |

### Intel (QuickSync)

`qsv_preset`: **veryfast** o **faster** (predeterminado `medium`: más lento).

### Software (x264)

`sw_preset`: **superfast** (predeterminado) o ultrafast si la CPU va justa; `sw_tune`: **zerolatency**
(predeterminado). `min_threads` 2 (predeterminado).

## 7.3 Códec

* El cliente ULL fuerza **H.264** en su perfil por defecto (objetivo del proyecto). No hace falta
  cambiar `hevc_mode`/`av1_mode` en Sunshine.
* **Medido en el ZTE (ULL.5):** HEVC decodifica igual de rápido que H.264 (sesiones: 2,2–2,4 ms
  frente a 2,3–2,6 ms de mediana) y el codificador del PC tarda ~0,9 ms más (latencia de host
  5,6 frente a 4,7 ms con el mismo escritorio). HEVC comprime mejor que H.264, así que **para mejorar
  la imagen es la primera opción, antes que subir el bitrate** (ver 7.7). Cuánto mejor con la GPU
  concreta no está medido aquí.
* AV1: el ZTE tiene decodificador AV1, pero anuncia un máximo de 40 Mbps, Moonlight no lo usa en
  "auto" (sin decodificadores AV1 en su lista) y el PC necesitaría una GPU con codificador AV1. No
  probado.

## 7.4 Bitrate recomendado para el ZTE

**Cómo usa Sunshine el bitrate** (código fuente: `src/rtsp.cpp`, `src/nvenc/nvenc_base.cpp`,
`src/video.cpp`). El bitrate configurado en Moonlight es el total: Sunshine le resta el FEC
(× (100 − `fec_percentage`) / 100), el audio (0,2 Mbps en estéreo) y 0,5 Mbps, y da el resto al
codificador. El codificador trabaja en CBR con un búfer (VBV) de **un fotograma**: ningún fotograma
puede pasar de *bitrate del codificador / FPS*. Ese es el **techo por fotograma**. Con imagen
estática los fotogramas son pequeños y el techo da igual. En cambios grandes (desplazar texto, mover
ventanas, escenas complejas de juego) el codificador llega al techo, y solo ahí se nota más bitrate.

| Bitrate configurado (1080p60, FEC 20 %) | Codificador | Techo por fotograma | Recepción de un fotograma en el techo por el Ethernet del ZTE (*estimada con la medida de 0,086 ms/kB*) |
|---|---|---|---|
| 20 Mbps | 15,3 Mbps | 32 kB | 2,7 ms |
| 30 Mbps | 23,3 Mbps | 49 kB | 4,2 ms |
| 50 Mbps | 39,3 Mbps | 82 kB | 7,0 ms |
| 80 Mbps | 63,3 Mbps | 132 kB | 11,3 ms |

| Conexión del ZTE | 1080p60 | Comentario |
|---|---|---|
| Ethernet 100 Mbps | **15–20 Mbps** (ULL) · 30 Mbps (Equilibrado) · ≤ 50 Mbps (Máxima calidad) | Cada 10 Mbps configurados añaden ~1,4 ms de recepción a los fotogramas en el techo (*medido*: 0,086 ms/kB, ver 9.9). A 80 Mbps las escenas complejas usan ~80 % del enlace. |
| Wi-Fi 5 (5 GHz, buena señal) | 20–40 Mbps | Mayor capacidad que el puerto de 100 Mbps pero más jitter; comparar con los informes. |

Desde 12.2-ULL.6, el overlay detallado y el informe de sesión muestran el tamaño de los fotogramas
frente a ese techo ("en el techo X %"). Si es bajo, subir el bitrate no cambia la imagen.

## 7.5 Host (PC)

* Resolución del escritorio igual a la del stream (1080p) para evitar escalado. Si el escritorio
  es mayor (1440p, 4K), Sunshine reduce la captura a la resolución del stream y el texto pierde
  nitidez. Compruébalo con `tools/image-test.html` (7.7).
* Frecuencia del monitor/escritorio ≥ FPS del stream (60 Hz o múltiplos).
* En los juegos: límite de FPS ligeramente por encima de 60 o desbloqueado, V-Sync desactivado o
  "fast sync"; el V-Sync del juego añade latencia antes de la captura.
* Windows: HAGS activado si se usa `nvenc_realtime_hags`; plan de energía de alto rendimiento.
* `capture`: el método automático (DDX en Windows) salvo problemas concretos.

## 7.6 Cliente (Moonlight ULL en el ZTE)

* Perfil **Ultra Low Latency**, 1080p, 60 FPS, bitrate de la tabla 7.4.
* Ejecutar antes el **benchmark del decodificador** para que "Auto" use la configuración medida.
* TV en **Modo juego** si el informe dice que no soporta ALLM.
* Solo probar 90/120 FPS si el benchmark indica que el decodificador lo sostiene y el TV acepta ese
  modo; streamear a más FPS que el refresco del TV puede reducir latencia (fotogramas más recientes)
  a costa de más carga y ancho de banda.

## 7.7 Mejorar la imagen (sin perder de vista la latencia)

De mayor a menor beneficio esperado por milisegundo, con los datos del ZTE (detalle en 9.9):

1. **Que nada escale la imagen.** En un monitor de PC el escalado es lo que más ablanda el texto.
   La cadena es: escritorio del PC → resolución del stream (Sunshine) → salida del ZTE (solo ofrece
   720p, 1080p y 4K, no 1440p) → panel del monitor. Con `tools/image-test.html` a pantalla completa
   en el PC, las líneas de 1 px salen nítidas en el ZTE solo si ningún paso escala.
2. **HEVC en lugar de H.264** con el mismo bitrate: misma decodificación medida, +0,9 ms en el PC.
3. **Ajustes de calidad del codificador** (en el PC). El coste aparece como "Procesamiento en host"
   del informe de sesión, así que se puede medir:
   * `nvenc_preset` 1 → 2–4: más calidad por bit, más tiempo de codificación en la GPU.
   * `nvenc_twopass` `full_res`: mejor reparto de bits que `quarter_res`, algo más lento.
   * `nvenc_spatial_aq` `enabled`: menos bloques en zonas planas; puede restar detalle en texturas.
   * `fec_percentage` 10: +12,5 % de bits para el vídeo con el mismo bitrate (ver 7.1).
   * No subir `nvenc_vbv_increase`: permite fotogramas por encima del techo, que por el puerto de
     100 Mbps tardan proporcionalmente más en llegar.
4. **Subir el bitrate** solo si el overlay muestra muchos fotogramas "en el techo", y aceptando el
   coste de la tabla 7.4 (80 Mbps: hasta ~11 ms por fotograma en el techo, frente a ~7 ms a 50).
5. **Rango completo** (opción de Moonlight): puede dar algo más de gradación; activarlo solo si los
   escalones de negro del patrón siguen distinguiéndose (si no, el ZTE lo interpreta mal).

No es posible en este ZTE: croma 4:4:4 (ningún decodificador lo anuncia; el texto de color siempre
saldrá algo borroso con 4:2:0).
