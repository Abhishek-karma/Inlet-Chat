package com.assistant.app.ui.components

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.assistant.app.R
import com.assistant.app.llm.model.UiAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Full-screen viewers for message media, with pinch zoom and a save action.
 * Saving uses the system file picker, so no storage permissions are needed.
 */

internal data class DecodedImage(val bitmap: Bitmap, val aspect: Float)

internal suspend fun decodeDownscaled(path: String, maxDim: Int = 2048): DecodedImage? =
    withContext(Dispatchers.IO) {
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (
                bounds.outWidth / (sample * 2) >= maxDim &&
                bounds.outHeight / (sample * 2) >= maxDim
            ) {
                sample *= 2
            }
            val bitmap = BitmapFactory.decodeFile(
                path,
                BitmapFactory.Options().apply { inSampleSize = sample },
            ) ?: return@runCatching null
            DecodedImage(bitmap, bounds.outWidth.toFloat() / bounds.outHeight)
        }.getOrNull()
    }

private val THUMBNAIL_HEIGHT = 120.dp

/**
 * Tappable thumbnail for an attached image, opening the full-screen viewer.
 * Falls back to the file name while decoding or when the file is unreadable.
 */
@Composable
fun AttachmentThumbnail(attachment: UiAttachment, onClick: () -> Unit) {
    val decoded = remember(attachment.id, attachment.path) { mutableStateOf<DecodedImage?>(null) }
    LaunchedEffect(attachment.id, attachment.path) {
        decoded.value = decodeDownscaled(attachment.path, maxDim = 512)
    }
    val image = decoded.value
    if (image == null) {
        Text(
            text = attachment.displayName,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 2.dp),
        )
        return
    }
    val imageBitmap = remember(image.bitmap) { image.bitmap.asImageBitmap() }
    Image(
        bitmap = imageBitmap,
        contentDescription = attachment.displayName,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .height(THUMBNAIL_HEIGHT)
            .width((THUMBNAIL_HEIGHT * image.aspect).coerceIn(48.dp, 280.dp))
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
    )
}

@Composable
fun ImageViewerDialog(attachment: UiAttachment, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var loaded by remember(attachment.id, attachment.path) { mutableStateOf(false) }
    val decoded = remember(attachment.id, attachment.path) { mutableStateOf<DecodedImage?>(null) }
    LaunchedEffect(attachment.id, attachment.path) {
        decoded.value = decodeDownscaled(attachment.path)
        loaded = true
    }
    var saveTarget by remember { mutableStateOf<Uri?>(null) }
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(attachment.mime),
    ) { uri -> saveTarget = uri }

    // Saving streams the app-internal copy to the SAF-picked location.
    LaunchedEffect(saveTarget) {
        val target = saveTarget ?: return@LaunchedEffect
        saveTarget = null
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                File(attachment.path).inputStream().use { input ->
                    context.contentResolver.openOutputStream(target)?.use { output ->
                        input.copyTo(output)
                    } ?: error("no output stream")
                }
            }.isSuccess
        }
        Toast.makeText(
            context,
            if (saved) R.string.viewer_saved else R.string.viewer_save_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.fillMaxSize()) {
                ViewerTopBar(
                    onSave = { saveLauncher.launch(attachment.displayName) },
                    onClose = onDismiss,
                    contentColor = Color.White,
                )
                val image = decoded.value
                when {
                    image != null -> ZoomableImage(
                        bitmap = image.bitmap,
                        contentDescription = attachment.displayName,
                        modifier = Modifier.fillMaxSize(),
                    )
                    loaded && image == null ->
                        ViewerMessage(stringResource(R.string.viewer_load_failed))
                    else -> ViewerMessage(null)
                }
            }
        }
    }
}

/** Full-screen Mermaid render. View only: no script-to-canvas export, so the
 * WebView keeps a single, height-only bridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DiagramViewerDialog(code: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
            Column(modifier = Modifier.fillMaxSize()) {
                ViewerTopBar(onSave = null, onClose = onDismiss, contentColor = Color.White)
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = true
                            settings.allowContentAccess = false
                            settings.allowFileAccessFromFileURLs = false
                            settings.allowUniversalAccessFromFileURLs = false
                            settings.domStorageEnabled = false
                            settings.javaScriptCanOpenWindowsAutomatically = false
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean = true

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean = true
                            }
                            setDownloadListener { _, _, _, _, _ -> Unit }
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.safeBrowsingEnabled = true
                            addJavascriptInterface(
                                object {
                                    @JavascriptInterface
                                    fun setHeight(height: Float) = Unit
                                },
                                "Android",
                            )
                            loadDataWithBaseURL(
                                "file:///android_asset/",
                                diagramDocument(code, htmlLabels = false),
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                    onRelease = { view ->
                        view.stopLoading()
                        view.destroy()
                    },
                )
            }
        }
    }
}

@Composable
private fun ViewerTopBar(onSave: (() -> Unit)?, onClose: () -> Unit, contentColor: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        onSave?.let { save ->
            TextButton(onClick = save) {
                Text(text = stringResource(R.string.menu_save), color = contentColor)
            }
        }
        IconButton(onClick = onClose) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = stringResource(R.string.cd_close_viewer),
                tint = contentColor,
            )
        }
    }
}

@Composable
private fun ViewerMessage(message: String?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (message == null) {
            CircularProgressIndicator(color = Color.White)
        } else {
            Text(text = message, color = Color.White)
        }
    }
}

@Composable
private fun ZoomableImage(bitmap: Bitmap, contentDescription: String?, modifier: Modifier = Modifier) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val imageBitmap = remember(bitmap) { bitmap.asImageBitmap() }
    Box(
        modifier = modifier.pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
                scale = (scale
 * zoom).coerceIn(1f, 6f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = imageBitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
        )
    }
}
