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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.withTimeoutOrNull
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
    var alerts by remember { mutableStateOf<List<FoodAlert>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var history by remember { mutableStateOf(History.load(ctx)) }
    var sound by remember { mutableStateOf(History.isSound(ctx)) }
    var update by remember { mutableStateOf<UpdateInfo?>(null) }

    // La clave guardada se pasa al motor de IA al arrancar
    LaunchedEffect(Unit) {
        AiRepo.groqKey = History.getGroqKey(ctx)
        AiRepo.nvidiaKey = History.getNvidiaKey(ctx)
        AiRepo.nvidiaModel = History.getNvidiaModel(ctx)
    }

    // Comprueba si hay una version nueva publicada en GitHub
    LaunchedEffect(Unit) {
        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "1.0"
        } catch (e: Exception) { "1.0" }
        val u = Updates.check(version)
        if (u != null && u.version != History.getSkippedVersion(ctx)) update = u
    }
    var sheetExpanded by remember { mutableStateOf(false) }
    var guess by remember { mutableStateOf<AiRepo.Guess?>(null) }
    var guessLoading by remember { mutableStateOf(false) }
    var lastCode by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun load(code: String, fromScanner: Boolean) {
        // Si ya estamos mostrando o cargando ese mismo codigo, no se repite la busqueda
        if (fromScanner && code == lastCode && (loading || guessLoading || product != null)) return
        loading = true; error = null; alternatives = emptyList(); product = null
        alerts = emptyList(); guess = null; guessLoading = false; lastCode = code
        if (!fromScanner) { screen = "result"; sheetExpanded = true }
        scope.launch {
            val p = Repo.fetchProduct(code)
            if (p == null) {
                loading = false
                guessLoading = true
                val res = withTimeoutOrNull(220_000L) { AiRepo.identify(code) }
                guessLoading = false
                val g = res?.first
                val err = res?.second ?: "La IA ha tardado demasiado en responder."
                if (g != null && g.name.lowercase() != "desconocido") guess = g
                else error = "No encontramos el producto ni la IA supo identificarlo.\n\nCodigo: $code\n\n$err"
            } else {
                product = p
                History.add(ctx, p); history = History.load(ctx)
                loading = false
                alerts = Alerts.matchFor(p)
                alternatives = Repo.fetchAlternatives(p)
            }
        }
    }

    fun acceptGuess() {
        val g = guess ?: return
        val p = AiRepo.guessToProduct(lastCode, g)
        product = p; guess = null
        History.add(ctx, p); history = History.load(ctx)
        sheetExpanded = true
    }

    update?.let { u ->
        UpdateDialog(u,
            onDismiss = { update = null },
            onSkip = { History.setSkippedVersion(ctx, u.version); update = null })
    }

    when (screen) {
        "home" -> HomeScreen(dark, onToggleDark, sound,
            onToggleSound = { sound = it; History.setSound(ctx, it) },
            onScan = { screen = "scan"; sheetExpanded = false; product = null; error = null },
            onManual = { load(it, false) }, onHistory = { screen = "history" },
            onSettings = { screen = "settings" })

        "settings" -> SettingsScreen(onBack = { screen = "home" })

        "history" -> HistoryScreen(history, onOpen = { load(it, false) }, onBack = { screen = "home" },
            onClear = { History.clear(ctx); history = emptyList() })

        "scan" -> ScanScreen(
            sound = sound,
            product = product, alternatives = alternatives, loading = loading, error = error,
            alerts = alerts, guess = guess, guessLoading = guessLoading, barcode = lastCode,
            onAcceptGuess = { acceptGuess() },
            onRetry = { val c = lastCode; lastCode = ""; if (c.isNotBlank()) load(c, false) },
            onOpenSettings = { screen = "settings" },
            expanded = sheetExpanded, onExpandedChange = { sheetExpanded = it },
            onDetected = { load(it, true) },
            onBack = { screen = "home"; product = null; error = null; guess = null },
            onReset = { product = null; error = null; loading = false; sheetExpanded = false; guess = null; alerts = emptyList(); lastCode = "" },
            onAlternative = { load(it, false) }
        )

        "result" -> Box(Modifier.fillMaxSize().background(LocalPal.current.fondo)) {
            FullDetail(product, alternatives, alerts, loading, error,
                onBack = { screen = "home" }, onScanAgain = { screen = "scan"; sheetExpanded = false; product = null; error = null },
                onAlternative = { load(it, false) })
        }
    }
}

