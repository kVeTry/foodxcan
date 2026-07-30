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
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
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

// ---------- Colores ----------
val Bosque = Color(0xFF0E2A1F)
val Lima = Color(0xFFA6E22E)
val Bueno = Color(0xFF2E9E5B)
val Medio = Color(0xFFF2A93B)
val Malo = Color(0xFFE05252)
val Azul = Color(0xFF5B8DD9)

data class Palette(
    val fondo: Color, val superficie: Color, val superficie2: Color, val tinta: Color,
    val gris: Color, val header: Color, val anilloBase: Color, val borde: Color, val acento: Color
)
val LightPal = Palette(
    fondo = Color(0xFFF7FAF5), superficie = Color.White, superficie2 = Color(0xFFEFF4EC),
    tinta = Color(0xFF17251E), gris = Color(0xFF5B6B62), header = Bosque,
    anilloBase = Color(0xFFE6ECE8), borde = Color(0xFFD8E2DB), acento = Bosque
)
val DarkPal = Palette(
    fondo = Color(0xFF0D1512), superficie = Color(0xFF19241F), superficie2 = Color(0xFF223028),
    tinta = Color(0xFFECF4EF), gris = Color(0xFFA9BCB1), header = Color(0xFF101B16),
    anilloBase = Color(0xFF2A3B32), borde = Color(0xFF2E3E35), acento = Lima
)
val LocalPal = compositionLocalOf { LightPal }

fun scoreColor(s: Int) = when { s >= 70 -> Bueno; s >= 45 -> Medio; else -> Malo }
fun scoreLabel(s: Int) = when { s >= 85 -> "Excelente"; s >= 70 -> "Bueno"; s >= 45 -> "Mediocre"; else -> "Malo" }
fun riskColor(r: Risk) = when (r) { Risk.SIN_RIESGO -> Bueno; Risk.LIMITADO -> Color(0xFF7BB661); Risk.MODERADO -> Medio; Risk.ALTO -> Malo }
fun scoreColorNutri(g: String) = when (g) { "a" -> Bueno; "b" -> Color(0xFF7BB661); "c" -> Medio; "d" -> Color(0xFFEB7A34); else -> Malo }
fun levelColor(l: String?) = when (l) { "Bajo" -> Bueno; "Medio" -> Medio; "Alto" -> Malo; else -> null }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val ctx = LocalContext.current
            var dark by remember { mutableStateOf(History.isDark(ctx)) }
            val pal = if (dark) DarkPal else LightPal
            val scheme = if (dark) darkColorScheme(
                primary = Lima, onPrimary = Bosque, background = pal.fondo, onBackground = pal.tinta,
                surface = pal.superficie, onSurface = pal.tinta, outline = pal.borde,
                surfaceVariant = pal.superficie2, onSurfaceVariant = pal.gris
            ) else lightColorScheme(
                primary = Bosque, onPrimary = Color.White, background = pal.fondo, onBackground = pal.tinta,
                surface = pal.superficie, onSurface = pal.tinta, outline = pal.borde,
                surfaceVariant = pal.superficie2, onSurfaceVariant = pal.gris
            )
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
    var sound by remember { mutableStateOf(History.isSound(ctx)) }
    var sheetExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun load(code: String, fromScanner: Boolean) {
        loading = true; error = null; alternatives = emptyList(); product = null
        if (!fromScanner) { screen = "result"; sheetExpanded = true }
        scope.launch {
            val p = Repo.fetchProduct(code)
            if (p == null) error = "No encontramos el producto\n\nCodigo: $code\n\nPuedes anadirlo en la app oficial de Open Food Facts."
            else {
                product = p
                History.add(ctx, p); history = History.load(ctx)
                alternatives = Repo.fetchAlternatives(p)
            }
            loading = false
        }
    }

    when (screen) {
        "home" -> HomeScreen(dark, onToggleDark, sound,
            onToggleSound = { sound = it; History.setSound(ctx, it) },
            onScan = { screen = "scan"; sheetExpanded = false; product = null; error = null },
            onManual = { load(it, false) }, onHistory = { screen = "history" })

        "history" -> HistoryScreen(history, onOpen = { load(it, false) }, onBack = { screen = "home" },
            onClear = { History.clear(ctx); history = emptyList() })

        "scan" -> ScanScreen(
            sound = sound,
            product = product, alternatives = alternatives, loading = loading, error = error,
            expanded = sheetExpanded, onExpandedChange = { sheetExpanded = it },
            onDetected = { load(it, true) },
            onBack = { screen = "home"; product = null; error = null },
            onReset = { product = null; error = null; loading = false; sheetExpanded = false },
            onAlternative = { load(it, false) }
        )

        "result" -> Box(Modifier.fillMaxSize().background(LocalPal.current.fondo)) {
            FullDetail(product, alternatives, loading, error,
                onBack = { screen = "home" }, onScanAgain = { screen = "scan"; sheetExpanded = false; product = null; error = null },
                onAlternative = { load(it, false) })
        }
    }
}

