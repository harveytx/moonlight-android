# 2. Auditoría técnica del pipeline de Moonlight Android (original 12.2)

Referencias de línea al código **original** (`b48494c`). Rutas abreviadas:
`MCDR` = `app/src/main/java/com/limelight/binding/video/MediaCodecDecoderRenderer.java`,
`MCH` = `.../MediaCodecHelper.java`, `mcc` = `app/src/main/jni/moonlight-core/moonlight-common-c/src`.

## 2.1 Recorrido de un fotograma

```
Sunshine ──UDP──► kernel (socket, SO_RCVBUF ≈ 2048 paquetes)
   │
   ▼  hilo nativo "VideoRecv" (nice 0)                         mcc/VideoStream.c:85
 recvfrom() → [descifrado AES-GCM si WAN] → RtpvAddPacket()     mcc/RtpVideoQueue.c
   │   reconstrucción FEC Reed-Solomon en cuanto hay suficientes paquetes (sin búfer de jitter)
   ▼
 VideoDepacketizer: reensamblado de NAL → decode unit (DU)      mcc/VideoDepacketizer.c:470
   │   direct submit (todos los decodificadores Codec2 "c2.*", MCH:70): en el mismo hilo
   │   si no: cola de 15 DUs + hilo "VideoDec"                   mcc/VideoDepacketizer.c:63
   ▼
 JNI BridgeDrSubmitDecodeUnit: copia a byte[] Java             callbacks.c:166/182
   ▼
 MCDR.submitDecodeUnit(): parcheo SPS, CSD, dequeueInputBuffer (bucle de 10 ms) MCDR:1127
   │   copia byte[] → buffer de entrada de MediaCodec           MCDR:1699
   ▼
 MediaCodec (hardware) ── decodificación ──►
   ▼  hilo Java "Video - Renderer" (prioridad NORM+2 ≈ nice -4) MCDR:1109
 dequeueOutputBuffer(50 ms)                                     MCDR:1023
   │  modo "menor latencia" (predeterminado): vacía la cola y libera solo el más nuevo
   │  releaseOutputBuffer(idx, System.nanoTime())               MCDR:1051
   ▼
 SurfaceView → SurfaceFlinger / HWC (overlay de vídeo) → HDMI → TV (procesado propio del TV)
```

## 2.2 Puntos donde se introduce latencia

| # | Etapa | Coste típico | ¿Se puede reducir en el cliente? | Observación |
|---|---|---|---|---|
| 1 | Captura + codificación en el host | 2–10 ms | No (es Sunshine) | Sunshine lo reporta por fotograma (*host processing latency*). Ver [07-sunshine.md](07-sunshine.md). |
| 2 | Serialización en la red | tamaño del fotograma / velocidad del enlace | Indirectamente (bitrate) | **El ZTE tiene Ethernet de 100 Mbps**: un fotograma de 1080p60 a 20 Mbps (~42 KB + 20 % FEC) tarda ~4 ms solo en el cable; a 50 Mbps ~10 ms. Ver 2.3. |
| 3 | Cola en el kernel (socket UDP) | 0 ms si el cliente va al día | Sí | Si el hilo `VideoRecv` se bloquea (decodificador lleno, CPU ocupada), los paquetes esperan aquí. **Moonlight no lo mide**: `receiveTimeUs` se toma *al leer* el paquete, no al llegar. |
| 4 | Reensamblado + FEC | < 1 ms (sin pérdidas) | No | Sin búfer de jitter: el fotograma se entrega en cuanto se puede reconstruir. Diseño ya óptimo. |
| 5 | Planificación del hilo `VideoRecv` | 0–varios ms bajo carga | **Sí** | Hilo nativo con nice 0 (hereda del hilo Java que inicia la conexión); el render tiene nice -4. En 4×Cortex-A35 con servicios de Android TV puede perder la CPU. |
| 6 | JNI + copias | ~10–50 µs | Marginal | Dos `memcpy` de ~40 KB. Despreciable frente a un intervalo de 16,7 ms. |
| 7 | Espera de buffer de entrada | 0 ms normalmente | Sí (detectar) | Si el decodificador se satura, `dequeueInputBuffer` bloquea el hilo de red (direct submit) → la cola crece en el socket (punto 3) **sin límite práctico** (2048 paquetes ≈ 70 fotogramas a 20 Mbps ≈ 1 s). |
| 8 | Decodificación hardware | 3–30 ms según SoC/firmware/modo | **Sí (configuración)** | Depende de las claves de baja latencia y del propio firmware. Moonlight usa una heurística por fabricante; en Amlogic con `FEATURE_LowLatency` solo pone `low-latency=1` (MCH:509). Nadie había medido en el ZTE qué configuración es la más rápida. |
| 9 | Espera hasta liberar (pacing) | ~0 ms en modo "menor latencia"; hasta 1 vsync + 1 fotograma en "equilibrado" | Ya es mínimo por defecto | El modo "menor latencia" ya libera el fotograma más reciente al instante. |
| 10 | SurfaceFlinger + vsync + escaneo | ~0,5–1,5 refrescos (8–25 ms a 60 Hz) | Solo con más Hz | Salida directa a overlay (sin GPU). Moonlight ya elige el modo de pantalla de mayor Hz compatible y llama a `setFrameRate`. |
| 11 | Procesado del TV | 10–100+ ms | Sí, en el TV | Moonlight ya pide ALLM (`preferMinimalPostProcessing`). Si el TV no lo soporta, activar "Modo juego" manualmente es probablemente la mejora individual más grande. |

