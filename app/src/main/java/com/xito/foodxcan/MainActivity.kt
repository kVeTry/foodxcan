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
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.ui.platform.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.xito.foodxcan.data.*
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

// ---- Paleta Foodxcan ----
val Bosque = Color(0xFF0E2A1F)
val BosqueClaro = Color(0xFF16402F)
val Lima = Color(0xFFA6E22E)
val Fondo = Color(0xFFF7FAF5)
val Tinta = Color(0xFF17251E)
val GrisTxt = Color(0xFF5B6B62)
val Bueno = Color(0xFF2E9E5B)
val Medio = Color(0xFFF2A93B)
val Malo = Color(0xFFE05252)

fun scoreColor(s: Int) = when { s >= 70 -> Bueno; s >= 45 -> Medio; else -> Malo }
fun scoreLabel(s: Int) = when { s >= 85 -> "Excelente"; s >= 70 -> "Bueno"; s >= 45 -> "Mediocre"; else -> "Malo" }
fun riskColor(r: Risk) = when (r) { Risk.SIN_RIESGO -> Bueno; Risk.LIMITADO -> Color(0xFF7BB661); Risk.MODERADO -> Medio; Risk.ALTO -> Malo }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Bosque, background = Fondo, surface = Color.White)) {
                App()
            }
        }
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf("home") }
    var product by remember { mutableStateOf<Product?>(null) }
    var alternatives by remember { mutableStateOf<List<Alternative>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun lookup(code: String) {
        loading = true; error = null; screen = "result"
        scope.launch {
            val p = Repo.fetchProduct(code)
            if (p == null) { error = "No encontramos este producto en la base de datos.\nCódigo: $code" }
            else { product = p; alternatives = Repo.fetchAlternatives(p) }
            loading = false
        }
    }

    when (screen) {
        "home" -> HomeScreen(onScan = { screen = "scan" }, onManual = { lookup(it) })
        "scan" -> ScannerScreen(onDetected = { lookup(it) }, onBack = { screen = "home" })
        "result" -> ResultScreen(product, alternatives, loading, error,
            onBack = { screen = "home"; product = null; error = null },
            onScanAgain = { screen = "scan"; product = null; error = null },
            onAlternative = { lookup(it) })
    }
}

// ================= HOME =================
@Composable
fun HomeScreen(onScan: () -> Unit, onManual: (String) -> Unit) {
    var manual by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Bosque)) {
        Spacer(Modifier.height(70.dp))
        Column(Modifier.padding(horizontal = 28.dp)) {
            Text("Foodxcan", color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text("Escanea. Descubre. Come mejor.", color = Lima, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(40.dp))
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                .background(Fondo).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(20.dp))
            Box(
                Modifier.size(190.dp).clip(CircleShape).background(Bosque).clickable { onScan() },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.QrCodeScanner, null, tint = Lima, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("ESCANEAR", color = Color.White, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            }
            Spacer(Modifier.height(36.dp))
            Text("o escribe el código de barras", color = GrisTxt, fontSize = 14.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = manual, onValueChange = { manual = it.filter { c -> c.isDigit() } },
                placeholder = { Text("Ej.: 8480000123456") },
                singleLine = true, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(onClick = { if (manual.length >= 8) onManual(manual) }) {
                        Icon(Icons.Filled.Search, null, tint = Bosque)
                    }
                }
            )
            Spacer(Modifier.weight(1f))
            Text("Datos: Open Food Facts · Precios orientativos", color = GrisTxt, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
        }
    }
}

// ================= ESCÁNER =================
@Composable
fun ScannerScreen(onDetected: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) CameraPreview(onDetected)
        else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Se necesita permiso de cámara", color = Color.White)
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Conceder permiso") }
        }
        // Marco guía
        Box(
            Modifier.align(Alignment.Center).size(280.dp, 170.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.Transparent)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val s = 3.dp.toPx(); val l = 40.dp.toPx()
                val c = Lima
                drawLine(c, Offset(0f, 0f), Offset(l, 0f), s); drawLine(c, Offset(0f, 0f), Offset(0f, l), s)
                drawLine(c, Offset(size.width, 0f), Offset(size.width - l, 0f), s); drawLine(c, Offset(size.width, 0f), Offset(size.width, l), s)
                drawLine(c, Offset(0f, size.height), Offset(l, size.height), s); drawLine(c, Offset(0f, size.height), Offset(0f, size.height - l), s)
                drawLine(c, Offset(size.width, size.height), Offset(size.width - l, size.height), s); drawLine(c, Offset(size.width, size.height), Offset(size.width, size.height - l), s)
            }
        }
        Text("Apunta al código de barras", color = Color.White, modifier = Modifier.align(Alignment.Center).offset(y = 120.dp))
        IconButton(onClick = onBack, modifier = Modifier.padding(16.dp).statusBarsPadding()) {
            Icon(Icons.Filled.ArrowBack, null, tint = Color.White)
        }
    }
}

@Composable
fun CameraPreview(onDetected: (String) -> Unit) {
    val context = LocalContext.current
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
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media != null && !handled) {
                        val img = InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees)
                        scanner.process(img)
                            .addOnSuccessListener { codes ->
                                codes.firstOrNull()?.rawValue?.let { v ->
                                    if (!handled && v.length >= 8 && v.all { it.isDigit() }) {
                                        handled = true; onDetected(v)
                                    }
                                }
                            }
                            .addOnCompleteListener { proxy.close() }
                    } else proxy.close()
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            }, ContextCompat.getMainExecutor(ctx))
        }
        previewView
    }, modifier = Modifier.fillMaxSize())
}

