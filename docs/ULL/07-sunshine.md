# 7. Configuración recomendada de Sunshine

Basada en la [documentación de configuración de Sunshine](https://docs.lizardbyte.dev/projects/sunshine/latest/md_docs_2configuration.html).
Se indica el valor predeterminado y si conviene cambiarlo. Todo se ajusta en la interfaz web de
Sunshine (`https://<pc>:47990` → Configuration) o en `sunshine.conf`.

## 7.1 Red y transporte

| Opción | Predeterminado | Recomendado | Motivo |
|---|---|---|---|
| `lan_encryption_mode` | 0 (sin cifrado en LAN) | **0** | El ZTE (Cortex-A35) tendría que descifrar AES-GCM cada paquete de vídeo. En LAN no aporta nada a la latencia. |
| `wan_encryption_mode` | 1 | 1 | Solo fuera de casa. |
| `fec_percentage` | 20 | **20**; 10 solo si los informes de sesión muestran 0 % de pérdidas por Ethernet durante varias sesiones | Menos FEC = fotogramas más pequeños (en 100 Mbps, de 1,2× a 1,1× el tamaño ≈ −0,3 ms a 20 Mbps) pero menos protección ante pérdidas. |
| `ping_timeout` | 10000 | sin cambios | |

PC conectado por cable al mismo switch que el ZTE. El ZTE solo tiene Ethernet de 100 Mbps: evita
bitrates que, con FEC, se acerquen a esa velocidad (ver 7.4).

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

* El cliente ULL fuerza **H.264** en su perfil por defecto (objetivo del proyecto y decodificador
  sin el problema Amlogic HEVC). No hace falta cambiar `hevc_mode`/`av1_mode` en Sunshine.
* Si el benchmark HEVC del ZTE muestra decodificación claramente más rápida que H.264 y funciona
  fluido, se puede probar HEVC (perfil Equilibrado o códec "auto") y comparar sesiones.

## 7.4 Bitrate recomendado para el ZTE

| Conexión del ZTE | 1080p60 | Comentario |
|---|---|---|
| Ethernet 100 Mbps | **15–20 Mbps** (ULL) · 30 Mbps (Equilibrado) · ≤ 50 Mbps (Máxima calidad) | Cada 10 Mbps extra ≈ +2 ms de recepción por fotograma (*cálculo*). |
| Wi-Fi 5 (5 GHz, buena señal) | 20–40 Mbps | Mayor capacidad que el puerto de 100 Mbps pero más jitter; comparar con los informes. |

## 7.5 Host (PC)

* Resolución del escritorio igual a la del stream (1080p) para evitar escalado.
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
