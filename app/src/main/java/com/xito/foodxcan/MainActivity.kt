package com.xito.foodxcan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.xito.foodxcan.data.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

val Bosque = Color(0xFF0E2A1F)
val Lima = Color(0xFFA6E22E)
val Bueno = Color(0xFF2E9E5B)
val Medio = Color(0xFFF2A93B)
val Malo = Color(0xFFE05252)

data class Palette(
    val fondo: Color, val superficie: Color, val tinta: Color,
    val gris: Color, val header: Color, val anilloBase: Color
)
val LightPal = Palette(Color(0xFFF7FAF5), Color.White, Color(0xFF17251E), Color(0xFF5B6B62), Bosque, Color(0xFFE6ECE8))
val DarkPal = Palette(Color(0xFF0B1712), Color(0xFF152A20), Color(0xFFEAF3ED), Color(0xFF9DB0A6), Color(0xFF081009), Color(0xFF25392F))
val LocalPal = compositionLocalOf { LightPal }

fun scoreColor(s: Int) = when { s >= 70 -> Bueno; s >= 45 -> Medio; else -> Malo }
fun scoreLabel(s: Int) = when { s >= 85 -> "Excelente"; s >= 70 -> "Bueno"; s >= 45 -> "Mediocre"; else -> "Malo" }
fun riskColor(r: Risk) = when (r) { Risk.SIN_RIESGO -> Bueno; Risk.LIMITADO -> Color(0xFF7BB661); Risk.MODERADO -> Medio; Risk.ALTO -> Malo }
fun scoreColorNutri(g: String) = when (g) { "a" -> Bueno; "b" -> Color(0xFF7BB661); "c" -> Medio; "d" -> Color(0xFFEB7A34); else -> Malo }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            var dark by remember { mutableStateOf(History.isDark(ctx)) }
            val pal = if (dark) DarkPal else LightPal
            val scheme = if (dark)
                darkColorScheme(primary = Lima, background = pal.fondo, surface = pal.superficie)
            else
                lightColorScheme(primary = Bosque, background = pal.fondo, surface = pal.superficie)
            CompositionLocalProvider(LocalPal provides pal) {
                MaterialTheme(colorScheme = scheme) {
                    App(dark = dark, onToggleDark = { dark = it; History.setDark(ctx, it) })
                }
            }
        }
    }
}

@Composable
fun App(dark: Boolean, onToggleDark: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    var screen by remember { mutableStateOf("home") }
    var product by remember { mutableStateOf<Product?>(null) }
    var alternatives by remember { mutableStateOf<List<Alternative>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(History.load(ctx)) }
    val scope = rememberCoroutineScope()

    fun lookup(code: String) {
        loading = true; error = null; alternatives = emptyList(); product = null; screen = "result"
        scope.launch {
            val p = Repo.fetchProduct(code)
            if (p == null) { error = "No encontramos este producto en Open Food Facts.\n\nCodigo: $code\n\nPuedes anadirlo tu mismo desde la app oficial de Open Food Facts para ayudar a la comunidad." }
            else {
                product = p
                History.add(ctx, p); history = History.load(ctx)
                alternatives = Repo.fetchAlternatives(p)
            }
            loading = false
        }
    }

    when (screen) {
        "home" -> HomeScreen(dark, onToggleDark, onScan = { screen = "scan" }, onManual = { lookup(it) }, onHistory = { screen = "history" }, onSettings = { screen = "settings" })
        "scan" -> ScannerScreen(onDetected = { lookup(it) }, onBack = { screen = "home" })
        "history" -> HistoryScreen(history, onOpen = { lookup(it) }, onBack = { screen = "home" }, onClear = { History.clear(ctx); history = emptyList() })
        "settings" -> SettingsScreen(onBack = { screen = "home" })
        "result" -> ResultScreen(product, alternatives, loading, error, onBack = { screen = "home" }, onScanAgain = { screen = "scan" }, onAlternative = { lookup(it) })
    }
}

