package com.dre.carbook.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.dre.carbook.newId
import com.dre.carbook.repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val MAX_SIDE = 1600
private const val AUTHORITY = "com.dre.carbook.files"

/** Блок «Фото чеков» в форме расхода: миниатюры + снять / выбрать из галереи. */
@Composable
fun ReceiptPhotos(photos: List<String>, onChange: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var viewing by remember { mutableStateOf<String?>(null) }
    var cameraFile by remember { mutableStateOf<File?>(null) }

    fun import(uris: List<Uri>, cleanup: File? = null) {
        if (uris.isEmpty()) { cleanup?.delete(); return }
        busy = true
        scope.launch {
            val names = withContext(Dispatchers.IO) { uris.mapNotNull { runCatching { saveReceipt(ctx, it) }.getOrNull() } }
            cleanup?.delete()
            busy = false
            if (names.size < uris.size) Toast.makeText(ctx, "Не удалось прочитать изображение", Toast.LENGTH_SHORT).show()
            onChange(photos + names)
        }
    }

    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        cameraFile = null
        if (ok && f != null && f.length() > 0) import(listOf(Uri.fromFile(f)), cleanup = f) else f?.delete()
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { import(it) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Фото чеков", style = MaterialTheme.typography.labelLarge)
        if (photos.isNotEmpty() || busy) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                photos.forEach { name -> Thumb(name, Modifier.size(84.dp)) { viewing = name } }
                if (busy) Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                val dir = File(ctx.cacheDir, "camera").apply { mkdirs() }
                val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
                cameraFile = f
                runCatching { camera.launch(FileProvider.getUriForFile(ctx, AUTHORITY, f)) }
                    .onFailure { Toast.makeText(ctx, "Нет приложения камеры", Toast.LENGTH_SHORT).show() }
            }, enabled = !busy) { Text("📷 Снять") }
            OutlinedButton(onClick = {
                gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }, enabled = !busy) { Text("🖼 Из галереи") }
        }
    }

    viewing?.let { name ->
        PhotoViewer(name, onDismiss = { viewing = null }, onDelete = { onChange(photos - name); viewing = null })
    }
}

@Composable
fun Thumb(name: String, modifier: Modifier, onClick: () -> Unit) {
    val bmp = rememberBitmap(name, 300)
    Box(modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable(onClick = onClick),
        contentAlignment = Alignment.Center) {
        if (bmp != null) Image(bmp, "Фото чека", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text("🧾")
    }
}

@Composable
private fun rememberBitmap(name: String, maxSide: Int): ImageBitmap? {
    val ctx = LocalContext.current
    val state by produceState<ImageBitmap?>(null, name, maxSide) {
        value = withContext(Dispatchers.IO) {
            ctx.repo.photoFile(name)?.takeIf { it.exists() }?.let { decodeSampled(it, maxSide) }?.asImageBitmap()
        }
    }
    return state
}

/** Полноэкранный просмотр с масштабированием жестами. onDelete = null — без кнопки удаления. */
@Composable
fun PhotoViewer(name: String, onDismiss: () -> Unit, onDelete: (() -> Unit)?) {
    val ctx = LocalContext.current
    val bmp = rememberBitmap(name, 2400)
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val tr = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            if (bmp != null) Image(
                bmp, "Фото чека",
                Modifier.fillMaxSize().transformable(tr).graphicsLayer {
                    scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y
                },
                contentScale = ContentScale.Fit,
            ) else CircularProgressIndicator(Modifier.align(Alignment.Center))
            Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(4.dp)) {
                IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Закрыть", tint = Color.White) }
                Box(Modifier.weight(1f))
                IconButton(onClick = { sharePhoto(ctx, name) }) { Icon(Icons.Filled.Share, "Поделиться", tint = Color.White) }
                if (onDelete != null) IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Удалить", tint = Color.White) }
            }
        }
    }
}

private fun sharePhoto(ctx: Context, name: String) {
    val f = ctx.repo.photoFile(name)?.takeIf { it.exists() } ?: return
    val uri = FileProvider.getUriForFile(ctx, AUTHORITY, f)
    val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, "Отправить чек").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Копирует изображение в каталог чеков: уменьшает до [MAX_SIDE], поворачивает по EXIF, JPEG 85%. */
private fun saveReceipt(ctx: Context, uri: Uri): String {
    val cr = ctx.contentResolver
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    cr.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
    require(opts.outWidth > 0 && opts.outHeight > 0)
    var sample = 1
    while (maxOf(opts.outWidth, opts.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2
    var bmp = cr.openInputStream(uri)!!.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    } ?: error("decode")
    val rotation = runCatching {
        cr.openInputStream(uri)!!.use { ExifInterface(it) }
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL).let {
        when (it) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
    }
    val scale = MAX_SIDE.toFloat() / maxOf(bmp.width, bmp.height)
    if (rotation != 0f || scale < 1f) {
        val m = Matrix().apply { if (scale < 1f) postScale(scale, scale); postRotate(rotation) }
        bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }
    val name = "${newId()}.jpg"
    ctx.repo.photoFile(name)!!.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    return name
}

private fun decodeSampled(f: File, maxSide: Int): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= maxSide) s *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}
