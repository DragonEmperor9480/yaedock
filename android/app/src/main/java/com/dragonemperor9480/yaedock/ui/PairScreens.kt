package com.dragonemperor9480.yaedock.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.sp
import com.dragonemperor9480.yaedock.R
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dragonemperor9480.yaedock.pairing.PairingInfo
import com.dragonemperor9480.yaedock.pairing.PairingUri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors

/**
 * The camera with a frame for the pairing QR code; calls [onFound] once with
 * the first valid code. In landscape the instructions sit beside the camera.
 */
@Composable
fun ScanScreen(onFound: (PairingInfo) -> Unit, onEnterManually: () -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) request.launch(Manifest.permission.CAMERA) }
    var torch by remember { mutableStateOf(false) }

    val viewfinder = @Composable { viewModifier: Modifier ->
        Box(viewModifier.background(Color(0xFF040506)), contentAlignment = Alignment.Center) {
            if (granted) {
                QrCamera(onFound, torch, Modifier.fillMaxSize())
                ScanFrame(Modifier.size(240.dp))
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp).clip(RoundedCornerShape(999.dp))
                        .background(YaeColors.card).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Dot(YaeColors.accent)
                    Text("Looking for a code…", fontSize = 12.sp)
                }
            } else {
                Column(
                    Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Muted("Scanning needs the camera.", size = 14.sp)
                    ChipButton("Allow camera", { request.launch(Manifest.permission.CAMERA) }, selected = true)
                }
            }
        }
    }
    val instructions = @Composable {
        Text("Scan the pairing code", fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
        Text(
            buildAnnotatedString {
                append("Point the camera at the QR code from ")
                withStyle(SpanStyle(fontFamily = Mono, color = YaeColors.accent)) { append("yaedock pair") }
                append(" on your PC. It pairs as soon as the code is in the frame.")
            },
            fontSize = 14.sp,
            lineHeight = 21.sp,
            color = YaeColors.muted,
        )
    }
    val torchButton = @Composable {
        SquareButton(
            painterResource(R.drawable.ic_flashlight),
            if (torch) "Turn the flashlight off" else "Turn the flashlight on",
            { torch = !torch },
            background = if (torch) YaeColors.accentSoft else YaeColors.card,
            tint = if (torch) YaeColors.accent else YaeColors.text,
            enabled = granted,
        )
    }
    BoxWithConstraints(modifier.fillMaxSize()) {
        if (isWide(maxWidth, maxHeight)) {
            Row(Modifier.fillMaxSize().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                viewfinder(Modifier.weight(0.56f).fillMaxHeight().clip(RoundedCornerShape(22.dp)))
                Column(Modifier.weight(0.44f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        BackButton(onBack)
                        torchButton()
                    }
                    instructions()
                    Spacer(Modifier.weight(1f))
                    ChipButton("Enter details by hand", onEnterManually, Modifier.fillMaxWidth(), height = 48.dp)
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    viewfinder(Modifier.fillMaxSize())
                    Row(Modifier.fillMaxWidth().padding(16.dp)) {
                        BackButton(onBack)
                        Spacer(Modifier.weight(1f))
                        torchButton()
                    }
                }
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).background(YaeColors.card)
                        .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    instructions()
                    ChipButton("Enter details by hand", onEnterManually, Modifier.fillMaxWidth().padding(top = 4.dp), height = 52.dp)
                }
            }
        }
    }
}

/** Four mint corners and a line across the middle, where the code should go. */
@Composable
private fun ScanFrame(modifier: Modifier) {
    Canvas(modifier) {
        val stroke = 4.dp.toPx()
        val arm = 44.dp.toPx()
        val radius = 18.dp.toPx()
        val inset = stroke / 2
        val style = Stroke(stroke, cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        // Each corner: an arm along the top or bottom, a rounded bend, an arm down the side.
        for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f)) {
            val sx = if (x == 0f) 1f else -1f
            val sy = if (y == 0f) 1f else -1f
            val cx = x * w + sx * inset
            val cy = y * h + sy * inset
            val path = Path().apply {
                moveTo(cx + sx * arm, cy)
                lineTo(cx + sx * radius, cy)
                quadraticTo(cx, cy, cx, cy + sy * radius)
                lineTo(cx, cy + sy * arm)
            }
            drawPath(path, YaeColors.accent, style = style)
        }
        drawLine(YaeColors.accent, Offset(20.dp.toPx(), h * 0.45f), Offset(w - 20.dp.toPx(), h * 0.45f), strokeWidth = 2.dp.toPx())
    }
}

@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
private fun QrCamera(onFound: (PairingInfo) -> Unit, torch: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnFound by rememberUpdatedState(onFound)
    val previewView = remember { PreviewView(context) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    LaunchedEffect(camera, torch) { camera?.cameraControl?.enableTorch(torch) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
        var found = false
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            val image = proxy.image
            if (image == null || found) {
                proxy.close()
                return@setAnalyzer
            }
            scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                .addOnSuccessListener { codes ->
                    val info = codes.firstNotNullOfOrNull { code -> code.rawValue?.let(PairingUri::parse) }
                    if (info != null && !found) {
                        found = true
                        currentOnFound(info)
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            if (providerFuture.isDone) providerFuture.get().unbindAll()
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Pairing by typing what `yaedock pair` prints, for when scanning is not possible. */
@Composable
fun ManualPairScreen(onPair: (PairingInfo) -> Unit, onBack: () -> Unit, modifier: Modifier = Modifier) {
    var address by remember { mutableStateOf("") }
    var port by remember { mutableStateOf(PairingUri.DEFAULT_PORT.toString()) }
    var code by remember { mutableStateOf("") }
    var fingerprint by remember { mutableStateOf("") }
    val info = PairingUri.validate(address, port, fingerprint, code)

    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 640.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenHeader("Pair by hand", "Type what yaedock pair shows on your PC", leading = { BackButton(onBack) })
            DockCard(Modifier.fillMaxWidth(), padding = 16.dp, spacing = 10.dp) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Field(address, { address = it }, "Address", KeyboardType.Uri, Modifier.weight(1f))
                    Field(port, { port = it }, "Port", KeyboardType.Number, Modifier.width(110.dp))
                }
                Field(code, { code = it }, "Code", KeyboardType.Number, Modifier.fillMaxWidth())
                Field(fingerprint, { fingerprint = it }, "Fingerprint", KeyboardType.Ascii, Modifier.fillMaxWidth(), singleLine = false)
                Muted("The fingerprint is 64 characters; spaces are fine.", size = 12.sp)
            }
            ChipButton(
                "Pair",
                { info?.let(onPair) },
                Modifier.fillMaxWidth(),
                height = 52.dp,
                enabled = info != null,
                colors = if (info != null) YaeColors.accent to YaeColors.onAccent else YaeColors.raised to YaeColors.muted,
            )
        }
    }
}

@Composable
private fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboard: KeyboardType,
    modifier: Modifier,
    singleLine: Boolean = true,
) {
    OutlinedTextField(
        value,
        onValueChange,
        modifier,
        label = { Text(label) },
        singleLine = singleLine,
        textStyle = TextStyle(fontFamily = Mono, fontSize = 15.sp, color = YaeColors.text),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        shape = ChipShape,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = YaeColors.accent,
            unfocusedBorderColor = YaeColors.track,
            focusedLabelColor = YaeColors.accent,
            unfocusedLabelColor = YaeColors.muted,
            cursorColor = YaeColors.accent,
            focusedContainerColor = YaeColors.raised,
            unfocusedContainerColor = YaeColors.raised,
        ),
    )
}
