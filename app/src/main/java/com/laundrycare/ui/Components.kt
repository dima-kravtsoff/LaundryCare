@file:OptIn(ExperimentalMaterial3Api::class)

package com.laundrycare.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.laundrycare.LaundryCareApp
import java.io.File

/* ---------- app bars ---------- */

@Composable
fun BackTopBar(title: String, subtitle: String? = null, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } },
        actions = actions,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        letterSpacing = 1.sp,
        modifier = modifier.padding(top = 20.dp, bottom = 8.dp),
    )
}

/* ---------- notes ---------- */

enum class NoteKind { INFO, WARN, BAD, GOOD }

@Composable
fun Note(kind: NoteKind, text: String, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val (fg, bg) = when (kind) {
        NoteKind.INFO -> Tone.info() to Tone.infoSoft()
        NoteKind.WARN -> Tone.warn() to Tone.warnSoft()
        NoteKind.BAD -> Tone.bad() to Tone.badSoft()
        NoteKind.GOOD -> Tone.good() to Tone.goodSoft()
    }
    val shown = icon ?: when (kind) {
        NoteKind.INFO -> Icons.Default.Info
        NoteKind.WARN -> Icons.Default.Warning
        NoteKind.BAD -> Icons.Default.ErrorOutline
        NoteKind.GOOD -> Icons.Default.CheckCircle
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(shown, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

/* ---------- rows ---------- */

@Composable
fun SettingRow(label: String, value: String, reason: String? = null, big: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(110.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            Text(
                value,
                fontWeight = FontWeight.Bold,
                style = if (big) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.End,
            )
            if (!reason.isNullOrBlank()) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End)
            }
        }
    }
}

@Composable
fun Pills(items: List<String>, color: Color, background: Color) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        items.forEach {
            Text(
                it,
                color = color,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(background)
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }
    }
}

