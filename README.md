# Foodxcan 🍏

Escanea el código de barras de cualquier alimento y descubre al instante su calidad.

- 📷 Escáner de código de barras con la cámara
- 🎯 Score de 0 a 100 con anillo visual (verde / naranja / rojo)
- ✅ Lo bueno y ❌ lo malo del producto
- 🧪 Aditivos, edulcorantes y conservantes explicados en español, con su nivel de riesgo
- 🔄 Alternativas mejores de la misma categoría
- 💶 Precio medio orientativo

Datos nutricionales: [Open Food Facts](https://world.openfoodfacts.org) (gratuito y abierto).

## Cómo compilar (GitHub Actions)

1. Sube este proyecto a un repositorio de GitHub (rama `main`).
2. Ve a la pestaña **Actions** → workflow **Compilar APK** → **Run workflow** (o se lanza solo al hacer push).
3. Cuando termine, descarga el artefacto **Foodxcan-APK** y instala `app-debug.apk` en tu Android.

> El precio mostrado es una estimación orientativa por categoría de producto, no un precio real de tienda.

Hecho por **Xito Development**.

## Licencia

Este proyecto se distribuye bajo la licencia **MIT** (ver archivo `LICENSE`).
Puedes usarlo, modificarlo y compartirlo libremente citando a Xito Development.

Los datos de productos provienen de [Open Food Facts](https://world.openfoodfacts.org) (licencia ODbL) y el análisis con IA usa modelos gratuitos de [Pollinations](https://pollinations.ai).
