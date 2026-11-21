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
