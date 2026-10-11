@file:OptIn(ExperimentalMaterial3Api::class)

package com.laundrycare.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistAddCheck
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laundrycare.care.Recommend
import com.laundrycare.care.Washer
import com.laundrycare.data.DryClean
import com.laundrycare.data.Garment

enum class ClosetFilter(val label: String) {
    ALL("All"), MACHINE("Machine wash"), HAND("Hand wash"), NO_TUMBLE("No tumble dry"), DRY_CLEAN("Dry clean"), CHECK("Needs a check");

    fun matches(g: Garment): Boolean = when (this) {
        ALL -> true
        MACHINE -> g.care.machineWash && !g.care.doNotWash
        HAND -> g.care.handWashOnly
        NO_TUMBLE -> g.care.doNotTumbleDry || (!g.care.tumbleDry && g.care.naturalDry != null)
        DRY_CLEAN -> g.care.dryCleanOnly || (g.care.dryClean != null && g.care.dryClean != DryClean.DO_NOT)
        CHECK -> g.care.lowConfidence.isNotEmpty()
    }
}

fun washSubtitle(g: Garment, washer: Washer): String {
    val w = Recommend.wash(g.care, g.name, washer)
    return w.program?.let { "Wash: ${it.name}" } ?: if (g.care.doNotWash) "Do not wash" else "Dry clean only"
}

@Composable
fun ClosetScreen(
    vm: ClosetViewModel,
    onOpen: (String) -> Unit,
    onAdd: () -> Unit,
    onSettings: () -> Unit,
    onTips: () -> Unit,
    onPlan: () -> Unit,
    onSnap: (Uri) -> Unit,
    onResumeDraft: () -> Unit,
) {
    val garments by vm.garments.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(ClosetFilter.ALL) }
    var sortAz by rememberSaveable { mutableStateOf(false) }
    var showSource by remember { mutableStateOf(false) }
    val picker = rememberPhotoPicker { uris -> uris.firstOrNull()?.let(onSnap) }
    val washer = settings.machines.washer

    val shown = remember(garments, query, filter, sortAz) {
        val q = query.trim().lowercase()
        garments
            .filter { filter.matches(it) }
            .filter {
                q.isEmpty() || it.name.lowercase().contains(q) || it.care.fabric.lowercase().contains(q) ||
                    it.care.specialNotes.lowercase().contains(q) || it.description.lowercase().contains(q)
            }
            .let { list -> if (sortAz) list.sortedBy { it.name.lowercase() } else list }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Closet Care")
                        Text("Your wardrobe's wash & dry guide", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    if (garments.size > 1) {
                        IconButton(onClick = { sortAz = !sortAz }) {
                            Icon(Icons.Default.SortByAlpha, contentDescription = if (sortAz) "Sorted A to Z" else "Sorted by date added",
                                tint = if (sortAz) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    IconButton(onClick = onTips) { Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = "Laundry tips") }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, contentDescription = "Settings") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, contentDescription = null) }, text = { Text("Add garment") })
        },
        bottomBar = {
            if (garments.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { showSource = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CenterFocusStrong, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Snap to find")
                        }
                        FilledTonalButton(onClick = onPlan, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.PlaylistAddCheck, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Plan a load")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            vm.draft?.let { d ->
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (d.editing) "Unfinished edit" else "Unfinished garment", fontWeight = FontWeight.SemiBold)
                            Text(d.name.ifBlank { "${d.garmentPhotos.size + d.tagPhotos.size} photo(s) taken" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { vm.discardDraft() }) { Text("Discard") }
                        Button(onClick = onResumeDraft) { Text("Continue") }
                    }
                }
            }
            if (!settings.hasKey) {
                Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("One-time setup", fontWeight = FontWeight.SemiBold)
                        Text("Add your ${settings.provider.label} API key so the app can read care tags and find garments from a photo.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onSettings, modifier = Modifier.padding(top = 4.dp)) { Text("Add API key") }
                    }
                }
            }
            if (garments.isEmpty()) {
                EmptyState(
                    "Your closet is empty",
                    "Add a garment: snap it plus its care tag, and the app decodes how to wash and dry it on your machines.",
                )
                return@Column
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search name, fabric, notes") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear search") }
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                items(ClosetFilter.entries) { f ->
                    FilterChip(selected = f == filter, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("Nothing matches that search.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(shown, key = { it.id }) { g ->
                        GarmentTile(g, washSubtitle(g, washer), vm) { onOpen(g.id) }
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) { BottomSpacer(80) }
                }
            }
        }
    }
    if (showSource) {
        PhotoSourceDialog("Snap it to find it", onDismiss = { showSource = false }, onCamera = picker.camera, onGallery = picker.gallery)
    }
}

@Composable
private fun GarmentTile(g: Garment, subtitle: String, vm: ClosetViewModel, onClick: () -> Unit) {
    Card(onClick = onClick) {
        Column {
            PhotoImage(g.coverPhoto?.let(vm::photoFile), Modifier.fillMaxWidth().aspectRatio(1f))
            Column(Modifier.padding(10.dp)) {
                Text(g.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
