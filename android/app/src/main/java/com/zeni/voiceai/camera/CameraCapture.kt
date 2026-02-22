package com.zeni.voiceai.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Camera capture helper for visual context.
 * Keeps latest frame cached for instant retrieval.
 */
class CameraCapture(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    private var isInitialized = false
    
    // Cached latest frame for instant retrieval
    private val latestFrameBase64 = AtomicReference<String?>(null)
    private var lastFrameTime = 0L
    
    companion object {
        private const val TAG = "CameraCapture"
        private const val IMAGE_QUALITY = 95  // Maximum JPEG quality for document clarity
        private const val MAX_WIDTH = 1920    // Full HD for document analysis
        private const val FRAME_INTERVAL_MS = 50  // Very fast cache updates for real-time freshness
    }
    
    /**
     * Initialize camera for capturing.
     * Sets up continuous frame caching for instant retrieval.
     */
    fun initialize(lifecycleOwner: LifecycleOwner, onReady: () -> Unit = {}) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                
                // Build ImageCapture use case (fallback) - MAX QUALITY for documents
                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)  // Best quality
                    .setTargetRotation(android.view.Surface.ROTATION_0)
                    .setTargetResolution(android.util.Size(1920, 1440))  // High res
                    .build()
                
                // Build ImageAnalysis for continuous frame caching
                // Request HIGH resolution for document/text analysis
                imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(android.util.Size(1920, 1440))  // Request 1920x1440 for documents
                    .build()
                    .also { analysis ->
                        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            // Update cache every FRAME_INTERVAL_MS
                            val now = System.currentTimeMillis()
                            if (now - lastFrameTime >= FRAME_INTERVAL_MS) {
                                lastFrameTime = now
                                try {
                                    val base64 = imageProxyToBase64(imageProxy)
                                    if (base64.isNotEmpty()) {
                                        latestFrameBase64.set(base64)
                                        Log.d(TAG, "Frame cached (${base64.length} chars)")
                                    } else {
                                        Log.w(TAG, "Frame conversion returned empty")
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Frame cache update failed", e)
                                }
                            }
                            imageProxy.close()
                        }
                    }
                
                // Use front camera for user observation
                val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
                
                try {
                    cameraProvider?.unbindAll()
                    cameraProvider?.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        imageCapture,
                        imageAnalysis
                    )
                    
                    isInitialized = true
                    Log.i(TAG, "Camera initialized with frame caching")
                    onReady()
                } catch (e: Exception) {
                    Log.e(TAG, "Camera bind failed", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera provider failed", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * Get the latest cached frame instantly.
     * Returns null only if no frame has been captured yet.
     */
    fun getLatestFrame(): String? {
        return latestFrameBase64.get()
    }
    
    /**
     * Capture visual context - returns cached frame immediately.
     * The cache is updated every 200ms by ImageAnalysis, so it's always recent.
     * This is INSTANT and reliable.
     */
    fun captureFreshFrame(onResult: (String?) -> Unit) {
        val cached = latestFrameBase64.get()
        if (cached != null && cached.isNotEmpty()) {
            Log.d(TAG, "Using cached frame (${cached.length} chars) - updated every ${FRAME_INTERVAL_MS}ms")
            onResult(cached)
            return
        }
        
        // No cache yet - try ImageCapture as fallback
        Log.w(TAG, "No cached frame available, trying ImageCapture...")
        if (!isInitialized || imageCapture == null) {
            Log.e(TAG, "Camera not initialized")
            onResult(null)
            return
        }
        
        imageCapture?.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val base64 = imageProxyToBase64(imageProxy)
                        imageProxy.close()
                        Log.d(TAG, "ImageCapture fallback: ${base64.length} chars")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onResult(if (base64.isNotEmpty()) base64 else null)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "ImageCapture failed", e)
                        imageProxy.close()
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            onResult(null)
                        }
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "ImageCapture error", exception)
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        onResult(null)
                    }
                }
            }
        )
    }
    
    /**
     * Capture a single frame and return as Base64 string.
     * First tries cached frame (instant), falls back to fresh capture.
     */
    fun captureFrame(onResult: (String?) -> Unit) {
        // Try cached frame first (instant!)
        val cached = latestFrameBase64.get()
        if (cached != null && cached.isNotEmpty()) {
            Log.d(TAG, "Using cached frame (instant)")
            onResult(cached)
            return
        }
        
        // Fallback to fresh capture if no cached frame
        if (!isInitialized || imageCapture == null) {
            Log.w(TAG, "Camera not initialized, skipping capture")
            onResult(null)
            return
        }
        
        Log.d(TAG, "No cached frame, doing fresh capture")
        imageCapture?.takePicture(
            cameraExecutor,
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(imageProxy: ImageProxy) {
                    try {
                        val base64 = imageProxyToBase64(imageProxy)
                        imageProxy.close()
                        onResult(base64)
                    } catch (e: Exception) {
                        Log.e(TAG, "Image processing failed", e)
                        imageProxy.close()
                        onResult(null)
                    }
                }
                
                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "Capture failed", exception)
                    onResult(null)
                }
            }
        )
    }
    
    /**
     * Convert ImageProxy to Base64 string with compression.
     * Handles YUV_420_888 format from ImageAnalysis.
     */
    private fun imageProxyToBase64(imageProxy: ImageProxy): String {
        return try {
            val bitmap = when (imageProxy.format) {
                ImageFormat.YUV_420_888 -> yuvTobitmap(imageProxy)
                ImageFormat.JPEG -> {
                    val buffer = imageProxy.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                else -> {
                    Log.w(TAG, "Unsupported format: ${imageProxy.format}")
                    null
                }
            } ?: return ""
            
            // Apply rotation and mirror for front camera
            val processedBitmap = processBitmap(bitmap, imageProxy.imageInfo.rotationDegrees)
            
            // Compress to JPEG and encode as Base64
            val outputStream = ByteArrayOutputStream()
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, outputStream)
            
            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Image conversion failed", e)
            ""
        }
    }
    
    /**
     * Convert YUV_420_888 ImageProxy to Bitmap.
     * Properly handles pixel stride and row stride for all devices.
     */
    private fun yuvTobitmap(imageProxy: ImageProxy): Bitmap? {
        val width = imageProxy.width
        val height = imageProxy.height
        
        val yPlane = imageProxy.planes[0]
        val uPlane = imageProxy.planes[1]
        val vPlane = imageProxy.planes[2]
        
        val yBuffer = yPlane.buffer.duplicate()
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        
        // Rewind buffers to start
        yBuffer.rewind()
        uBuffer.rewind()
        vBuffer.rewind()
        
        // Get row and pixel strides - critical for correct conversion
        val yRowStride = yPlane.rowStride
        val uvRowStride = uPlane.rowStride
        val uvPixelStride = uPlane.pixelStride
        
        Log.d(TAG, "YUV: ${width}x${height}, yStride=$yRowStride, uvStride=$uvRowStride, uvPx=$uvPixelStride")
        
        // NV21 format needs width * height * 1.5 bytes
        val nv21 = ByteArray(width * height * 3 / 2)
        
        // Copy Y plane
        var yPos = 0
        for (row in 0 until height) {
            yBuffer.position(row * yRowStride)
            yBuffer.get(nv21, yPos, width)
            yPos += width
        }
        
        // Copy UV planes (NV21 = VU interleaved)
        val uvWidth = width / 2
        val uvHeight = height / 2
        var uvPos = width * height
        
        // Interleave V and U properly
        for (row in 0 until uvHeight) {
            for (col in 0 until uvWidth) {
                val uvOffset = row * uvRowStride + col * uvPixelStride
                nv21[uvPos++] = vBuffer.get(uvOffset)  // V first in NV21
                nv21[uvPos++] = uBuffer.get(uvOffset)  // U second
            }
        }
        
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), IMAGE_QUALITY, out)
        
        val jpegBytes = out.toByteArray()
        Log.d(TAG, "YUV->JPEG: ${width}x${height} -> ${jpegBytes.size} bytes")
        
        return BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    }
    
    /**
     * Process bitmap: rotate and mirror for front camera.
     * NO aggressive resizing - keep quality for document analysis.
     */
    private fun processBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        var result = bitmap
        
        // Rotate if needed
        if (rotationDegrees != 0) {
            val matrix = Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            // Mirror for front camera
            matrix.postScale(-1f, 1f)
            result = Bitmap.createBitmap(result, 0, 0, result.width, result.height, matrix, true)
        }
        
        // Only resize if extremely large (>4K) to prevent memory issues
        // Keep high resolution for document/text analysis
        if (result.width > MAX_WIDTH) {
            val scale = MAX_WIDTH.toFloat() / result.width
            val newHeight = (result.height * scale).toInt()
            result = Bitmap.createScaledBitmap(result, MAX_WIDTH, newHeight, true)
            Log.d(TAG, "Resized to ${MAX_WIDTH}x${newHeight} for upload")
        }
        
        return result
    }
    
    /**
     * Release camera resources.
     */
    fun release() {
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        isInitialized = false
    }
}