@Composable
fun UpdateDialog(u: UpdateInfo, onDismiss: () -> Unit, onSkip: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    fun abrir(url: String) {
        try {
            ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) { }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = pal.superficie,
        icon = { Icon(Icons.Filled.SystemUpdate, null, tint = pal.acento, modifier = Modifier.size(32.dp)) },
        title = { Text("Nueva version disponible", color = pal.tinta, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Foodxcan ${u.version} ya esta publicada.", color = pal.tinta, fontSize = 14.sp)
                if (u.notes.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(u.notes, color = pal.gris, fontSize = 12.sp, lineHeight = 17.sp, maxLines = 10)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { abrir(u.apkUrl ?: u.url); onDismiss() }) {
                Text(if (u.apkUrl != null) "Descargar" else "Ver release", color = pal.acento, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onSkip) { Text("Omitir", color = pal.gris) }
                TextButton(onClick = onDismiss) { Text("Luego", color = pal.gris) }
            }
        }
    )
}

// ==================== INICIO ====================
@Composable
fun HomeScreen(dark: Boolean, onToggleDark: (Boolean) -> Unit, sound: Boolean, onToggleSound: (Boolean) -> Unit,
               onScan: () -> Unit, onManual: (String) -> Unit, onHistory: () -> Unit, onSettings: () -> Unit) {
    val pal = LocalPal.current
    val ctxHome = LocalContext.current
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
            IconButton(onClick = onSettings) {
                Icon(Icons.Filled.Settings, "Ajustes", tint = Lima)
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
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    try {
                        ctxHome.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://apps-xito.unocerobits.com")))
                    } catch (e: Exception) { }
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, pal.borde),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = pal.superficie)
            ) {
                Icon(Icons.Filled.Apps, null, tint = Lima)
                Spacer(Modifier.width(8.dp))
                Text("Mas apps de Xito", color = pal.tinta)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Filled.OpenInNew, null, tint = pal.gris, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.weight(1f))
            Text("Alimentacion, cosmetica y mascotas", color = pal.gris, fontSize = 12.sp)
            Text("Datos: Open Food Facts", color = pal.gris, fontSize = 11.sp)
            Text("Xito Development", color = pal.gris, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
                    run {
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

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    var groq by remember { mutableStateOf(History.getGroqKey(ctx)) }
    var nvidia by remember { mutableStateOf(History.getNvidiaKey(ctx)) }
    var modelo by remember { mutableStateOf(History.getNvidiaModel(ctx)) }
    var guardado by remember { mutableStateOf(false) }
    var probando by remember { mutableStateOf(false) }
    var resultado by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun guardar() {
        History.setGroqKey(ctx, groq); AiRepo.groqKey = groq
        History.setNvidiaKey(ctx, nvidia); AiRepo.nvidiaKey = nvidia
        History.setNvidiaModel(ctx, modelo); AiRepo.nvidiaModel = modelo
        guardado = true
    }

    Column(Modifier.fillMaxSize().background(pal.fondo)) {
        Row(Modifier.statusBarsPadding().fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = pal.tinta) }
            Text("Ajustes", color = pal.tinta, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Column(Modifier.padding(horizontal = 22.dp).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = pal.acento, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Analisis con IA", color = pal.tinta, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text("Sin ninguna clave la app usa un servicio gratuito que suele fallar. " +
                 "Con una clave (basta una de las dos, ambas son gratis y sin tarjeta) el analisis va rapido y fiable.",
                color = pal.gris, fontSize = 13.sp, lineHeight = 19.sp)

            Spacer(Modifier.height(18.dp))
            KeyField("Clave de Groq", "gsk_...", groq) { groq = it; guardado = false; resultado = null }
            Spacer(Modifier.height(6.dp))
            Text("Rapido y estable. Unas 1.000 consultas al dia.", color = pal.gris, fontSize = 11.sp)

            Spacer(Modifier.height(16.dp))
            KeyField("Clave de NVIDIA", "nvapi-...", nvidia) { nvidia = it; guardado = false; resultado = null }
            Spacer(Modifier.height(6.dp))
            Text("100+ modelos gratis, con un limite de 40 peticiones por minuto.",
                color = pal.gris, fontSize = 11.sp)

            if (nvidia.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Text("Modelo de NVIDIA", color = pal.tinta, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                AiRepo.NVIDIA_CATALOG.forEach { (id, etiqueta) ->
                    val elegido = modelo == id || (modelo.isBlank() && id == AiRepo.NVIDIA_CATALOG.first().first)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (elegido) pal.acento.copy(alpha = 0.14f) else pal.superficie)
                            .clickable { modelo = id; guardado = false; resultado = null }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (elegido) Icons.Filled.RadioButtonChecked else Icons.Filled.RadioButtonUnchecked,
                            null, tint = if (elegido) pal.acento else pal.gris, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(etiqueta, color = pal.tinta, fontSize = 13.sp,
                                fontWeight = if (elegido) FontWeight.SemiBold else FontWeight.Normal)
                            Text(id, color = pal.gris, fontSize = 10.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            Row {
                Button(
                    onClick = { guardar(); resultado = null },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = pal.acento),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(if (guardado) "Guardado" else "Guardar",
                        color = if (pal == DarkPal) Bosque else Color.White, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(10.dp))
                OutlinedButton(
                    onClick = {
                        guardar(); probando = true; resultado = null
                        scope.launch {
                            val r = withTimeoutOrNull(110_000L) { AiRepo.test() }
                            resultado = when {
                                r == null -> "Sin respuesta: ha tardado demasiado."
                                r.startsWith("OK") -> "Funciona. ${r.removePrefix("OK: ").take(60)}"
                                else -> r
                            }
                            probando = false
                        }
                    },
                    enabled = !probando, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, pal.borde)
                ) {
                    if (probando) {
                        var seg by remember(probando) { mutableStateOf(0) }
                        LaunchedEffect(probando) {
                            while (true) { kotlinx.coroutines.delay(1000); seg++ }
                        }
                        CircularProgressIndicator(color = pal.acento, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (seg < 5) "Probando" else "${seg}s", color = pal.tinta)
                    } else Text("Probar", color = pal.tinta)
                }
            }
            resultado?.let { r ->
                Spacer(Modifier.height(12.dp))
                val ok = r.startsWith("Funciona")
                Card(colors = CardDefaults.cardColors(containerColor = (if (ok) Bueno else Malo).copy(alpha = 0.14f)),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp)) {
                        Icon(if (ok) Icons.Filled.CheckCircle else Icons.Filled.ErrorOutline, null,
                            tint = if (ok) Bueno else Malo, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(r, color = pal.tinta, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                }
            }

            Spacer(Modifier.height(26.dp))
            Text("Como conseguir una clave gratis", color = pal.tinta, fontWeight = FontWeight.Bold, fontSize = 15.sp)

            Spacer(Modifier.height(12.dp))
            ProviderGuide(
                titulo = "Groq (recomendado)",
                pasos = listOf(
                    "Entra en console.groq.com y crea cuenta.",
                    "Abre API Keys en el menu lateral.",
                    "Pulsa Create API Key y copiala (solo se ve una vez).",
                    "Pegala arriba y pulsa Guardar."
                ),
                url = "https://console.groq.com/keys", etiqueta = "Abrir console.groq.com"
            )
            Spacer(Modifier.height(18.dp))
            ProviderGuide(
                titulo = "NVIDIA Build",
                pasos = listOf(
                    "Entra en build.nvidia.com y crea cuenta.",
                    "Elige un modelo del catalogo (por ejemplo Llama 3.3).",
                    "Pulsa Get API Key y copiala.",
                    "Pegala arriba y pulsa Guardar."
                ),
                url = "https://build.nvidia.com", etiqueta = "Abrir build.nvidia.com"
            )

            Spacer(Modifier.height(16.dp))
            Text("Las claves se guardan solo en tu movil. Si pones las dos, se usa Groq y NVIDIA queda de respaldo.",
                color = pal.gris, fontSize = 11.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
fun KeyField(etiqueta: String, pista: String, valor: String, onChange: (String) -> Unit) {
    val pal = LocalPal.current
    OutlinedTextField(
        value = valor, onValueChange = { onChange(it.trim()) },
        placeholder = { Text(pista, color = pal.gris) },
        label = { Text(etiqueta, color = pal.gris) },
        singleLine = true, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = pal.tinta, unfocusedTextColor = pal.tinta,
            focusedBorderColor = pal.acento, unfocusedBorderColor = pal.borde, cursorColor = pal.acento,
            focusedContainerColor = pal.superficie, unfocusedContainerColor = pal.superficie
        )
    )
}

@Composable
fun ProviderGuide(titulo: String, pasos: List<String>, url: String, etiqueta: String) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(titulo, color = pal.tinta, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.height(8.dp))
            pasos.forEachIndexed { i, paso ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Box(Modifier.size(20.dp).clip(CircleShape).background(pal.superficie2), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", color = pal.acento, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(paso, color = pal.gris, fontSize = 12.sp, lineHeight = 18.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    try {
                        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)))
                    } catch (e: Exception) { }
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = pal.superficie2)
            ) {
                Icon(Icons.Filled.OpenInNew, null, tint = pal.acento, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(8.dp))
                Text(etiqueta, color = pal.tinta, fontSize = 13.sp)
            }
        }
    }
}