@Composable
fun HomeScreen(dark: Boolean, onToggleDark: (Boolean) -> Unit, onScan: () -> Unit, onManual: (String) -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    val pal = LocalPal.current
    var manual by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(pal.header)) {
        Spacer(Modifier.height(50.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Foodxcan", color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Black)
                Text("Escanea. Descubre. Come mejor.", color = Lima, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = { onToggleDark(!dark) }) {
                Icon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, "Cambiar tema", tint = Lima)
            }
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, "Ajustes", tint = Lima)
            }
        }
        Spacer(Modifier.height(32.dp))
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)).background(pal.fondo).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))
            Box(Modifier.size(180.dp).clip(CircleShape).background(pal.header).clickable { onScan() }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = Lima, modifier = Modifier.size(60.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("ESCANEAR", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }
            Spacer(Modifier.height(32.dp))
            OutlinedTextField(
                value = manual, onValueChange = { manual = it.filter { c -> c.isDigit() } },
                placeholder = { Text("Escribe el codigo de barras", color = pal.gris) },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                trailingIcon = { IconButton(onClick = { if (manual.length >= 8) onManual(manual) }) { Icon(Icons.Filled.Search, null, tint = pal.tinta) } }
            )
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = onHistory, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Icon(Icons.Filled.History, null, tint = pal.tinta)
                Spacer(Modifier.width(8.dp))
                Text("Historial de escaneos", color = pal.tinta)
            }
            Spacer(Modifier.weight(1f))
            Text("Datos: Open Food Facts - Precios orientativos", color = pal.gris, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
fun HistoryScreen(items: List<HistoryItem>, onOpen: (String) -> Unit, onBack: () -> Unit, onClear: () -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxSize().background(pal.fondo)) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = pal.tinta) }
            Text("Historial", color = pal.tinta, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (items.isNotEmpty()) IconButton(onClick = onClear) { Icon(Icons.Filled.DeleteOutline, "Vaciar", tint = Malo) }
        }
        if (items.isEmpty()) {
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.Inventory2, null, tint = pal.gris, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("Aun no has escaneado nada", color = pal.gris)
            }
        } else {
            val fmt = remember { SimpleDateFormat("d MMM - HH:mm", Locale("es", "ES")) }
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                items(items) { h ->
                    Card(Modifier.padding(vertical = 5.dp).fillMaxWidth().clickable { onOpen(h.barcode) },
                        colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(model = h.imageUrl, contentDescription = null, modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(pal.fondo))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(h.name, fontWeight = FontWeight.SemiBold, color = pal.tinta, maxLines = 1)
                                Text(fmt.format(Date(h.time)), color = pal.gris, fontSize = 12.sp)
                            }
                            ScoreBadge(h.score)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ScoreBadge(score: Int) {
    val c = scoreColor(score)
    Box(Modifier.size(44.dp).clip(CircleShape).background(c.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        Text("$score", color = c, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    var key by remember { mutableStateOf(History.getApiKey(ctx)) }
    var saved by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().background(pal.fondo)) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = pal.tinta) }
            Text("Ajustes", color = pal.tinta, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(24.dp)) {
            Text("Análisis con IA", color = pal.tinta, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("Para que la IA busque en internet e informe sobre cada producto, necesitas una API key de Anthropic. Se guarda solo en tu móvil.",
                color = pal.gris, fontSize = 13.sp, lineHeight = 18.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it.trim(); saved = false },
                placeholder = { Text("sk-ant-...", color = pal.gris) },
                label = { Text("API key de Anthropic") },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { History.setApiKey(ctx, key); saved = true },
                colors = ButtonDefaults.buttonColors(containerColor = pal.header),
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
            ) { Text(if (saved) "Guardado ✓" else "Guardar", color = Color.White) }
            Spacer(Modifier.height(24.dp))
            Text("¿Cómo consigo la clave?", color = pal.tinta, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Entra en console.anthropic.com, crea una cuenta, añade unos euros de saldo y genera una API key en la sección \"API Keys\". Cada análisis cuesta muy pocos céntimos.",
                color = pal.gris, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
fun ScannerScreen(onDetected: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    val hasTorch = camera?.cameraInfo?.hasFlashUnit() == true

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) CameraPreview(onDetected) { camera = it }
        else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Se necesita permiso de camara", color = Color.White)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Conceder permiso") }
        }
        Box(Modifier.align(Alignment.Center).size(280.dp, 170.dp).clip(RoundedCornerShape(20.dp))) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 3.dp.toPx(); val l = 40.dp.toPx(); val c = Lima
                drawLine(c, Offset(0f, 0f), Offset(l, 0f), s); drawLine(c, Offset(0f, 0f), Offset(0f, l), s)
                drawLine(c, Offset(size.width, 0f), Offset(size.width - l, 0f), s); drawLine(c, Offset(size.width, 0f), Offset(size.width, l), s)
                drawLine(c, Offset(0f, size.height), Offset(l, size.height), s); drawLine(c, Offset(0f, size.height), Offset(0f, size.height - l), s)
                drawLine(c, Offset(size.width, size.height), Offset(size.width - l, size.height), s); drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - l), s)
            }
        }
        Text("Apunta al codigo de barras", color = Color.White, modifier = Modifier.align(Alignment.Center).offset(y = 120.dp))
        IconButton(onClick = onBack, modifier = Modifier.padding(16.dp).statusBarsPadding()) { Icon(Icons.Filled.ArrowBack, null, tint = Color.White) }

        // Boton de flash
        if (hasTorch) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp)
                    .size(64.dp).clip(CircleShape)
                    .background(if (torchOn) Lima else Color.White.copy(alpha = 0.2f))
                    .clickable { torchOn = !torchOn; camera?.cameraControl?.enableTorch(torchOn) },
                contentAlignment = Alignment.Center
            ) {
                Icon(if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Flash",
                    tint = if (torchOn) Bosque else Color.White, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
fun CameraPreview(onDetected: (String) -> Unit, onCameraReady: (androidx.camera.core.Camera) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var handled by remember { mutableStateOf(false) }
    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx)
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()
        ProcessCameraProvider.getInstance(ctx).apply {
            addListener({
                val provider = get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media != null && !handled) {
                        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(img)
                            .addOnSuccessListener { codes ->
                                codes.firstOrNull()?.rawValue?.let { v ->
                                    if (!handled && v.length >= 8 && v.all { it.isDigit() }) { handled = true; onDetected(v) }
                                }
                            }
                            .addOnCompleteListener { proxy.close() }
                    } else proxy.close()
                }
                provider.unbindAll()
                val cam = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                onCameraReady(cam)
            }, ContextCompat.getMainExecutor(ctx))
        }
        previewView
    }, modifier = Modifier.fillMaxSize())
}

