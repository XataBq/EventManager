package ru.eventpass.core.platform

import android.annotation.SuppressLint
import android.content.Context
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** One visible scanner cycle. Main-thread start/close; releases only its own CameraX use cases. */
class QrCameraPreview(context: Context) : FrameLayout(context), AutoCloseable {
    private val previewView = PreviewView(context).also {
        it.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        addView(it, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)
    private var started = false
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null
    var isCameraBound: Boolean = false
        private set
    val isStreaming: Boolean get() = previewView.previewStreamState.value == PreviewView.StreamState.STREAMING

    @SuppressLint("MissingPermission") // Route checks runtime CAMERA before creating this view.
    fun start(owner: LifecycleOwner, onFrame: (List<String>) -> Unit, onFailure: () -> Unit) {
        if (started || stopped.get()) return
        started = true
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (stopped.get()) return@addListener
            try {
                val camera = future.get(); provider = camera
                val p = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val a = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                preview = p; analysis = a
                a.setAnalyzer(executor) { image ->
                    try {
                        if (!stopped.get() && !delivered.get()) {
                            val plane = image.planes[0]
                            val buffer = plane.buffer.duplicate()
                            val bytes = ByteArray(buffer.remaining()); buffer.get(bytes)
                            val crop = image.cropRect
                            val found = QrFrameDecoder.decode(bytes, image.width, image.height, plane.rowStride,
                                plane.pixelStride, crop.left, crop.top, crop.width(), crop.height())
                            if (found.isNotEmpty() && delivered.compareAndSet(false, true)) main.execute {
                                if (!stopped.get()) onFrame(found)
                            }
                        }
                    } catch (_: IllegalArgumentException) {
                        if (delivered.compareAndSet(false, true)) main.execute { if (!stopped.get()) onFailure() }
                    } finally { image.close() }
                }
                camera.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, p, a)
                isCameraBound = true
            } catch (error: Exception) {
                when (error) {
                    is java.util.concurrent.ExecutionException, is IllegalStateException,
                    is IllegalArgumentException, is SecurityException -> { close(); onFailure() }
                    else -> throw error
                }
            }
        }, main)
    }
    override fun close() {
        if (!stopped.compareAndSet(false, true)) return
        analysis?.clearAnalyzer()
        val uses = listOfNotNull(preview, analysis).toTypedArray()
        if (uses.isNotEmpty()) provider?.unbind(*uses)
        isCameraBound = false
        executor.shutdown()
    }
}