// ==================== INICIO ====================
@Composable
fun HomeScreen(dark: Boolean, onToggleDark: (Boolean) -> Unit, sound: Boolean, onToggleSound: (Boolean) -> Unit,
               onScan: () -> Unit, onManual: (String) -> Unit, onHistory: () -> Unit) {
    val pal = LocalPal.current
    var manual by remember { mutableStateOf("") }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(1f, 1.06f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "s")

    Column(Modifier.fillMaxSize().background(pal.header)) {
        Spacer(Modifier.height(50.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Foodxcan", color = Color.White, fontSize = 36.sp, fontWeight = FontWeight.Black)
                Text("Escanea. Descubre. Come mejor.", color = Lima, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = { onToggleSound(!sound) }) {
                Icon(if (sound) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff, "Sonido", tint = Lima)
            }
            IconButton(onClick = { onToggleDark(!dark) }) {
                Icon(if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode, "Tema", tint = Lima)
            }
        }
        Spacer(Modifier.height(28.dp))
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(pal.fondo).padding(26.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Box(
                Modifier.size((174 * scale).dp).clip(CircleShape)
                    .background(if (pal == DarkPal) pal.superficie2 else pal.header)
                    .clickable { onScan() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = Lima, modifier = Modifier.size(58.dp))
                    Spacer(Modifier.height(6.dp))
                    Text("ESCANEAR", color = if (pal == DarkPal) Lima else Color.White,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(30.dp))
            OutlinedTextField(
                value = manual, onValueChange = { manual = it.filter { c -> c.isDigit() } },
                placeholder = { Text("Escribe el codigo de barras", color = pal.gris) },
                singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = pal.tinta, unfocusedTextColor = pal.tinta,
                    focusedBorderColor = pal.acento, unfocusedBorderColor = pal.borde,
                    cursorColor = pal.acento,
                    focusedContainerColor = pal.superficie, unfocusedContainerColor = pal.superficie
                ),
                trailingIcon = {
                    IconButton(onClick = { if (manual.length >= 8) onManual(manual) }) {
                        Icon(Icons.Filled.Search, null, tint = pal.acento)
                    }
                }
            )
            Spacer(Modifier.height(14.dp))
            OutlinedButton(
                onClick = onHistory, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, pal.borde),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = pal.superficie)
            ) {
                Icon(Icons.Filled.History, null, tint = pal.tinta)
                Spacer(Modifier.width(8.dp))
                Text("Historial de escaneos", color = pal.tinta)
            }
            Spacer(Modifier.weight(1f))
            Text("Alimentacion, cosmetica y mascotas", color = pal.gris, fontSize = 12.sp)
            Text("Datos: Open Food Facts", color = pal.gris, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ==================== HISTORIAL ====================
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
            val fmt = remember { SimpleDateFormat("d MMM · HH:mm", Locale("es", "ES")) }
            LazyColumn(contentPadding = PaddingValues(16.dp)) {
                items(items) { h ->
                    var visible by remember { mutableStateOf(false) }
                    LaunchedEffect(Unit) { visible = true }
                    AnimatedVisibility(visible, enter = fadeIn() + slideInVertically { it / 3 }) {
                        Card(Modifier.padding(vertical = 5.dp).fillMaxWidth().clickable { onOpen(h.barcode) },
                            colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                AsyncImage(model = h.imageUrl, contentDescription = null,
                                    modifier = Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(pal.superficie2))
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
}

@Composable
fun ScoreBadge(score: Int) {
    val c = scoreColor(score)
    Box(Modifier.size(44.dp).clip(CircleShape).background(c.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Text("$score", color = c, fontWeight = FontWeight.Bold)
    }
}

// ==================== ESCANER CON PESTANA ====================
@Composable
fun ScanScreen(
    sound: Boolean,
    product: Product?, alternatives: List<Alternative>, loading: Boolean, error: String?,
    expanded: Boolean, onExpandedChange: (Boolean) -> Unit,
    onDetected: (String) -> Unit, onBack: () -> Unit, onReset: () -> Unit, onAlternative: (String) -> Unit
) {
    val pal = LocalPal.current
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    var camera by remember { mutableStateOf<androidx.camera.core.Camera?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    val hasTorch = camera?.cameraInfo?.hasFlashUnit() == true
    val sheetVisible = loading || product != null || error != null

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val maxH = maxHeight
        val peekH = 250.dp
        val targetH = if (expanded) maxH else peekH
        val sheetH by animateDpAsState(targetH, tween(320, easing = FastOutSlowInEasing), label = "sheet")

        // ---- Camara ----
        if (hasPermission) CameraPreview(sound, paused = sheetVisible, onDetected = onDetected) { camera = it }
        else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Se necesita permiso de camara", color = Color.White)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Conceder permiso") }
        }

        // ---- Marco guia con linea animada ----
        if (!sheetVisible) {
            val trans = rememberInfiniteTransition(label = "scanline")
            val y by trans.animateFloat(0f, 1f,
                infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "y")
            Box(Modifier.align(Alignment.Center).offset(y = (-40).dp).size(290.dp, 175.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val s = 3.dp.toPx(); val l = 42.dp.toPx(); val c = Lima
                    drawLine(c, Offset(0f, 0f), Offset(l, 0f), s); drawLine(c, Offset(0f, 0f), Offset(0f, l), s)
                    drawLine(c, Offset(size.width, 0f), Offset(size.width - l, 0f), s); drawLine(c, Offset(size.width, 0f), Offset(size.width, l), s)
                    drawLine(c, Offset(0f, size.height), Offset(l, size.height), s); drawLine(c, Offset(0f, size.height), Offset(0f, size.height - l), s)
                    drawLine(c, Offset(size.width, size.height), Offset(size.width - l, size.height), s); drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - l), s)
                    drawLine(c.copy(alpha = 0.85f), Offset(10f, size.height * y), Offset(size.width - 10f, size.height * y), 2.dp.toPx())
                }
            }
            Text("Centra el codigo de barras", color = Color.White,
                modifier = Modifier.align(Alignment.Center).offset(y = 70.dp))
        }

        // ---- Barra superior: atras + flash ----
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)).clickable { onBack() },
                contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.ArrowBack, "Atras", tint = Color.White)
            }
            Spacer(Modifier.weight(1f))
            if (hasTorch) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape)
                        .background(if (torchOn) Lima else Color.Black.copy(alpha = 0.45f))
                        .clickable { torchOn = !torchOn; camera?.cameraControl?.enableTorch(torchOn) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(if (torchOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Flash",
                        tint = if (torchOn) Bosque else Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }

        // ---- Pestana inferior ----
        AnimatedVisibility(
            visible = sheetVisible,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Surface(
                Modifier.fillMaxWidth().height(sheetH)
                    .draggable(
                        orientation = Orientation.Vertical,
                        state = rememberDraggableState { delta ->
                            if (delta < -8f) onExpandedChange(true)
                            if (delta > 12f && expanded) onExpandedChange(false)
                        },
                        onDragStopped = { }
                    ),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                color = pal.fondo, shadowElevation = 16.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    // asa
                    Box(Modifier.fillMaxWidth().clickable { onExpandedChange(!expanded) }.padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(44.dp, 5.dp).clip(RoundedCornerShape(3.dp)).background(pal.borde))
                            if (!expanded && product != null) {
                                Spacer(Modifier.height(4.dp))
                                val t = rememberInfiniteTransition(label = "hint")
                                val a by t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
                                Text("Desliza para ver todo", color = pal.gris.copy(alpha = a), fontSize = 11.sp)
                            }
                        }
                    }
                    when {
                        loading -> Column(Modifier.fillMaxWidth().padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = pal.acento)
                            Spacer(Modifier.height(14.dp)); Text("Analizando producto...", color = pal.gris)
                        }
                        error != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Filled.SearchOff, null, tint = pal.gris, modifier = Modifier.size(44.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(error, textAlign = TextAlign.Center, color = pal.tinta, fontSize = 14.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onReset, colors = ButtonDefaults.buttonColors(containerColor = pal.acento),
                                shape = RoundedCornerShape(14.dp)) {
                                Text("Escanear otro", color = if (pal == DarkPal) Bosque else Color.White)
                            }
                        }
                        product != null -> {
                            if (expanded) ProductDetail(product, alternatives, onAlternative, Modifier.weight(1f))
                            else PeekCard(product) { onExpandedChange(true) }
                        }
                    }
                    if (product != null && expanded) {
                        Button(onClick = onReset, modifier = Modifier.fillMaxWidth().padding(16.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = pal.acento), shape = RoundedCornerShape(16.dp)) {
                            Icon(Icons.Filled.QrCodeScanner, null, tint = if (pal == DarkPal) Bosque else Color.White)
                            Spacer(Modifier.width(8.dp))
                            Text("Escanear otro", color = if (pal == DarkPal) Bosque else Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PeekCard(p: Product, onExpand: () -> Unit) {
    val pal = LocalPal.current
    Column(Modifier.fillMaxWidth().clickable { onExpand() }.padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(model = p.imageUrl, contentDescription = null,
                modifier = Modifier.size(66.dp).clip(RoundedCornerShape(14.dp)).background(pal.superficie))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, fontWeight = FontWeight.Bold, color = pal.tinta, fontSize = 16.sp, maxLines = 2)
                if (p.brand.isNotBlank()) Text(p.brand, color = pal.gris, fontSize = 12.sp)
            }
            Spacer(Modifier.width(10.dp))
            MiniRing(p.score)
        }
        Spacer(Modifier.height(12.dp))
        Row {
            val nBad = p.negatives.size; val nGood = p.positives.size
            if (nGood > 0) { Chip("$nGood positivos", Bueno); Spacer(Modifier.width(8.dp)) }
            if (nBad > 0) { Chip("$nBad negativos", Malo); Spacer(Modifier.width(8.dp)) }
            if (p.additives.isNotEmpty()) Chip("${p.additives.size} aditivos", Medio)
        }
    }
}

@Composable
fun MiniRing(score: Int) {
    val pal = LocalPal.current
    val anim = remember { Animatable(0f) }
    LaunchedEffect(score) { anim.animateTo(score / 100f, tween(900, easing = FastOutSlowInEasing)) }
    val c = scoreColor(score)
    Box(contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(62.dp)) {
            val st = 7.dp.toPx()
            drawArc(pal.anilloBase, -90f, 360f, false, style = Stroke(st, cap = StrokeCap.Round),
                size = Size(size.width - st, size.height - st), topLeft = Offset(st / 2, st / 2))
            drawArc(c, -90f, 360f * anim.value, false, style = Stroke(st, cap = StrokeCap.Round),
                size = Size(size.width - st, size.height - st), topLeft = Offset(st / 2, st / 2))
        }
        Text("${(anim.value * 100).toInt()}", color = c, fontWeight = FontWeight.Black, fontSize = 17.sp)
    }
}

