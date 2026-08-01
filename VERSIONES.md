# Cómo publicar una versión nueva de Foodxcan

La versión de la app **sale automáticamente de la etiqueta de git**. No hay que tocar ningún archivo.

## Publicar una versión

Desde la web de GitHub:

1. Ve a **Releases** → **Draft a new release**
2. En "Choose a tag" escribe la etiqueta nueva, por ejemplo `v1.1`, y pulsa **Create new tag**
3. Ponle título y descripción
4. **Publish release**

GitHub Actions compila solo, firma el APK y lo sube a esa misma release como `Foodxcan-v1.1.apk`.

## Reglas importantes

- Las etiquetas siempre con formato `v1.1`, `v1.2`, `v2.0`... (número, punto, número)
- **Nunca repitas una etiqueta ya usada**
- La app compara la versión instalada con la última etiqueta publicada: si son iguales, no avisa

## Sobre la firma

El archivo `foodxcan.keystore` de la raíz **debe estar en el repositorio**. Es la clave que firma la app.

- Si falta, la compilación falla a propósito
- Si lo borras o lo cambias, Android dará "conflicto con el paquete existente" al actualizar,
  y los usuarios tendrán que desinstalar la app y volver a instalarla

> Al ser una clave para uso propio, la contraseña está a la vista en el proyecto.
> Si algún día publicas la app en Google Play, habrá que generar una clave privada de verdad
> y guardarla como *secret* de GitHub.

## Primera instalación tras este cambio

Como el APK pasa a estar firmado con la clave nueva, **la primera vez hay que desinstalar
la versión anterior de Foodxcan** antes de instalar. A partir de ahí, las actualizaciones
se instalan encima sin problema.
