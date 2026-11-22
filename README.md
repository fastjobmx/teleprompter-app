# Teleprompter

Teleprompter que graba con la cámara del celular. Pegas el guion, ajustas velocidad y tamaño de letra y grabas mientras el texto sube. El texto no sale en el video.

## Descargar el APK (Android)

Ve a **Releases** → la versión más reciente → toca **Teleprompter.apk**.
La primera vez Android te pedirá permitir instalar apps desde tu navegador.

Los videos se guardan en **Galería › Movies/Teleprompter**.

## Estructura

- `web/` — la app (HTML). También funciona como app web instalable (PWA) si la subes a Netlify.
- `app/` — envoltorio Android (WebView) con permisos de cámara y guardado en galería.
- `.github/workflows/build-apk.yml` — compila el APK en cada cambio a `main` y lo publica en Releases.

## Funciones (v2)

- **Sigue mi voz**: el texto avanza cuando hablas y se detiene en las pausas. Reconocimiento en español 100 % en el celular (Vosk), sin internet.
- **Ventana de texto ajustable**: tamaño, color, interlineado, posición, alto, ancho, oscuridad del fondo y línea de lectura. Se arrastra desde su borde.
- **Subtítulos automáticos**: se generan mientras grabas en modo voz, usando las palabras exactas de tu guion. Se exportan dentro del video o como `.srt`.
- **Quitar silencios**: corta las pausas (suave, normal o agresivo) al exportar.

## Créditos

- [Vosk](https://alphacephei.com/vosk/) y [vosk-browser](https://github.com/ccoreilly/vosk-browser) (Apache 2.0) para el reconocimiento de voz.
- Ideas de [smart-teleprompter-android](https://github.com/xdadrm/smart-teleprompter-android) (filtro de ruido), [CueCam](https://github.com/lustsazeus-lab/CueCam) y [OpenCut Mobile](https://github.com/Inexra/opencut-mobile) (exportación con Media3).

## v3.1

- **Subtítulos estilo redes**: Karaoke, Caja, Impacto, Cómic, Neón, Limpio y Minimal, con la palabra que dices resaltada, color de acento, tamaño y palabras por pantalla.
- **Micrófono externo**: detecta receptores USB-C (DJI, Hollyland, Boya, Rode…) y Bluetooth, y graba sin filtros de ruido para que suene natural.
- Fuentes: Montserrat, Anton y Bangers (SIL Open Font License, ver `web/fonts/`).