@Composable
fun ConfidenceChip(confidence: String) {
    val (fg, bg) = when (confidence) {
        "high" -> Tone.good() to Tone.goodSoft()
        "medium" -> Tone.warn() to Tone.warnSoft()
        else -> Tone.bad() to Tone.badSoft()
    }
    Text(
        "$confidence confidence".uppercase(),
        color = fg,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).background(bg).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/* ---------- photos ---------- */

@Composable
fun PhotoImage(file: File?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        if (file != null && file.exists()) {
            AsyncImage(model = file, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Default.Checkroom, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
        }
    }
}

/** A strip of photos with add, remove, and tap-to-make-main. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoStrip(
    label: String,
    hint: String,
    photos: List<File>,
    max: Int,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onMakeMain: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("${photos.size}/$max", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(photos) { i, file ->
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(onClick = { if (i > 0) onMakeMain(i) }, onLongClick = { onRemove(i) }),
                ) {
                    PhotoImage(file, Modifier.fillMaxSize())
                    if (i == 0) {
                        Text(
                            "MAIN",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.Black.copy(alpha = 0.6f))
                                .padding(horizontal = 7.dp, vertical = 2.dp),
                        )
                    }
                    Box(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.6f))
                            .clickable { onRemove(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Remove photo", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
            }
            if (photos.size < max) {
                item {
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                            .clickable(onClick = onAdd),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Add", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (hint.isNotBlank()) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Camera or gallery, through the system apps, so the app needs no camera permission. */
class PhotoPicker(val camera: () -> Unit, val gallery: () -> Unit)

@Composable
fun rememberPhotoPicker(onImages: (List<Uri>) -> Unit): PhotoPicker {
    val context = LocalContext.current
    val photos = (context.applicationContext as LaundryCareApp).container.photos
    // Saveable, because the camera app can push this process out of memory.
    var pendingCapture by rememberSaveable { mutableStateOf<String?>(null) }
    val latest = rememberUpdatedState(onImages)
    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val path = pendingCapture
        pendingCapture = null
        if (taken && path != null) latest.value(listOf(Uri.fromFile(File(path))))
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { uris ->
        if (uris.isNotEmpty()) latest.value(uris)
    }
    return remember {
        PhotoPicker(
            camera = {
                val file = photos.newCaptureFile()
                pendingCapture = file.absolutePath
                try {
                    takePicture.launch(photos.uriFor(file))
                } catch (e: ActivityNotFoundException) {
                    pendingCapture = null
                    pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
            },
            gallery = { pickMany.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        )
    }
}

@Composable
fun PhotoSourceDialog(title: String, onDismiss: () -> Unit, onCamera: () -> Unit, onGallery: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                SourceOption(Icons.Default.PhotoCamera, "Take a photo") { onDismiss(); onCamera() }
                SourceOption(Icons.Default.PhotoLibrary, "Choose from gallery") { onDismiss(); onGallery() }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SourceOption(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/* ---------- form fields ---------- */

@Composable
fun <T> DropdownField(
    label: String,
    value: T,
    options: List<Pair<T, String>>,
    unsure: Boolean = false,
    modifier: Modifier = Modifier,
    onChange: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val warn = Tone.warn()
    Column(modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Box {
            OutlinedTextField(
                // A value outside the listed choices (a 35 °C tag, a hand-edited backup) is still shown, not blanked.
                value = options.firstOrNull { it.first == value }?.second ?: value?.toString()?.let { if (value is Int) "$it°C" else it } ?: "",
                onValueChange = {},
                readOnly = true,
                label = { Text(if (unsure) "$label · CHECK THIS" else label) },
                trailingIcon = { Icon(Icons.Default.ArrowDropDown, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                colors = if (unsure) androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = warn, focusedBorderColor = warn, unfocusedLabelColor = warn, focusedLabelColor = warn,
                ) else androidx.compose.material3.OutlinedTextFieldDefaults.colors(),
            )
            Box(Modifier.matchParentSize().clickable { expanded = true })
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (v, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; onChange(v) })
                }
            }
        }
        if (unsure) {
            Text(
                "The AI inferred this rather than reading it clearly. Confirm it against the tag.",
                style = MaterialTheme.typography.bodySmall, color = warn, modifier = Modifier.padding(top = 4.dp, start = 4.dp),
            )
        }
    }
}

/* ---------- list rows ---------- */

@Composable
fun GarmentRow(
    name: String,
    subtitle: String?,
    photo: File?,
    selected: Boolean? = null,
    highlighted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 1.dp,
        border = if (highlighted || selected == true) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            PhotoImage(photo, Modifier.size(60.dp).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
        }
    }
}

@Composable
fun EmptyState(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Default.Checkroom, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Keeps a list from ending under the floating buttons. */
@Composable
fun BottomSpacer(height: Int = 120) {
    Spacer(Modifier.height(height.dp))
}

/** A photo you can pinch to zoom and drag, for reading a tag at full size. */
@Composable
fun ZoomableImage(file: File, modifier: Modifier = Modifier) {
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }
    // At the stored size, not the screen's: the whole point of zooming in on a
    // tag is to count the dots, and a screen-sized downsample blurs them.
    val context = LocalContext.current
    AsyncImage(
        model = coil3.request.ImageRequest.Builder(context).data(file).size(coil3.size.Size.ORIGINAL).build(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier
            .transformable(state)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y),
    )
}

@Composable
fun FourThreeBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(RoundedCornerShape(16.dp))) { content() }
}

@Composable
fun LabelValueList(items: List<Pair<String, String>>) {
    items.forEach { (k, v) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(k, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
            Text(v, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
fun ThumbRow(files: List<File>, selected: Int, onSelect: (Int) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        items(files.size) { i ->
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (i == selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable { onSelect(i) },
            ) { PhotoImage(files[i], Modifier.fillMaxSize()) }
        }
    }
}

@Composable
fun BulletList(lines: List<String>, modifier: Modifier = Modifier) {
    Column(modifier) { lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
}
