package co.iostream.apps.testframe

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.*
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.graphics.YuvImage
import android.graphics.Rect
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageProxy
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var webSocket: WebSocket
    private lateinit var cameraExecutor: ExecutorService
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private lateinit var debugStorageDir: File
    private var frameCount = 0
    private val savedFrames = mutableStateListOf<File>()

    private val _isStreaming = MutableStateFlow(true)
    private val isStreaming = _isStreaming.asStateFlow()

    private val _lastResponse = MutableStateFlow<String?>(null)
    private val lastResponse = _lastResponse.asStateFlow()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            startCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setupDebugStorage()
        cameraExecutor = Executors.newSingleThreadExecutor()
        setupWebSocket()

        setContent {
            CameraScreen(
                onRequestPermission = { requestPermissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        }
    }

    private fun setupDebugStorage() {
        debugStorageDir = File(
            getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "DebugFrames"
        ).apply {
            if (!exists()) {
                mkdirs()
            }
        }

        debugStorageDir.listFiles()?.forEach { it.delete() }
    }

    private fun setupWebSocket() {
        val request = Request.Builder()
            .url("wss://myonechain.com/screwthisproj_android")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("WebSocket", "Connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("WebSocket", "Received: $text")
                MainScope().launch {
                    _lastResponse.value = text
                }
            }

//            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
//                try {
//                    val jsonString = bytes.utf8()
//                    Log.i("WebSocket Response", "Received JSON: $jsonString")
//
//                    MainScope().launch {
//                        _lastResponse.value = jsonString
//                    }
//                } catch (e: Exception) {
//                    Log.e("WebSocket", "Error parsing JSON: ${e.message}")
//                }
//            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.i("WebSocket Response", "Binary message: ${bytes.hex()}")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("WebSocket", "Error : ${t.message}")
                MainScope().launch {
                    delay(5000)
                    if (isStreaming.value) {
                        setupWebSocket()
                    }
                }
            }
        })
    }

    private fun saveFrameToStorage(jpegBytes: ByteArray) {
        try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val frameFile = File(debugStorageDir, "frame_${timestamp}.jpg")

            frameFile.writeBytes(jpegBytes)

            debugStorageDir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(50)?.forEach {
                it.delete()
            }

            Log.d("Storage", "Saved frame to ${frameFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("Storage", "Error saving frame", e)
        }
    }

    private fun startCamera() {
        setContent {
            CameraScreen(
                onRequestPermission = { requestPermissionLauncher.launch(Manifest.permission.CAMERA) }
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        webSocket.close(1000, "Activity destroyed")
    }

    @Composable
    fun CameraScreen(onRequestPermission: () -> Unit) {
        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val streaming by isStreaming.collectAsState()
        val response by lastResponse.collectAsState()

        var hasCameraPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.CAMERA
                ) == PackageManager.PERMISSION_GRANTED
            )
        }

        LaunchedEffect(Unit) {
            while(true) {
                updateSavedFramesList()
                delay(1000)
            }
        }
        Column(modifier = Modifier.fillMaxSize()) {
            if (hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(2f)
                ) {
                    AndroidView(
                        factory = { context ->
                            PreviewView(context).apply {
                                setupCamera(context, this, lifecycleOwner)
                            }
                        },
                        modifier = Modifier.fillMaxSize().graphicsLayer(rotationY = 180f)
                    )
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.7f)
                        .padding(8.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (streaming) "Status: Streaming" else "Status: Stopped",
                                modifier = Modifier.padding(end = 8.dp)
                            )
                            Button(
                                onClick = {
                                    _isStreaming.value = !streaming
                                },
                            ) {
                                Text(if (streaming) "STOP" else "START")
                            }
                        }
                        Text(
                            "Server Response:",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFFF5F5F5))
                                .padding(8.dp)
                        ) {
                            Text(
                                text = response ?: "No response yet",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    SavedFramesPreview()
                }
            } else {
                Button(
                    onClick = onRequestPermission,
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text("Request Camera Permission")
                }
            }
        }
    }

    @Composable
    private fun SavedFramesPreview() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Text(
                "Frame được gửi cập nhật phía dưới",
                modifier = Modifier.padding(bottom = 8.dp)
            )

            if (savedFrames.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text("No saved frames yet")
                }
            } else {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(savedFrames) { file ->
                        SavedFrameItem(file)
                    }
                }
            }
        }
    }

    private fun updateSavedFramesList() {
        try {
            debugStorageDir.listFiles()?.let { files ->
                val sortedFiles = files.sortedByDescending { it.lastModified() }
                    .take(10)
                savedFrames.clear()
                savedFrames.addAll(sortedFiles)
            }
        } catch (e: Exception) {
            Log.e("Storage", "Error updating saved frames list", e)
        }
    }

    private fun setupCamera(
        context: Context,
        previewView: PreviewView,
        lifecycleOwner: LifecycleOwner
    ) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(240, 320))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also {
                    it.setAnalyzer(cameraExecutor) { image ->
                        processImageProxy(image)
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageAnalyzer
                )
            } catch (e: Exception) {
                Log.e("Camera", "Use case binding failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

private fun processImageProxy(imageProxy: ImageProxy) {
    try {
        if (isStreaming.value) {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                85,
                out
            )
            val jpegBytes = out.toByteArray()

            cameraExecutor.execute {
                Handler(Looper.getMainLooper()).post {
                    updateSavedFramesList()
                }

                val success = webSocket.send(ByteString.of(*jpegBytes))
                if(success){
                    Log.d("Camera", "Frame #$frameCount - Size: ${jpegBytes.size} bytes, Sent: thanh cong")
                    saveFrameToStorage(jpegBytes)
                    frameCount++
                }
            }

            out.close()
        }
    } catch (e: Exception) {
        Log.e("Camera", "Error processing image", e)
    } finally {
        imageProxy.close()
    }
}

    @Composable
    private fun SavedFrameItem(file: File) {
        var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

        LaunchedEffect(file.absolutePath) {
            withContext(Dispatchers.IO) {
                try {
                    val loadedBitmap = BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()
                    bitmap = loadedBitmap
                } catch (e: Exception) {
                    Log.e("UI", "Error loading bitmap", e)
                }
            }
        }

        Box(
            modifier = Modifier
                .size(120.dp)
                .padding(4.dp)
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = "Saved frame",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } ?: CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}