// ==================== ESCANER CON PESTANA ====================
@Composable
fun ScanScreen(
    sound: Boolean,
    product: Product?, alternatives: List<Alternative>, loading: Boolean, error: String?,
    alerts: List<FoodAlert>, guess: AiRepo.Guess?, guessLoading: Boolean, barcode: String, onAcceptGuess: () -> Unit,
    onRetry: () -> Unit, onOpenSettings: () -> Unit,
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
    val sheetVisible = loading || guessLoading || product != null || error != null || guess != null

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val maxH = maxHeight
        val peekH = 250.dp
        val targetH = if (expanded) maxH else peekH
        val sheetH by animateDpAsState(targetH, tween(320, easing = FastOutSlowInEasing), label = "sheet")

        // ---- Camara ----
        // La camara sigue escaneando en standby: solo se pausa con la ficha desplegada
        if (hasPermission) CameraPreview(sound, paused = expanded, onDetected = onDetected) { camera = it }
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
                Modifier.fillMaxWidth().height(sheetH),
                shape = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp),
                color = pal.fondo, shadowElevation = 16.dp
            ) {
                Column(Modifier.fillMaxSize()) {
                    // asa: se arrastra acumulando el recorrido y se decide al soltar
                    val arrastre = remember { mutableStateOf(0f) }
                    Box(
                        Modifier.fillMaxWidth()
                            .draggable(
                                orientation = Orientation.Vertical,
                                state = rememberDraggableState { d -> arrastre.value += d },
                                onDragStopped = {
                                    val d = arrastre.value
                                    arrastre.value = 0f
                                    if (d < -60f) onExpandedChange(true)
                                    else if (d > 60f) onExpandedChange(false)
                                }
                            )
                            .clickable { onExpandedChange(!expanded) }
                            .padding(vertical = 12.dp),
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
                        guessLoading -> Column(Modifier.fillMaxWidth().padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            var seg by remember(guessLoading) { mutableStateOf(0) }
                            LaunchedEffect(guessLoading) {
                                while (true) { kotlinx.coroutines.delay(1000); seg++ }
                            }
                            CircularProgressIndicator(color = pal.acento)
                            Spacer(Modifier.height(14.dp))
                            Text("No esta en la base de datos", color = pal.tinta, fontWeight = FontWeight.SemiBold)
                            Text("Preguntando a la IA por el codigo $barcode.\nPuede tardar hasta un minuto.",
                                color = pal.gris, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp)
                            if (seg >= 8) {
                                Spacer(Modifier.height(6.dp))
                                Text("${seg}s · algunos modelos tardan cerca de un minuto",
                                    color = pal.gris, fontSize = 11.sp, textAlign = TextAlign.Center)
                            }
                        }
                        guess != null -> GuessCard(guess, barcode, onAcceptGuess, onReset)
                        error != null -> Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(Icons.Filled.SearchOff, null, tint = pal.gris, modifier = Modifier.size(40.dp))
                            Spacer(Modifier.height(10.dp))
                            Text(error, textAlign = TextAlign.Center, color = pal.tinta, fontSize = 13.sp, lineHeight = 18.sp)
                            Spacer(Modifier.height(16.dp))
                            Row {
                                OutlinedButton(onClick = onRetry, shape = RoundedCornerShape(14.dp),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, pal.borde)) {
                                    Icon(Icons.Filled.Refresh, null, tint = pal.tinta, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp)); Text("Reintentar", color = pal.tinta, fontSize = 13.sp)
                                }
                                Spacer(Modifier.width(8.dp))
                                Button(onClick = onReset, colors = ButtonDefaults.buttonColors(containerColor = pal.acento),
                                    shape = RoundedCornerShape(14.dp)) {
                                    Text("Escanear otro", color = if (pal == DarkPal) Bosque else Color.White, fontSize = 13.sp)
                                }
                            }
                            if (AiRepo.groqKey.isBlank() && AiRepo.nvidiaKey.isBlank()) {
                                Spacer(Modifier.height(12.dp))
                                TextButton(onClick = onOpenSettings) {
                                    Icon(Icons.Filled.AutoAwesome, null, tint = pal.acento, modifier = Modifier.size(15.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Configurar IA (gratis)", color = pal.acento, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        product != null -> {
                            if (expanded) ProductDetail(product, alternatives, alerts, onAlternative, Modifier.weight(1f))
                            else PeekCard(product, alerts) { onExpandedChange(true) }
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
fun GuessCard(g: AiRepo.Guess, barcode: String, onAccept: () -> Unit, onReject: () -> Unit) {
    val pal = LocalPal.current
    val confColor = when (g.confidence.lowercase()) { "alta" -> Bueno; "media" -> Medio; else -> Malo }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoAwesome, null, tint = pal.acento, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Producto no encontrado", fontWeight = FontWeight.Bold, color = pal.tinta, fontSize = 15.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text("No esta en Open Food Facts. La IA cree que es:", color = pal.gris, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = pal.superficie), shape = RoundedCornerShape(16.dp)) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(g.name, fontWeight = FontWeight.Bold, color = pal.tinta, fontSize = 16.sp)
                    if (g.brand.isNotBlank()) Text(g.brand, color = pal.gris, fontSize = 13.sp)
                    if (g.category.isNotBlank()) Text(g.category, color = pal.gris, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    Chip("Confianza ${g.confidence}", confColor)
                }
                MiniRing(g.score)
            }
        }
        if (g.note.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(g.note, color = pal.gris, fontSize = 11.sp, lineHeight = 15.sp)
        }
        Spacer(Modifier.height(14.dp))
        Text("Es correcto?", color = pal.tinta, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        Row {
            Button(onClick = onAccept, modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Bueno), shape = RoundedCornerShape(14.dp)) {
                Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Si, analizar", color = Color.White)
            }
            Spacer(Modifier.width(10.dp))
            OutlinedButton(onClick = onReject, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, pal.borde)) {
                Text("No", color = pal.tinta)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
fun PeekCard(p: Product, alerts: List<FoodAlert>, onExpand: () -> Unit) {
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
        if (alerts.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Malo)
                .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (alerts.any { it.matchLevel >= 2 }) "ALERTA: no consumir sin comprobar el lote"
                     else "Alerta sanitaria de este tipo de producto",
                     color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
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
    // Estos objetos NO se recrean al recomponer: evitan re-disparos en bucle
    val gate = remember { ScanGate() }
    val pausedRef = remember { mutableStateOf(paused) }
    val soundRef = remember { mutableStateOf(sound) }
    pausedRef.value = paused
    soundRef.value = sound

    AndroidView(factory = { ctx ->
        val previewView = PreviewView(ctx)
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient()
        ProcessCameraProvider.getInstance(ctx).apply {
            addListener({
                val provider = get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media != null && !pausedRef.value) {
                        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(img)
                            .addOnSuccessListener { codes ->
                                val v = codes.firstOrNull()?.rawValue
                                if (v != null && BarcodeUtils.isValid(v) && gate.offer(v)) {
                                    if (soundRef.value) Beeper.beep()
                                    onDetected(v)
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

// ==================== DETALLE COMPLETO (pantalla aparte) ====================
@Composable
fun FullDetail(product: Product?, alternatives: List<Alternative>, alerts: List<FoodAlert>, loading: Boolean, error: String?,
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
            product != null -> ProductDetail(product, alternatives, alerts, onAlternative, Modifier.fillMaxSize(), topPad = 64.dp)
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
fun ProductDetail(p: Product, alternatives: List<Alternative>, alerts: List<FoodAlert>, onAlternative: (String) -> Unit,
                  modifier: Modifier = Modifier, topPad: Dp = 4.dp) {
    val pal = LocalPal.current
    val scope = rememberCoroutineScope()
    var aiState by remember(p.barcode) { mutableStateOf<AiRepo.Result?>(null) }
    var aiLoading by remember(p.barcode) { mutableStateOf(false) }
    val hazards = remember(p.barcode) { Alerts.hazards(p) }

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
            val exacta = alerts.any { it.matchLevel >= 2 }
            if (alerts.isNotEmpty() || hazards.any { it.severity >= 3 }) {
                Spacer(Modifier.height(14.dp))
                DangerBanner(alerts.isNotEmpty(), exacta)
            }
            if (p.aiEstimated) {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Medio.copy(alpha = 0.15f)), shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.AutoAwesome, null, tint = Medio, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Datos estimados por IA, no verificados", color = Medio, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            ScoreRing(p.score)
            Spacer(Modifier.height(12.dp))
            ScoreBar(p.score)
            Spacer(Modifier.height(12.dp))
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
                        scope.launch {
                            aiState = withTimeoutOrNull(240_000L) { AiRepo.analyze(p) }
                                ?: AiRepo.Result.Error("La IA ha tardado demasiado. Intentalo de nuevo.")
                            aiLoading = false
                        }
                    },
                    enabled = !aiLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = pal.acento, disabledContainerColor = pal.superficie2),
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                ) {
                    val txtColor = if (pal == DarkPal) Bosque else Color.White
                    if (aiLoading) {
                        var seg by remember(aiLoading) { mutableStateOf(0) }
                        LaunchedEffect(aiLoading) {
                            while (true) { kotlinx.coroutines.delay(1000); seg++ }
                        }
                        CircularProgressIndicator(color = txtColor, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(if (seg < 8) "Analizando..." else "Analizando... ${seg}s (hasta 1 min)", color = pal.tinta)
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
                                        is AiRepo.Result.Error -> Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Filled.CloudOff, null, tint = Malo, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(8.dp))
                                                Text("No se pudo analizar", color = Malo, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                            }
                                            Spacer(Modifier.height(6.dp))
                                            Text(st.message, color = pal.gris, fontSize = 13.sp, lineHeight = 18.sp)
                                            Spacer(Modifier.height(10.dp))
                                            OutlinedButton(
                                                onClick = {
                                                    aiLoading = true; aiState = null
                                                    scope.launch {
                                                        aiState = withTimeoutOrNull(240_000L) { AiRepo.analyze(p) }
                                                            ?: AiRepo.Result.Error("La IA ha tardado demasiado. Intentalo de nuevo.")
                                                        aiLoading = false
                                                    }
                                                },
                                                shape = RoundedCornerShape(12.dp),
                                                border = androidx.compose.foundation.BorderStroke(1.dp, pal.borde)
                                            ) {
                                                Icon(Icons.Filled.Refresh, null, tint = pal.tinta, modifier = Modifier.size(16.dp))
                                                Spacer(Modifier.width(6.dp))
                                                Text("Reintentar", color = pal.tinta, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }

        // ---- Seguridad alimentaria ----
        if (alerts.isNotEmpty() || hazards.isNotEmpty()) {
            item { SectionTitle("Seguridad alimentaria", Icons.Filled.HealthAndSafety, Malo) }
        }
        if (hazards.isNotEmpty()) {
            items(hazards) { h -> HazardCard(h) }
        }
        if (alerts.isNotEmpty()) {
            item {
                Text("Alertas oficiales de AESAN relacionadas con este producto:",
                    color = pal.gris, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
            }
            items(alerts) { a -> AlertCard(a) }
            item {
                Text("AESAN no publica codigos de barras: el cruce se hace por marca y nombre del producto. Confirma siempre el lote en la alerta oficial.",
                    color = pal.gris, fontSize = 11.sp, lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp))
            }
        }
        if (alerts.isNotEmpty() || hazards.isNotEmpty()) {
            item { Spacer(Modifier.height(16.dp)) }
        }

        // ---- Negativos ----
        if (p.negatives.isNotEmpty()) {
            item { SectionTitle("Negativos", Icons.Filled.ThumbDown, Malo) }
            items(p.negatives) { InsightRow(it) }
            item { Spacer(Modifier.height(14.dp)) }
        }
        // ---- Positivos ----
        if (p.positives.isNotEmpty()) {
            item { SectionTitle("Positivos", Icons.Filled.ThumbUp, Bueno) }
            items(p.positives) { InsightRow(it) }
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
fun DangerBanner(esAlerta: Boolean, exacta: Boolean) {
    val pulse = rememberInfiniteTransition(label = "danger")
    val a by pulse.animateFloat(if (exacta) 0.65f else 0.8f, 1f,
        infiniteRepeatable(tween(if (exacta) 550 else 800), RepeatMode.Reverse), label = "a")
    val titulo = when {
        esAlerta && exacta -> "NO CONSUMIR SIN COMPROBAR"
        esAlerta -> "ATENCION: ALERTA SANITARIA"
        else -> "ATENCION: SUSTANCIA DE RIESGO"
    }
    val texto = when {
        esAlerta && exacta -> "Hay una alerta oficial de AESAN sobre este producto y esta marca. Comprueba el numero de lote mas abajo antes de consumirlo."
        esAlerta -> "Hay una alerta oficial que puede afectar a este tipo de producto. Revisa el apartado de seguridad alimentaria."
        else -> "Este producto contiene una sustancia prohibida o bajo vigilancia sanitaria."
    }
    Card(Modifier.padding(horizontal = 22.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Malo.copy(alpha = a)),
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (exacta) Icons.Filled.Dangerous else Icons.Filled.Warning, null,
                tint = Color.White, modifier = Modifier.size(if (exacta) 40.dp else 34.dp))
            Spacer(Modifier.width(14.dp))
            Column {
                Text(titulo, color = Color.White, fontWeight = FontWeight.Black,
                    fontSize = if (exacta) 18.sp else 16.sp, letterSpacing = 0.5.sp, lineHeight = 22.sp)
                Spacer(Modifier.height(3.dp))
                Text(texto, color = Color.White, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
    }
}

@Composable
fun HazardCard(h: Hazard) {
    val pal = LocalPal.current
    val c = if (h.severity >= 3) Malo else if (h.severity == 2) Medio else Color(0xFF9BC53D)
    Card(Modifier.padding(horizontal = 22.dp, vertical = 5.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = c.copy(alpha = 0.12f)), shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(14.dp)) {
            Icon(Icons.Filled.Dangerous, null, tint = c, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(h.name, color = pal.tinta, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Spacer(Modifier.height(3.dp))
                Text(h.reason, color = pal.gris, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
}

@Composable
fun AlertCard(a: FoodAlert) {
    val pal = LocalPal.current
    val ctx = LocalContext.current
    val exacta = a.matchLevel >= 2
    Card(Modifier.padding(horizontal = 22.dp, vertical = 6.dp).fillMaxWidth().clickable {
            try {
                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(a.url)))
            } catch (e: Exception) { }
        },
        colors = CardDefaults.cardColors(containerColor = if (exacta) Malo.copy(alpha = 0.16f) else pal.superficie),
        shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Campaign, null, tint = Malo, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (exacta) "COINCIDE CON ESTE PRODUCTO" else "Alerta del mismo tipo de alimento",
                    color = Malo, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 0.4.sp)
            }
            Spacer(Modifier.height(8.dp))
            Text(a.title, color = pal.tinta, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp)

            if (a.productName.isNotBlank() || a.brand.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                if (a.productName.isNotBlank()) AlertField("Producto", a.productName)
                if (a.brand.isNotBlank()) AlertField("Marca", a.brand)
                if (a.weight.isNotBlank()) AlertField("Formato", a.weight)
                if (a.bestBefore.isNotBlank()) AlertField("Consumo preferente", a.bestBefore)
            }

            if (a.lots.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Malo.copy(alpha = 0.22f)).padding(12.dp)) {
                    Text("COMPRUEBA TU LOTE", color = Malo, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 0.5.sp)
                    Spacer(Modifier.height(6.dp))
                    a.lots.forEach { lote ->
                        Text("Lote $lote", color = pal.tinta, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Si tu envase lleva este lote, no lo consumas.", color = pal.tinta, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${a.source}${if (a.date.isNotBlank()) " · ${a.date}" else ""} · toca para leer la alerta oficial",
                    color = pal.gris, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Icon(Icons.Filled.OpenInNew, null, tint = pal.gris, modifier = Modifier.size(15.dp))
            }
        }
    }
}

@Composable
fun AlertField(label: String, value: String) {
    val pal = LocalPal.current
    Row(Modifier.padding(vertical = 2.dp)) {
        Text("$label: ", color = pal.gris, fontSize = 12.sp)
        Text(value, color = pal.tinta, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun ScoreBar(score: Int) {
    val pal = LocalPal.current
    val anim = remember { Animatable(0f) }
    LaunchedEffect(score) { anim.animateTo(score / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    val tramos = listOf(Malo to 0.25f, Color(0xFFEB7A34) to 0.20f, Medio to 0.25f, Color(0xFF7BB661) to 0.15f, Bueno to 0.15f)
    Column(Modifier.fillMaxWidth().padding(horizontal = 30.dp)) {
        Row(Modifier.fillMaxWidth().height(9.dp).clip(RoundedCornerShape(50))) {
            tramos.forEach { (c, w) -> Box(Modifier.weight(w).fillMaxHeight().background(c)) }
        }
        Spacer(Modifier.height(5.dp))
        Box(Modifier.fillMaxWidth()) {
            BoxWithConstraints {
                val x = maxWidth * anim.value
                Box(Modifier.offset(x = x - 6.dp)) {
                    Icon(Icons.Filled.ArrowDropUp, null, tint = pal.tinta, modifier = Modifier.size(20.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Malo", color = pal.gris, fontSize = 10.sp)
            Text("Excelente", color = pal.gris, fontSize = 10.sp)
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

fun insightIcon(kind: String): androidx.compose.ui.graphics.vector.ImageVector = when (kind) {
    "sugar" -> Icons.Filled.Cookie
    "salt" -> Icons.Filled.Grain
    "fat", "satfat" -> Icons.Filled.WaterDrop
    "calories" -> Icons.Filled.LocalFireDepartment
    "protein" -> Icons.Filled.FitnessCenter
    "fiber" -> Icons.Filled.Spa
    "additive" -> Icons.Filled.Science
    "allergen" -> Icons.Filled.Warning
    "nova", "nutri" -> Icons.Filled.Assessment
    "eco" -> Icons.Filled.Eco
    else -> Icons.Filled.CheckCircle
}

fun severityColor(sev: Int) = when (sev) {
    0 -> Bueno; 1 -> Color(0xFF9BC53D); 2 -> Medio; else -> Malo
}

@Composable
fun InsightRow(ins: Insight) {
    val pal = LocalPal.current
    val c = severityColor(ins.severity)
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(pal.superficie2), contentAlignment = Alignment.Center) {
                Icon(insightIcon(ins.kind), null, tint = pal.gris, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(ins.title, color = pal.tinta, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (ins.detail.isNotBlank())
                    Text(ins.detail, color = pal.gris, fontSize = 12.sp)
            }
            Box(Modifier.size(13.dp).clip(CircleShape).background(c))
        }
        Divider(color = pal.borde.copy(alpha = 0.5f), thickness = 0.5.dp, modifier = Modifier.padding(start = 74.dp))
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
