@file:OptIn(ExperimentalMaterial3Api::class)

package com.laundrycare.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.laundrycare.care.DryMode
import com.laundrycare.care.LoadPlan
import com.laundrycare.care.MachinePair
import com.laundrycare.data.Garment

@Composable
fun PlanLoadScreen(
    vm: ClosetViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onSnap: (Uri) -> Unit,
) {
    val garments by vm.garments.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val plan = vm.plan
    var query by rememberSaveable { mutableStateOf("") }
    var showSource by remember { mutableStateOf(false) }
    val picker = rememberPhotoPicker { uris -> uris.firstOrNull()?.let(onSnap) }
    val selection = vm.planSelection

    BackHandler(enabled = plan != null) { vm.resetPlanResult() }

    Scaffold(
        topBar = {
            BackTopBar(
                title = "Plan a load",
                subtitle = if (plan == null) "Tick everything that's going in" else "${plan.load.size} of ${selection.size} can run together",
                onBack = { if (plan != null) vm.resetPlanResult() else onBack() },
                actions = { if (selection.isNotEmpty()) TextButton(onClick = vm::clearPlan) { Text("Clear") } },
            )
        },
        bottomBar = {
            if (plan == null) {
                Surface(tonalElevation = 3.dp) {
                    Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { showSource = true }) {
                            Icon(Icons.Default.CenterFocusStrong, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Snap to add")
                        }
                        Button(onClick = vm::checkLoad, enabled = selection.isNotEmpty(), modifier = Modifier.weight(1f)) {
                            Text("Check this load" + if (selection.isEmpty()) "" else " (${selection.size})")
                        }
                    }
                }
            }
        },
    ) { padding ->
        if (plan == null) {
            val washer = settings.machines.washer
            val q = query.trim().lowercase()
            val shown = garments.filter { q.isEmpty() || it.name.lowercase().contains(q) || it.care.fabric.lowercase().contains(q) }
            LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                if (garments.isEmpty()) {
                    item { EmptyState("Nothing to plan yet", "Add some garments first, then come back to plan a load.") }
                } else {
                    item {
                        OutlinedTextField(
                            value = query, onValueChange = { query = it }, singleLine = true,
                            placeholder = { Text("Search") },
                            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, contentDescription = "Clear") } },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        )
                    }
                    items(shown, key = { it.id }) { g ->
                        val checked = g.id in selection
                        GarmentRow(
                            name = g.name,
                            subtitle = listOfNotNull(g.care.fabric.takeIf { it.isNotBlank() }, washSubtitle(g, washer)).joinToString(" · "),
                            photo = g.coverPhoto?.let(vm::photoFile),
                            selected = checked,
                            trailing = { Checkbox(checked = checked, onCheckedChange = { vm.togglePlan(g.id) }) },
                        ) { vm.togglePlan(g.id) }
                    }
                }
                item { BottomSpacer(40) }
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
                PlanResult(plan, settings.machines, vm, onOpen, depth = 0)
                BottomSpacer(40)
            }
        }
    }
    if (showSource) {
        PhotoSourceDialog("Snap to add", onDismiss = { showSource = false }, onCamera = picker.camera, onGallery = picker.gallery)
    }
}

@Composable
private fun MiniRow(g: Garment, vm: ClosetViewModel, reason: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PhotoImage(g.coverPhoto?.let(vm::photoFile), Modifier.size(44.dp).clip(RoundedCornerShape(8.dp)))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(g.name, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (!reason.isNullOrBlank()) {
                Text(reason, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PlanResult(plan: LoadPlan, machines: MachinePair, vm: ClosetViewModel, onOpen: (String) -> Unit, depth: Int) {
    if (plan.load.isEmpty()) {
        Note(NoteKind.WARN, "Nothing in this selection can go in the washer together.", Modifier.padding(top = 12.dp))
    } else {
        SectionTitle(if (depth == 0) "Run these ${plan.load.size} together" else "Then run these ${plan.load.size} together")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                plan.load.forEach { MiniRow(it, vm) { onOpen(it.id) } }
            }
        }
        plan.sizeWarning?.let { Note(NoteKind.WARN, it, Modifier.padding(top = 10.dp)) }

        if (plan.limiters.isNotEmpty()) {
            SectionTitle("What sets the limits")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    plan.limiters.forEachIndexed { i, l ->
                        if (i > 0) HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PhotoImage(l.garment.coverPhoto?.let(vm::photoFile), Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)))
                            Spacer(Modifier.width(10.dp))
                            Column {
                                Text("${l.setting}: ${l.value}", fontWeight = FontWeight.SemiBold)
                                Text("because of the ${l.garment.name} (${l.detail})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Text(
                        "Pull one of these out and the rest can run less gently.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }

        plan.wash?.let {
            SectionTitle("Washer")
            WashCard(it, machines.washer)
        }
        if (plan.airDry.isNotEmpty()) {
            SectionTitle("Air dry these")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    plan.airDry.forEach { g ->
                        val d = vm.dryPlan(g)
                        MiniRow(g, vm, reason = if (d.mode == DryMode.AIR) d.airText else null) { onOpen(g.id) }
                    }
                }
            }
            Note(NoteKind.INFO, "They can share the wash, but not the dryer.", Modifier.padding(top = 8.dp))
        }
        plan.dry?.let {
            SectionTitle(if (plan.airDry.isEmpty()) "Dryer" else "Dryer, for the rest")
            DryCard(it, machines.dryer)
        }
    }

    if (plan.pulled.isNotEmpty()) {
        SectionTitle("Pull these out")
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                plan.pulled.forEach { p -> MiniRow(p.garment, vm, reason = p.reason) { onOpen(p.garment.id) } }
            }
        }
    }

    plan.secondLoad?.let { second ->
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(
            if (depth == 0) "Second load" else "Another load",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp),
        )
        PlanResult(second, machines, vm, onOpen, depth + 1)
    }
}
