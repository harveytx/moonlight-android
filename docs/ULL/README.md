# Moonlight ULL (Ultra Low Latency) para Android TV

Versión optimizada de [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) 12.2
centrada en reducir la latencia de recepción, decodificación y presentación de vídeo **H.264**
procedente de **Sunshine**, con el **ZTE ZXV10 B866V2K (Android TV 14)** como dispositivo objetivo.

> **Estado de la validación (versión 12.2-ULL.4).**
> Compila, el APK se genera y los 32 tests unitarios pasan. Medidas reales en el ZTE
> ([09-resultados-zte.md](09-resultados-zte.md)): SoC S905Y4 confirmado. En este equipo, la
> configuración de decodificador de Moonlight original ya es óptima (H.264 1080p ≈ 2,3 ms con
> `low-latency=1`). La mejora de "standard" que se anunció en ULL.2 **no se reprodujo** y se ha
> retirado. Lo que sí está medido: el bitrate cuesta ~0,19 ms de recepción por Mbps en el Ethernet de
> 100 Mbps, y liberar el fotograma al instante nunca es peor que "equilibrado" (entre ~1 y ~8 ms mejor según la sesión).
> **Aún no hay una sesión del perfil ULL con imagen en movimiento** ni una comparación con la app
> oficial. No se declara ninguna reducción de latencia que no esté medida.

## Qué cambia respecto a Moonlight original

| Área | Cambio | Efecto esperado (a verificar en el ZTE) |
|---|---|---|
| Decodificador | El modo de baja latencia de MediaCodec se elige **por dispositivo, midiendo**: un benchmark en el propio equipo prueba 4 configuraciones y "Auto" solo se aparta de la original si la mejora se repite en todas las rondas. Corrección conocida para HEVC Amlogic (efecto diapositiva), con confirmación visual para anularla. | **Medido en el ZTE:** la configuración original ya es la mejor en H.264 (~2,3 ms); sin baja latencia, ~10 ms. |
| Acumulación de latencia | **Protección contra acumulación**: si el decodificador no da abasto y se forma cola (incluida la del socket UDP, invisible en Moonlight original), resincroniza con un IDR. | Evita que la latencia crezca sin límite en decodificadores lentos. Sin efecto cuando el decodificador va sobrado. |
| Prioridad de hilos | Los hilos nativos `VideoRecv`/`VideoDec` (antes con prioridad normal) y el hilo de render se suben a `URGENT_DISPLAY`. | Menos retrasos por planificación de CPU (p95/p99) en un Cortex-A35 con servicios de Android TV en segundo plano. |
| moonlight-common-c | Actualizado a upstream (`5d3c0ec`): incluye la corrección que evitaba **hasta 120 fotogramas perdidos** tras ciertas pérdidas de paquetes sin RFI (el H.264 de Amlogic no usa RFI). | Recuperación de pérdidas más rápida. |
| Diagnóstico | Estadísticas por etapa con reloj de nanosegundos (recepción, cola, decodificación, liberación, presentación según el sistema, jitter RFC 3550, retardo de cola estimado, CPU), informes JSON/CSV por sesión, comparador A/B, informe de capacidades del hardware. | Medidas verificables y reproducibles. |
| Perfiles | **Ultra Low Latency** (predeterminado), **Equilibrado**, **Máxima calidad** y **Moonlight original** (referencia A/B dentro de la misma app). | Configuración coherente en un clic; restaurar valores predeterminados. |
| Instalación | Identificador propio `com.limelight.ull`: se instala **junto** al Moonlight oficial para comparar. | Pruebas A/B en las mismas condiciones. |

Lo que **no** se cambió y por qué (Vulkan/OpenGL, FFmpeg, GStreamer, ExoPlayer, túnel multimedia,
eliminación de copias en JNI, recvmmsg…) está justificado en
[03-diseno-y-optimizaciones.md](03-diseno-y-optimizaciones.md#opciones-evaluadas-y-descartadas).

## Instalación rápida en el ZTE

1. Copia `dist/MoonlightULL-12.2-ULL.4.apk` a una memoria USB o usa `adb install`:
   `adb connect <ip-del-zte>:5555 && adb install -r dist/MoonlightULL-12.2-ULL.4.apk`
2. Abre **Moonlight ULL**, empareja con Sunshine como siempre.
3. **Ajustes → Ultra Low Latency → Diagnóstico y benchmark**:
   1. *Informe de capacidades del dispositivo* (guárdalo; es la información real del hardware).
   2. *Benchmark del decodificador (H.264 1080p60)* (~1 minuto; mira que el contador se mueva fluido).
4. Ajustes básicos: resolución **1080p**, **60 FPS**, bitrate según la tabla de
   [07-sunshine.md](07-sunshine.md#bitrate-recomendado-para-el-zte) (por defecto el perfil ULL usa el
   de Moonlight: 20 Mbps a 1080p60).
5. Activa *Mostrar estadísticas de rendimiento* para ver el desglose por etapa durante el juego.

## Documentación

1. [01-investigacion.md](01-investigacion.md) — Repositorios y fuentes investigados, qué aporta cada uno, licencias y decisión.
2. [02-auditoria.md](02-auditoria.md) — Auditoría del pipeline original: dónde se introduce latencia.
3. [03-diseno-y-optimizaciones.md](03-diseno-y-optimizaciones.md) — Arquitectura, optimizaciones implementadas y descartadas.
4. [04-modificaciones.md](04-modificaciones.md) — Lista de modificaciones archivo por archivo.
5. [05-benchmark.md](05-benchmark.md) — Metodología de pruebas A/B, qué se mide y qué se estima, plantilla de resultados.
6. [06-zte-b866v2k.md](06-zte-b866v2k.md) — Hardware del ZTE, limitaciones y pruebas necesarias.
7. [07-sunshine.md](07-sunshine.md) — Configuración recomendada de Sunshine.
8. [08-compilacion.md](08-compilacion.md) — Cómo compilar futuras versiones.
9. [09-resultados-zte.md](09-resultados-zte.md) — **Medidas reales del ZTE** y cambios de la versión ULL.2.

## Herramientas (`tools/`)

| Archivo | Uso |
|---|---|
| `latency-clock.html` | Reloj en milisegundos para medir latencia de extremo a extremo con cámara lenta (y entrada→pantalla con mando). |
| `collect-diagnostics.sh` | Recoge por `adb` la información del dispositivo y los informes de la app. |
| `compare-sessions.py` | Compara dos informes de sesión (y sus CSV por fotograma con test estadístico). |
| `generate-bench-clips.sh` | Regenera los clips sintéticos del benchmark. |

## Licencia

GPLv3, igual que Moonlight (ver `LICENSE.txt`). moonlight-common-c incluye la excepción de la
sección 7 para tiendas de aplicaciones. Se conservan todas las atribuciones originales.
