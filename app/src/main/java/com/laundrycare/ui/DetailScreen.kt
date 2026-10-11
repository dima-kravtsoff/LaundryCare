@file:OptIn(ExperimentalMaterial3Api::class)

package com.laundrycare.ui

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laundrycare.care.CareText
import com.laundrycare.care.Recommend
import com.laundrycare.data.Garment
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    vm: ClosetViewModel,
    id: String,
    onBack: () -> Unit,
    onEdit: (Garment) -> Unit,
    onReread: (Garment) -> Unit,
    onApplyTo: () -> Unit,
    onGone: () -> Unit,
) {
    val garments by vm.garments.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val g = garments.firstOrNull { it.id == id }
    if (g == null) {
        Scaffold(topBar = { BackTopBar("Care guide", onBack = onBack) }) { padding ->
            Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This garment is no longer in the closet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    val machines = settings.machines
    val wash = remember(g, machines) { Recommend.wash(g.care, g.name, machines.washer, g.colorGroup) }
    val dry = remember(g, machines) { Recommend.dry(g.care, g.name, machines.dryer) }
    var confirmDelete by remember { mutableStateOf(false) }
    val context = LocalContext.current

    Scaffold(
        topBar = {
            BackTopBar("Care guide", g.name, onBack = onBack, actions = {
                IconButton(onClick = {
                    val text = CareText.share(g, wash, dry, machines)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "${g.name}: care guide")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    context.startActivity(Intent.createChooser(send, "Share care guide"))
                }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                IconButton(onClick = { onEdit(g) }) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
            })
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            val photos = g.allPhotos.map(vm::photoFile)
            if (photos.isNotEmpty()) {
                val pager = rememberPagerState { photos.size }
                val scope = rememberCoroutineScope()
                Spacer(Modifier.height(8.dp))
                var fullscreen by remember { mutableStateOf<java.io.File?>(null) }
                // Whole photo, letterboxed: a cropped care tag is a useless care tag.
                FourThreeBox {
                    HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                        PhotoImage(
                            photos[page],
                            Modifier.fillMaxSize().clickable { fullscreen = photos[page] },
                            contentScale = ContentScale.Fit,
                        )
                    }
                }
                fullscreen?.let { file ->
                    Dialog(onDismissRequest = { fullscreen = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        Box(Modifier.fillMaxSize().background(Color.Black).clickable { fullscreen = null }) {
                            ZoomableImage(file, Modifier.fillMaxSize())
                        }
                    }
                }
                if (photos.size > 1) {
                    ThumbRow(photos, pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
                    val bits = buildList {
                        if (g.garmentPhotos.isNotEmpty()) add("${g.garmentPhotos.size} garment")
                        if (g.tagPhotos.isNotEmpty()) add("${g.tagPhotos.size} care tag")
                    }
                    Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                }
            }
            Text(g.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
            if (g.care.fabric.isNotBlank()) Text(g.care.fabric, color = MaterialTheme.colorScheme.onSurfaceVariant)

            val unsure = g.care.unsureLabels
            if (unsure.isNotEmpty()) {
                Note(
                    NoteKind.WARN,
                    "Unconfirmed: ${unsure.joinToString(", ")}. The AI inferred ${if (unsure.size == 1) "this" else "these"} rather than reading " +
                        "${if (unsure.size == 1) "it" else "them"} clearly. Tap Edit to check against the tag.",
                    Modifier.padding(top = 10.dp),
                )
            }

            SectionTitle("How to wash it")
            WashCard(wash, machines.washer)
            SectionTitle("How to dry it")
            DryCard(dry, machines.dryer)
            SectionTitle("What the tag says")
            TagSummaryCard(g.care, g)

            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = { onEdit(g) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Edit, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Edit this garment")
            }
            if (g.tagPhotos.isNotEmpty() && settings.hasKey) {
                OutlinedButton(onClick = { onReread(g) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Re-read the tag with AI")
                }
            }
            if (garments.size > 1) {
                OutlinedButton(onClick = onApplyTo, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Apply this care to other garments")
                }
            }
            TextButton(
                onClick = { confirmDelete = true },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Icon(Icons.Default.Delete, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Remove from closet")
            }
            BottomSpacer(40)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove ${g.name}?") },
            text = { Text("Its photos and care details are deleted from this device.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.delete(g.id) { onGone() } }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