// ==================== CAMARA ====================
@Composable
fun CameraPreview(sound: Boolean, paused: Boolean, onDetected: (String) -> Unit,
                  onCameraReady: (androidx.camera.core.Camera) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val pausedRef = remember { mutableStateOf(paused) }
    pausedRef.value = paused
    val soundRef = remember { mutableStateOf(sound) }
    soundRef.value = sound
    var handled by remember { mutableStateOf(false) }
    if (!paused) handled = false

    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx)
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()
        // Confirmacion: el mismo codigo debe leerse 3 veces y ser valido
        var lastCode: String? = null
        var repeats = 0
        ProcessCameraProvider.getInstance(ctx).apply {
            addListener({
                val provider = get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media != null && !pausedRef.value && !handled) {
                        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(img)
                            .addOnSuccessListener { codes ->
                                val v = codes.firstOrNull()?.rawValue
                                if (v != null && v.all { it.isDigit() } && BarcodeUtils.isValid(v)) {
                                    if (v == lastCode) repeats++ else { lastCode = v; repeats = 1 }
                                    if (repeats >= 3 && !handled) {
                                        handled = true
                                        if (soundRef.value) Beeper.beep()
                                        onDetected(v)
                                    }
                                } else if (v != lastCode) { lastCode = null; repeats = 0 }
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

// ==================== DETALLE COMPLETO (pantalla aparte) ====================
@Composable
fun FullDetail(product: Product?, alternatives: List<Alternative>, loading: Boolean, error: String?,
               onBack: () -> Unit, onScanAgain: () -> Unit, onAlternative: (String) -> Unit) {
    val pal = LocalPal.current
    Box(Modifier.fillMaxSize().background(pal.fondo)) {
        when {
            loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = pal.acento)
                Spacer(Modifier.height(16.dp)); Text("Analizando producto...", color = pal.gris)
            }
            error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.SearchOff, null, tint = pal.gris, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(error, textAlign = TextAlign.Center, color = pal.tinta)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onScanAgain, colors = ButtonDefaults.buttonColors(containerColor = pal.acento)) {
                    Text("Escanear", color = if (pal == DarkPal) Bosque else Color.White)
                }
            }
            product != null -> ProductDetail(product, alternatives, onAlternative, Modifier.fillMaxSize(), topPad = 64.dp)
        }
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = pal.tinta) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onScanAgain) { Icon(Icons.Filled.QrCodeScanner, null, tint = pal.tinta) }
        }
    }
}

