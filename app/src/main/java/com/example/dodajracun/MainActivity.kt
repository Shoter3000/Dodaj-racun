package com.example.dodajracun

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.camera.core.ImageCaptureException
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val PERMISSION_REQUEST_CODE = 1001
    private val REQUIRED_PERMISSIONS = mutableListOf(
        Manifest.permission.CAMERA
    ).apply {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Request permissions
        if (!allPermissionsGranted()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, PERMISSION_REQUEST_CODE)
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color.Red,
                    background = Color(0xFF121212),
                    surface = Color(0xFF121212),
                    onPrimary = Color.White,
                    onBackground = Color.White
                )
            ) {
                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    ScanReceiptScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun ScanReceiptScreen(
    modifier: Modifier = Modifier,
    roiWidthDp: Dp = 160.dp,
    roiHeightDp: Dp = 60.dp
) {
    var croppedBitmapDisplay by remember { mutableStateOf<Bitmap?>(null) }
    var recognizedAmount by remember { mutableStateOf<String?>(null) }
    var showDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showMessage(msg: String) {
        scope.launch {
            snackbarHostState.showSnackbar(msg)
        }
    }

    fun Dp.toPx(): Float = this.value * context.resources.displayMetrics.density

    fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return bitmap
        val matrix = Matrix()
        matrix.postRotate(rotationDegrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun ImageProxy.toBitmap(): Bitmap {
        val yBuffer = planes[0].buffer
        val uBuffer = planes[1].buffer
        val vBuffer = planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)

        val yuvImage = android.graphics.YuvImage(nv21, android.graphics.ImageFormat.NV21, width, height, null)
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, width, height), 100, out)
        val imageBytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
    }

    LaunchedEffect(Unit) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val preview = Preview.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_4_3)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
        } catch (e: Exception) {
            Log.e("CameraX", "Bind failed", e)
            showMessage("Napaka pri inicializaciji kamere!")
        }
    }

    // Animacija scanning linije z glow efektom
    val infiniteTransition = rememberInfiniteTransition()
    val lineOffsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = roiHeightDp.toPx(),
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    fun saveAmountToCsv(context: android.content.Context, amount: String) {
        try {
            val displayName = "računi.csv"
            val relativePath = "Documents/Računi"
            val resolver = context.contentResolver

            val existingUri = android.provider.MediaStore.Files.getContentUri("external")
            val query = resolver.query(
                existingUri,
                arrayOf(android.provider.MediaStore.MediaColumns._ID),
                "${android.provider.MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$relativePath/", displayName),
                null
            )

            val fileUri = if (query != null && query.moveToFirst()) {
                val id = query.getLong(query.getColumnIndexOrThrow(android.provider.MediaStore.MediaColumns._ID))
                android.content.ContentUris.withAppendedId(existingUri, id)
            } else {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                }
                resolver.insert(existingUri, values)
            }
            query?.close()

            fileUri?.let { uri ->
                resolver.openOutputStream(uri, "wa")?.bufferedWriter(Charsets.UTF_8).use { writer ->
                    val dateTime = SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale("sl", "SI")).format(Date())
                    // Ohranimo decimalno vejico in spremenimo separator na podpičje
                    val amountFormatted = amount // ohranimo originalno obliko z vejico
                    writer?.write("$amountFormatted;$dateTime\n")

                    writer?.flush()
                }
            }

            showMessage("Znesek shranjen v Documents/Računi/računi.csv")

        } catch (e: Exception) {
            showMessage("Napaka pri shranjevanju: ${e.message}")
        }
    }

    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // ROI okvir
        Box(
            modifier = Modifier
                .size(width = roiWidthDp, height = roiHeightDp)
                .align(Alignment.Center)
                .border(3.dp, Color.Red, RoundedCornerShape(12.dp))
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Transparent)
        )

        // Scanning linija z glow
        Canvas(modifier = Modifier.size(width = roiWidthDp, height = roiHeightDp).align(Alignment.Center)) {
            for (i in 0..4) {
                val alpha = 0.2f / (i + 1)
                drawLine(
                    color = Color.Yellow.copy(alpha = alpha),
                    start = Offset(0f, lineOffsetY + i * 2),
                    end = Offset(size.width, lineOffsetY + i * 2),
                    strokeWidth = 4f
                )
                drawLine(
                    color = Color.Yellow.copy(alpha = alpha),
                    start = Offset(0f, lineOffsetY - i * 2),
                    end = Offset(size.width, lineOffsetY - i * 2),
                    strokeWidth = 4f
                )
            }
            drawLine(
                color = Color.Yellow,
                start = Offset(0f, lineOffsetY),
                end = Offset(size.width, lineOffsetY),
                strokeWidth = 4f
            )
        }

        // Gumb za skeniranje
        Button(
            onClick = {
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(imageProxy: ImageProxy) {
                            val bitmap = imageProxy.toBitmap()
                            val rotatedBitmap = rotateBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)

                            val scaleX = rotatedBitmap.width.toFloat() / previewView.width
                            val scaleY = rotatedBitmap.height.toFloat() / previewView.height

                            val cropLeft = ((previewView.width / 2 - roiWidthDp.toPx() / 2) * scaleX).toInt()
                            val cropTop = ((previewView.height / 2 - roiHeightDp.toPx() / 2) * scaleY).toInt()
                            val cropWidth = (roiWidthDp.toPx() * scaleX).toInt()
                            val cropHeight = (roiHeightDp.toPx() * scaleY).toInt()

                            val croppedBitmap = Bitmap.createBitmap(rotatedBitmap, cropLeft, cropTop, cropWidth, cropHeight)
                            croppedBitmapDisplay = croppedBitmap
                            imageProxy.close()

                            val image = InputImage.fromBitmap(croppedBitmap, 0)
                            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                            recognizer.process(image)
                                .addOnSuccessListener { visionText ->
                                    val regex = Regex("""\d+[.,]\d{2}""")
                                    val znesek = regex.find(visionText.text)?.value
                                    if (znesek != null) {
                                        recognizedAmount = znesek
                                        showDialog = true
                                    } else {
                                        showMessage("Znesek ni zaznan")
                                    }
                                }
                                .addOnFailureListener {
                                    showMessage("Napaka pri prepoznavi besedila!")
                                }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            showMessage("Napaka pri zajemu slike!")
                        }
                    }
                )
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 80.dp)
                .height(64.dp)
                .width(200.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
        ) {
            Text("Skeniraj", color = Color.White, fontSize = 22.sp)
        }

        // Prikaz obrezane slike
        croppedBitmapDisplay?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "Obrezan ROI",
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 160.dp)
                    .size(width = roiWidthDp, height = roiHeightDp)
                    .border(2.dp, Color.White, RoundedCornerShape(8.dp))
            )
        }

        // Dialog za shranjevanje / ponovno skeniranje
        if (showDialog) {
            AlertDialog(
                onDismissRequest = { showDialog = false },
                title = { Text("Znesek zaznan") },
                text = { Text("Znesek: $recognizedAmount €") },
                confirmButton = {
                    TextButton(onClick = {
                        recognizedAmount?.let { saveAmountToCsv(context, it) }
                        showDialog = false
                        recognizedAmount = null
                        croppedBitmapDisplay = null
                    }) { Text("Shrani znesek") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        showDialog = false
                        recognizedAmount = null
                        croppedBitmapDisplay = null
                    }) { Text("Ponovno skeniraj") }
                }
            )
        }

        // Snackbar host
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp)
        )
    }
}