@Composable
fun ResultScreen(product: Product?, alternatives: List<Alternative>, loading: Boolean, error: String?,
    onBack: () -> Unit, onScanAgain: () -> Unit, onAlternative: (String) -> Unit) {
    val pal = LocalPal.current
    Box(Modifier.fillMaxSize().background(pal.fondo)) {
        when {
            loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = pal.tinta)
                Spacer(Modifier.height(16.dp)); Text("Analizando producto...", color = pal.gris)
            }
            error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.SearchOff, null, tint = pal.gris, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(error, textAlign = TextAlign.Center, color = pal.tinta)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onScanAgain, colors = ButtonDefaults.buttonColors(containerColor = pal.header)) { Text("Escanear otro") }
            }
            product != null -> ProductDetail(product, alternatives, onAlternative)
        }
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = pal.tinta) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onScanAgain) { Icon(Icons.Filled.QrCodeScanner, null, tint = pal.tinta) }
        }
    }
}

@Composable
fun ProductDetail(p: Product, alternatives: List<Alternative>, onAlternative: (String) -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var aiState by remember(p.barcode) { mutableStateOf<AiRepo.Result?>(null) }
    var aiLoading by remember(p.barcode) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 64.dp, bottom = 32.dp)) {
        item {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p.imageUrl != null) AsyncImage(model = p.imageUrl, contentDescription = null, modifier = Modifier.size(92.dp).clip(RoundedCornerShape(16.dp)).background(pal.superficie))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = pal.tinta)
                    if (p.brand.isNotBlank()) Text(p.brand, color = pal.gris, fontSize = 14.sp)
                    if (p.quantity.isNotBlank()) Text(p.quantity, color = pal.gris, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            ScoreRing(p.score)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center) {
                p.nutriScore?.let { Chip("Nutri-Score ${it.uppercase()}", scoreColorNutri(it)) }
                p.novaGroup?.let { Spacer(Modifier.width(8.dp)); Chip("NOVA $it", if (it >= 4) Malo else if (it == 1) Bueno else Medio) }
                p.estimatedPrice?.let { Spacer(Modifier.width(8.dp)); Chip("~ $it", Color(0xFF4A6FA5)) }
            }
            Spacer(Modifier.height(20.dp))

            // --- Analisis con IA ---
            Column(Modifier.padding(horizontal = 24.dp)) {
                Button(
                    onClick = {
                        aiLoading = true; aiState = null
                        scope.launch {
                            aiState = AiRepo.analyze(History.getApiKey(ctx), p)
                            aiLoading = false
                        }
                    },
                    enabled = !aiLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = pal.header),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    if (aiLoading) {
                        CircularProgressIndicator(color = Lima, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Buscando en internet...", color = Color.White)
                    } else {
                        Icon(Icons.Filled.AutoAwesome, null, tint = Lima, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text("Analisis con IA", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
                aiState?.let { st ->
                    Spacer(Modifier.height(12.dp))
                    Card(colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            when (st) {
                                is AiRepo.Result.Ok -> {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Filled.AutoAwesome, null, tint = pal.tinta, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(8.dp))
                                        Text("Analisis de la IA", fontWeight = FontWeight.Bold, color = pal.tinta)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                    Text(st.text, color = pal.gris, fontSize = 14.sp, lineHeight = 20.sp)
                                    Spacer(Modifier.height(8.dp))
                                    Text("Generado por IA con busqueda web. Puede contener errores.", color = pal.gris, fontSize = 11.sp)
                                }
                                is AiRepo.Result.Error -> {
                                    Text(st.message, color = Malo, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
        if (p.negatives.isNotEmpty()) {
            item { SectionTitle("Lo malo", Icons.Filled.ThumbDown, Malo) }
            items(p.negatives) { PointRow(it, Malo) }
            item { Spacer(Modifier.height(16.dp)) }
        }
        if (p.positives.isNotEmpty()) {
            item { SectionTitle("Lo bueno", Icons.Filled.ThumbUp, Bueno) }
            items(p.positives) { PointRow(it, Bueno) }
            item { Spacer(Modifier.height(16.dp)) }
        }
        if (p.negatives.isEmpty() && p.positives.isEmpty()) {
            item {
                Text("Este producto tiene pocos datos nutricionales en la base de datos.", color = pal.gris, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 28.dp))
                Spacer(Modifier.height(16.dp))
            }
        }
        if (p.additives.isNotEmpty()) {
            item { SectionTitle("Aditivos (${p.additives.size})", Icons.Filled.Science, pal.tinta) }
            items(p.additives) { AdditiveCard(it) }
            item { Spacer(Modifier.height(16.dp)) }
        }
        item { SectionTitle("Alternativas mejores", Icons.Filled.SwapHoriz, pal.tinta) }
        if (alternatives.isEmpty()) {
            item { Text("No encontramos alternativas mejores en esta categoria.", color = pal.gris, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 4.dp)) }
        } else items(alternatives) { a ->
            Card(Modifier.padding(horizontal = 24.dp, vertical = 6.dp).fillMaxWidth().clickable { onAlternative(a.barcode) },
                colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = a.imageUrl, contentDescription = null, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(pal.fondo))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.SemiBold, color = pal.tinta, maxLines = 2)
                        if (a.brand.isNotBlank()) Text(a.brand, color = pal.gris, fontSize = 12.sp)
                    }
                    a.nutriScore?.let { Chip(it.uppercase(), scoreColorNutri(it)) }
                }
            }
        }
    }
}

@Composable
fun ScoreRing(score: Int) {
    val pal = LocalPal.current
    val anim = remember { Animatable(0f) }
    LaunchedEffect(score) { anim.animateTo(score / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    val color = scoreColor(score)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(170.dp)) {
                val stroke = 16.dp.toPx()
                drawArc(pal.anilloBase, -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round), size = Size(size.width - stroke, size.height - stroke), topLeft = Offset(stroke / 2, stroke / 2))
                drawArc(color, -90f, 360f * anim.value, false, style = Stroke(stroke, cap = StrokeCap.Round), size = Size(size.width - stroke, size.height - stroke), topLeft = Offset(stroke / 2, stroke / 2))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(anim.value * 100).toInt()}", fontSize = 44.sp, fontWeight = FontWeight.Black, color = color)
                Text("/ 100 - ${scoreLabel(score)}", color = pal.gris, fontSize = 13.sp)
            }
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = LocalPal.current.tinta)
    }
}

@Composable
fun PointRow(text: String, color: Color) {
    Row(Modifier.padding(horizontal = 28.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, color = LocalPal.current.tinta, fontSize = 14.sp)
    }
}

@Composable
fun AdditiveCard(a: AdditiveInfo) {
    val pal = LocalPal.current
    var expanded by remember { mutableStateOf(false) }
    val c = riskColor(a.risk)
    Card(Modifier.padding(horizontal = 24.dp, vertical = 5.dp).fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${a.code} - ${a.name}", fontWeight = FontWeight.SemiBold, color = pal.tinta, fontSize = 14.sp)
                    Text("${a.category} - ${a.risk.label}", color = c, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = pal.gris)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(a.description, color = pal.gris, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}