// ==================== FICHA DEL PRODUCTO ====================
@Composable
fun ProductDetail(p: Product, alternatives: List<Alternative>, onAlternative: (String) -> Unit,
                  modifier: Modifier = Modifier, topPad: Dp = 4.dp) {
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    var aiState by remember(p.barcode) { mutableStateOf<AiRepo.Result?>(null) }
    var aiLoading by remember(p.barcode) { mutableStateOf(false) }

    LazyColumn(modifier, contentPadding = PaddingValues(top = topPad, bottom = 24.dp)) {
        item {
            Row(Modifier.padding(horizontal = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(model = p.imageUrl, contentDescription = null,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(16.dp)).background(pal.superficie))
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(p.name, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = pal.tinta)
                    if (p.brand.isNotBlank()) Text(p.brand, color = pal.gris, fontSize = 13.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (p.quantity.isNotBlank()) { Text(p.quantity, color = pal.gris, fontSize = 12.sp); Spacer(Modifier.width(8.dp)) }
                        Chip(p.source.label, Azul)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            ScoreRing(p.score)
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.Center) {
                p.nutriScore?.let { Chip("Nutri ${it.uppercase()}", scoreColorNutri(it)); Spacer(Modifier.width(6.dp)) }
                p.novaGroup?.let { Chip("NOVA $it", if (it >= 4) Malo else if (it <= 2) Bueno else Medio); Spacer(Modifier.width(6.dp)) }
                p.ecoScore?.let { Chip("Eco ${it.uppercase()}", scoreColorNutri(it)) }
            }
            p.estimatedPrice?.let {
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { Chip("Precio medio: $it", Azul) }
            }
            Spacer(Modifier.height(20.dp))
        }

        // ---- IA ----
        item {
            Column(Modifier.padding(horizontal = 22.dp)) {
                Button(
                    onClick = {
                        aiLoading = true; aiState = null
                        scope.launch { aiState = AiRepo.analyze(p); aiLoading = false }
                    },
                    enabled = !aiLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = pal.acento, disabledContainerColor = pal.superficie2),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    val txtColor = if (pal == DarkPal) Bosque else Color.White
                    if (aiLoading) {
                        CircularProgressIndicator(color = txtColor, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp)); Text("Analizando...", color = pal.tinta)
                    } else {
                        Icon(Icons.Filled.AutoAwesome, null, tint = txtColor, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp)); Text("Analisis con IA", color = txtColor, fontWeight = FontWeight.SemiBold)
                    }
                }
                AnimatedVisibility(aiState != null, enter = fadeIn() + expandVertically()) {
                    aiState?.let { st ->
                        Column {
                            Spacer(Modifier.height(12.dp))
                            Card(colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    when (st) {
                                        is AiRepo.Result.Ok -> {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.AutoAwesome, null, tint = pal.acento, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("Analisis de la IA", fontWeight = FontWeight.Bold, color = pal.tinta)
                                            }
                                            Spacer(Modifier.height(10.dp))
                                            AiFormatted(st.text)
                                            Spacer(Modifier.height(8.dp))
                                            Text("Generado por IA. Puede contener errores.", color = pal.gris, fontSize = 11.sp)
                                        }
                                        is AiRepo.Result.Error -> Text(st.message, color = Malo, fontSize = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        // ---- Negativos ----
        if (p.negatives.isNotEmpty()) {
            item { SectionTitle("Lo malo (${p.negatives.size})", Icons.Filled.ThumbDown, Malo) }
            items(p.negatives) { PointRow(it, Malo) }
            item { Spacer(Modifier.height(14.dp)) }
        }
        // ---- Positivos ----
        if (p.positives.isNotEmpty()) {
            item { SectionTitle("Lo bueno (${p.positives.size})", Icons.Filled.ThumbUp, Bueno) }
            items(p.positives) { PointRow(it, Bueno) }
            item { Spacer(Modifier.height(14.dp)) }
        }

        // ---- Informacion nutricional ----
        if (p.nutrients.isNotEmpty()) {
            item {
                SectionTitle("Informacion nutricional", Icons.Filled.Restaurant, pal.acento)
                Text(if (p.servingSize != null) "Por 100 g/ml · Racion: ${p.servingSize}" else "Por 100 g/ml",
                    color = pal.gris, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 2.dp))
                Spacer(Modifier.height(6.dp))
                Card(Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        p.nutrients.forEachIndexed { i, n ->
                            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Text(n.name, color = if (n.name.startsWith("de ")) pal.gris else pal.tinta,
                                    fontSize = if (n.name.startsWith("de ")) 13.sp else 14.sp,
                                    modifier = Modifier.weight(1f).padding(start = if (n.name.startsWith("de ")) 12.dp else 0.dp))
                                levelColor(n.level)?.let { lc ->
                                    Chip(n.level!!, lc); Spacer(Modifier.width(8.dp))
                                }
                                Text("${fmtN(n.value)} ${n.unit}", color = pal.tinta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                            if (i < p.nutrients.size - 1) Divider(color = pal.borde, thickness = 0.5.dp)
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // ---- Ingredientes ----
        p.ingredientsText?.let { ing ->
            item {
                var open by remember { mutableStateOf(false) }
                SectionTitle("Ingredientes", Icons.Filled.Article, pal.acento)
                Card(Modifier.padding(horizontal = 22.dp).fillMaxWidth().clickable { open = !open },
                    colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(14.dp).animateContentSize()) {
                        Text(ing, color = pal.gris, fontSize = 13.sp, lineHeight = 19.sp,
                            maxLines = if (open) 100 else 3)
                        Spacer(Modifier.height(6.dp))
                        Text(if (open) "Ver menos" else "Ver todo", color = pal.acento, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // ---- Etiquetas ----
        if (p.labels.isNotEmpty()) {
            item {
                SectionTitle("Etiquetas y certificaciones", Icons.Filled.Verified, pal.acento)
                Column(Modifier.padding(horizontal = 22.dp)) {
                    p.labels.chunked(2).forEach { fila ->
                        Row(Modifier.padding(vertical = 3.dp)) {
                            fila.forEach { l -> Chip(l, Bueno); Spacer(Modifier.width(6.dp)) }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        // ---- Aditivos ----
        if (p.additives.isNotEmpty()) {
            item { SectionTitle("Aditivos (${p.additives.size})", Icons.Filled.Science, pal.acento) }
            items(p.additives) { AdditiveCard(it) }
            item { Spacer(Modifier.height(16.dp)) }
        }

        // ---- Alternativas ----
        item { SectionTitle("Alternativas mejores", Icons.Filled.SwapHoriz, pal.acento) }
        if (alternatives.isEmpty()) {
            item {
                Text("No encontramos alternativas mejores en esta categoria.",
                    color = pal.gris, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 26.dp, vertical = 4.dp))
            }
        } else items(alternatives) { a ->
            Card(Modifier.padding(horizontal = 22.dp, vertical = 5.dp).fillMaxWidth().clickable { onAlternative(a.barcode) },
                colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(model = a.imageUrl, contentDescription = null,
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(12.dp)).background(pal.superficie2))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(a.name, fontWeight = FontWeight.SemiBold, color = pal.tinta, maxLines = 2, fontSize = 14.sp)
                        if (a.brand.isNotBlank()) Text(a.brand, color = pal.gris, fontSize = 12.sp)
                    }
                    a.nutriScore?.let { Chip(it.uppercase(), scoreColorNutri(it)) }
                }
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Text("Codigo: ${p.barcode}", color = pal.gris, fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp), textAlign = TextAlign.Center)
        }
    }
}

fun fmtN(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else String.format("%.1f", v)

@Composable
fun ScoreRing(score: Int) {
    val pal = LocalPal.current
    val anim = remember { Animatable(0f) }
    LaunchedEffect(score) { anim.animateTo(score / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    val color = scoreColor(score)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(162.dp)) {
                val st = 15.dp.toPx()
                drawArc(pal.anilloBase, -90f, 360f, false, style = Stroke(st, cap = StrokeCap.Round),
                    size = Size(size.width - st, size.height - st), topLeft = Offset(st / 2, st / 2))
                drawArc(color, -90f, 360f * anim.value, false, style = Stroke(st, cap = StrokeCap.Round),
                    size = Size(size.width - st, size.height - st), topLeft = Offset(st / 2, st / 2))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(anim.value * 100).toInt()}", fontSize = 42.sp, fontWeight = FontWeight.Black, color = color)
                Text(scoreLabel(score), color = pal.gris, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
fun Chip(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SectionTitle(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color) {
    Row(Modifier.padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(19.dp))
        Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = LocalPal.current.tinta)
    }
}

@Composable
fun PointRow(text: String, color: Color) {
    val pal = LocalPal.current
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    AnimatedVisibility(visible, enter = fadeIn(tween(300)) + slideInHorizontally { -it / 6 }) {
        Row(Modifier.padding(horizontal = 26.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Text(text, color = pal.tinta, fontSize = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
fun AiFormatted(text: String) {
    val pal = LocalPal.current
    val titulos = mapOf(
        "RESUMEN" to pal.tinta, "EN QUE AYUDA" to Bueno, "EN QUÉ AYUDA" to Bueno,
        "EN QUE PERJUDICA" to Malo, "EN QUÉ PERJUDICA" to Malo,
        "ALTERNATIVAS MEJORES" to Azul, "ALTERNATIVAS" to Azul, "CONSEJO" to Medio
    )
    Column {
        text.split("\n").filter { it.isNotBlank() }.forEach { linea ->
            val l = linea.trim().removeSurrounding("**")
            val titulo = titulos.keys.firstOrNull { l.uppercase().startsWith("$it:") || l.uppercase().startsWith("**$it") }
            if (titulo != null) {
                val resto = l.substringAfter(":").trim()
                Spacer(Modifier.height(9.dp))
                Text(titulo.replace("Ó", "O"), color = titulos[titulo]!!, fontSize = 12.sp,
                    fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                if (resto.isNotBlank()) Text(resto.removeSuffix("**"), color = pal.gris, fontSize = 14.sp, lineHeight = 20.sp)
            } else {
                Text(l.removePrefix("- ").removePrefix("* "), color = pal.gris, fontSize = 14.sp, lineHeight = 20.sp)
            }
        }
    }
}

@Composable
fun AdditiveCard(a: AdditiveInfo) {
    val pal = LocalPal.current
    var expanded by remember { mutableStateOf(false) }
    val c = riskColor(a.risk)
    val rot by animateFloatAsState(if (expanded) 180f else 0f, label = "rot")
    Card(Modifier.padding(horizontal = 22.dp, vertical = 5.dp).fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${a.code} · ${a.name}", fontWeight = FontWeight.SemiBold, color = pal.tinta, fontSize = 14.sp)
                    Text("${a.category} · ${a.risk.label}", color = c, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Icon(Icons.Filled.ExpandMore, null, tint = pal.gris, modifier = Modifier.rotate(rot))
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(a.description, color = pal.gris, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}