// ================= RESULTADO =================
@Composable
fun ResultScreen(
    product: Product?, alternatives: List<Alternative>, loading: Boolean, error: String?,
    onBack: () -> Unit, onScanAgain: () -> Unit, onAlternative: (String) -> Unit
) {
    Box(Modifier.fillMaxSize().background(Fondo)) {
        when {
            loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Bosque)
                Spacer(Modifier.height(16.dp)); Text("Analizando producto…", color = GrisTxt)
            }
            error != null -> Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Filled.SearchOff, null, tint = GrisTxt, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text(error, textAlign = TextAlign.Center, color = Tinta)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onScanAgain, colors = ButtonDefaults.buttonColors(containerColor = Bosque)) { Text("Escanear otro") }
            }
            product != null -> ProductDetail(product, alternatives, onAlternative)
        }
        Row(Modifier.statusBarsPadding().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, null, tint = Tinta) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onScanAgain) { Icon(Icons.Filled.QrCodeScanner, null, tint = Tinta) }
        }
    }
}

@Composable
fun ProductDetail(p: Product, alternatives: List<Alternative>, onAlternative: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 64.dp, bottom = 32.dp)) {
        item {
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                if (p.imageUrl != null) AsyncImage(
                    model = p.imageUrl, contentDescription = null,
                    modifier = Modifier.size(92.dp).clip(RoundedCornerShape(16.dp)).background(Color.White)
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Tinta)
                    if (p.brand.isNotBlank()) Text(p.brand, color = GrisTxt, fontSize = 14.sp)
                    if (p.quantity.isNotBlank()) Text(p.quantity, color = GrisTxt, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            ScoreRing(p.score)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.Center) {
                p.nutriScore?.let { Chip("Nutri-Score ${it.uppercase()}", scoreColorNutri(it)) }
                Spacer(Modifier.width(8.dp))
                p.novaGroup?.let { Chip("NOVA $it", if (it >= 4) Malo else if (it == 1) Bueno else Medio) }
                p.estimatedPrice?.let { Spacer(Modifier.width(8.dp)); Chip("~ $it", Color(0xFF4A6FA5)) }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (p.positives.isNotEmpty()) {
            item { SectionTitle("Lo bueno", Icons.Filled.ThumbUp, Bueno) }
            items(p.positives) { PointRow(it, Bueno) }
            item { Spacer(Modifier.height(16.dp)) }
        }
        if (p.negatives.isNotEmpty()) {
            item { SectionTitle("Lo malo", Icons.Filled.ThumbDown, Malo) }
            items(p.negatives) { PointRow(it, Malo) }
            item { Spacer(Modifier.height(16.dp)) }
        }

        if (p.additives.isNotEmpty()) {
            item { SectionTitle("Aditivos (${p.additives.size})", Icons.Filled.Science, Bosque) }
            items(p.additives) { AdditiveCard(it) }
            item { Spacer(Modifier.height(16.dp)) }
        }

        if (alternatives.isNotEmpty()) {
            item { SectionTitle("Alternativas mejores", Icons.Filled.SwapHoriz, Bosque) }
            items(alternatives) { a ->
                Card(
                    Modifier.padding(horizontal = 24.dp, vertical = 6.dp).fillMaxWidth().clickable { onAlternative(a.barcode) },
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(model = a.imageUrl, contentDescription = null,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(Fondo))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(a.name, fontWeight = FontWeight.SemiBold, color = Tinta, maxLines = 2)
                            if (a.brand.isNotBlank()) Text(a.brand, color = GrisTxt, fontSize = 12.sp)
                        }
                        a.nutriScore?.let { Chip(it.uppercase(), scoreColorNutri(it)) }
                    }
                }
            }
        }
    }
}

@Composable
fun ScoreRing(score: Int) {
    val anim = remember { Animatable(0f) }
    LaunchedEffect(score) { anim.animateTo(score / 100f, tween(1100, easing = FastOutSlowInEasing)) }
    val color = scoreColor(score)
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(170.dp)) {
                val stroke = 16.dp.toPx()
                drawArc(Color(0xFFE6ECE8), -90f, 360f, false, style = Stroke(stroke, cap = StrokeCap.Round),
                    size = Size(size.width - stroke, size.height - stroke), topLeft = Offset(stroke / 2, stroke / 2))
                drawArc(color, -90f, 360f * anim.value, false, style = Stroke(stroke, cap = StrokeCap.Round),
                    size = Size(size.width - stroke, size.height - stroke), topLeft = Offset(stroke / 2, stroke / 2))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(anim.value * 100).toInt()}", fontSize = 44.sp, fontWeight = FontWeight.Black, color = color)
                Text("/ 100 · ${scoreLabel(score)}", color = GrisTxt, fontSize = 13.sp)
            }
        }
    }
}

fun scoreColorNutri(g: String) = when (g) { "a" -> Bueno; "b" -> Color(0xFF7BB661); "c" -> Medio; "d" -> Color(0xFFEB7A34); else -> Malo }

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
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Tinta)
    }
}

@Composable
fun PointRow(text: String, color: Color) {
    Row(Modifier.padding(horizontal = 28.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 6.dp).size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(10.dp))
        Text(text, color = Tinta, fontSize = 14.sp)
    }
}

@Composable
fun AdditiveCard(a: AdditiveInfo) {
    var expanded by remember { mutableStateOf(false) }
    val c = riskColor(a.risk)
    Card(
        Modifier.padding(horizontal = 24.dp, vertical = 5.dp).fillMaxWidth().clickable { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${a.code} · ${a.name}", fontWeight = FontWeight.SemiBold, color = Tinta, fontSize = 14.sp)
                    Text("${a.category} · ${a.risk.label}", color = c, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, tint = GrisTxt)
            }
            if (expanded) {
                Spacer(Modifier.height(8.dp))
                Text(a.description, color = GrisTxt, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }
}