## 2.3 Serialización en Ethernet de 100 Mbps (cálculo)

Tiempo para recibir un fotograma medio completo, a 60 FPS, con el FEC por defecto de Sunshine (20 %):

| Bitrate | Datos por fotograma | Con 20 % FEC | Tiempo a 100 Mbps | Tiempo a 1 Gbps |
|---|---|---|---|---|
| 10 Mbps | 20,8 KB | 25 KB | 2,0 ms | 0,2 ms |
| 15 Mbps | 31,3 KB | 37,5 KB | 3,0 ms | 0,3 ms |
| 20 Mbps | 41,7 KB | 50 KB | 4,0 ms | 0,4 ms |
| 30 Mbps | 62,5 KB | 75 KB | 6,0 ms | 0,6 ms |
| 50 Mbps | 104 KB | 125 KB | 10,0 ms | 1,0 ms |
| 80 Mbps | 167 KB | 200 KB | 16,0 ms (≈ todo el intervalo) | 1,6 ms |

Además el PC (normalmente a 1 Gbps) envía cada fotograma en ráfaga; el switch debe almacenarlo y
reenviarlo a 100 Mbps. Con bitrates altos, los switches domésticos con poco búfer pueden descartar
paquetes. En la app, la métrica **"recepción del fotograma"** (primer → último paquete) mide
exactamente este efecto en el dispositivo real.

## 2.4 Lo que el original no permitía saber

* Desglose por etapa: el overlay original muestra "tiempo de decodificación" calculado como
  `uptimeMillis() - (base + enqueueTime)/1000` (MCDR:1080): mezcla espera en cola, JNI y
  decodificación, con resolución de 1 ms truncada.
* Jitter de red y retardo de cola (no se usaba el PTS del host en Java).
* Tiempo de presentación real (el código existe pero está desactivado: `USE_FRAME_RENDER_TIME = false`).
* Uso de CPU, hilos, estado del decodificador y claves efectivamente aplicadas.

## 2.5 Cuellos de botella reales y mejoras viables

1. **Configuración del decodificador desconocida para el ZTE** → medirla en el dispositivo (benchmark) y aplicar la mejor solo si mejora de forma significativa.
2. **Acumulación invisible en el socket** cuando el decodificador no da abasto → detectarla (PTS del host) y resincronizar.
3. **Hilo de red con prioridad normal** → subir prioridad.
4. **Errores de recuperación ya corregidos en upstream** (120 fotogramas perdidos sin RFI) → actualizar moonlight-common-c.
5. **Bitrate frente a enlace de 100 Mbps** → perfiles y recomendaciones basadas en cálculo y en la métrica de recepción.
6. **Falta de medidas** → instrumentación por etapa, informes y comparación A/B.

Lo demás (renderizado, frame pacing por defecto, ALLM, `setFrameRate`, ausencia de búfer de jitter,
reconstrucción FEC inmediata) ya está optimizado en el original y se conserva.
