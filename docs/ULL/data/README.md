# Datos medidos en dispositivos reales

Una carpeta por dispositivo y, dentro, **una carpeta por versión de la app** que generó los datos,
igual que la app los guarda desde 12.2-ULL.5 (`ull-sessions/<versión>/`).

| Carpeta | Contenido |
|---|---|
| `zte-b866v2k/12.2-ULL.1/` | Primeros informes (30-10-2026, 22:27–22:33) |
| `zte-b866v2k/12.2-ULL.2/` | Segunda ronda (30-10-2026, 23:01–23:08) |
| `zte-b866v2k/12.2-ULL.3/` | Tercera y cuarta ronda (31-10-2026, 07:52–08:24), con CSV por fotograma |
| `zte-b866v2k/12.2-ULL.5/` | Quinta ronda (31-10-2026, 08:39–08:52): 12 sesiones del escritorio, una de 50 Mbps durante 161 s |
| `zte-b866v2k/12.2-ULL.6/` | Sexta ronda (31-10-2026, 09:48–10:01): 11 sesiones con tamaño de fotograma frente al techo (20, 50 y 80 Mbps) |

Los informes de capacidades y de benchmark anteriores a ULL.5 no llevaban la versión dentro; se
asignaron por la hora y el formato del informe. En las sesiones se ha ocultado el nombre del PC
(`"host": "(redacted)"`). Análisis en [../09-resultados-zte.md](../09-resultados-zte.md).

Comparar dos sesiones:

```
tools/compare-sessions.py docs/ULL/data/zte-b866v2k/12.2-ULL.3/session-20261031-081845-custom.json \
                          docs/ULL/data/zte-b866v2k/12.2-ULL.3/session-20261031-082047-ull.json
```
