package com.sivelswhy.multiblur

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

enum class Status { QUEUED, PROCESSING, DONE, ERROR, CANCELED }

/** One file in the list, observed by the interface. */
class Item(val uri: Uri, val name: String, val isVideo: Boolean) {
    var status by mutableStateOf(Status.QUEUED)
    /** Null while the share is unknown (photos, or a video that hasn't started). */
    var progress by mutableStateOf<Float?>(null)
    var secondsLeft by mutableStateOf<Long?>(null)
    var thumbnail by mutableStateOf<ImageBitmap?>(null)
    var result by mutableStateOf<Result?>(null)
    /** A string resource, or a raw message for unexpected errors. */
    var errorRes by mutableStateOf<Int?>(null)
    var errorText by mutableStateOf<String?>(null)

    @Volatile
    var cancelled = false
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context get() = getApplication<Application>()

    var options by mutableStateOf(Options.load(application))
        private set
    var platesPhase by mutableStateOf(ModelPhase.WAITING)
        private set
    var facesPhase by mutableStateOf(ModelPhase.WAITING)
        private set
    var modelError by mutableStateOf<String?>(null)
        private set
    var replacement by mutableStateOf<Bitmap?>(null)
        private set
    var replacementName by mutableStateOf<String?>(null)
        private set

    val queue = mutableStateListOf<Item>()
    val busy get() = queue.any { it.status == Status.PROCESSING || it.status == Status.QUEUED }

    private var processor: Processor? = null
    private var running = false

    init {
        viewModelScope.launch {
            try {
                val detector = withContext(Dispatchers.Default) {
                    Detector.load(context) { plates, phase ->
                        viewModelScope.launch { if (plates) platesPhase = phase else facesPhase = phase }
                    }
                }
                processor = Processor(context, detector)
                runQueue()
            } catch (error: Throwable) {
                Log.e(TAG, "Models failed to load", error)
                modelError = error.message ?: error.toString()
            }
        }
    }

    fun update(change: (Options) -> Options) {
        options = change(options)
        options.save(context)
    }

    fun reset() = update { Options() }

    fun setReplacement(uri: Uri) {
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { Processor.decodeImage(context, uri) }.getOrNull() }
            replacement = bitmap
            replacementName = bitmap?.let { Processor.displayName(context, uri) }
        }
    }

    fun add(uris: List<Uri>) {
        viewModelScope.launch {
            for (uri in uris) {
                val (name, video) = withContext(Dispatchers.IO) { Processor.displayName(context, uri) to Processor.isVideo(context, uri) }
                val item = Item(uri, name, video)
                queue += item
                if (!video) launch(Dispatchers.IO) {
                    runCatching { Processor.decodeThumbnail(context, uri) }.getOrNull()?.let { item.thumbnail = it.asImageBitmap() }
                }
            }
            runQueue()
        }
    }

    fun remove(item: Item) {
        queue.remove(item)
    }

    fun stop(item: Item) {
        item.cancelled = true
    }

    fun clearFinished() {
        queue.removeAll { it.status != Status.QUEUED && it.status != Status.PROCESSING }
    }

    private suspend fun runQueue() {
        val processor = processor ?: return
        if (running) return
        running = true
        try {
            while (true) {
                val item = queue.firstOrNull { it.status == Status.QUEUED } ?: break
                process(item, processor)
            }
        } finally {
            running = false
        }
    }

    private suspend fun process(item: Item, processor: Processor) {
        // Settings are read when a file starts, so changing them affects the files that follow.
        val used = options
        val problem = when {
            !used.maskPlates && !used.maskFaces -> R.string.error_no_target
            used.mode == MaskMode.IMAGE && replacement == null -> R.string.error_no_replacement
            else -> null
        }
        if (problem != null) {
            item.errorRes = problem
            item.status = Status.ERROR
            return
        }
        item.status = Status.PROCESSING
        val started = SystemClock.elapsedRealtime()
        val callbacks = Callbacks(
            progress = { fraction ->
                if (item.isVideo) {
                    val elapsed = (SystemClock.elapsedRealtime() - started) / 1000.0
                    viewModelScope.launch {
                        item.progress = fraction
                        item.secondsLeft = if (fraction > 0.02f) (elapsed / fraction * (1 - fraction)).toLong() else null
                    }
                }
            },
            preview = { frame -> viewModelScope.launch { item.thumbnail = frame.asImageBitmap() } },
            isCancelled = { item.cancelled },
        )
        try {
            val result = withContext(Dispatchers.Default) {
                if (item.isVideo) processor.processVideo(item.uri, used, replacement, callbacks)
                else processor.processImage(item.uri, used, replacement, callbacks)
            }
            item.result = result
            item.progress = 1f
            item.status = Status.DONE
        } catch (error: Throwable) {
            if (error is CancellationException && !item.cancelled) throw error
            Log.e(TAG, "Processing ${item.name} failed", error)
            item.status = if (item.cancelled) Status.CANCELED else Status.ERROR
            item.errorText = error.message ?: error.toString()
        }
    }

    private companion object {
        const val TAG = "MultiBlur"
    }
}
