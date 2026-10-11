@file:OptIn(ExperimentalMaterial3Api::class)

package com.laundrycare.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

@Composable
fun MatchScreen(
    vm: ClosetViewModel,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
    onAddNew: () -> Unit,
    onPickedForPlan: () -> Unit,
) {
    val m = vm.match
    val leave = { vm.clearMatch(); onBack() }
    Scaffold(
        topBar = {
            BackTopBar(
                title = if (m?.forPlan == true) "Add to the load" else "Which one is it?",
                subtitle = "Matching against your closet",
                onBack = leave,
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            if (m == null) {
                Text("Nothing to match.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 24.dp))
                return@Column
            }
            Spacer(Modifier.height(8.dp))
            PhotoImage(m.query, Modifier.size(180.dp).align(Alignment.CenterHorizontally).clip(RoundedCornerShape(14.dp)))
            Spacer(Modifier.height(12.dp))
            when {
                m.busy -> {
                    Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.5.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Looking through your closet…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                m.error != null -> {
                    Note(NoteKind.BAD, m.error)
                    if (m.retryable) {
                        Button(onClick = vm::retryMatch, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) { Text("Try again") }
                    }
                    OutlinedButton(onClick = leave, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Browse the closet instead") }
                }
                m.hits.isEmpty() -> {
                    Note(NoteKind.WARN, "Nothing in your closet matches this photo. If it's new, add it; otherwise pick it from the list.")
                    if (!m.forPlan) {
                        Button(onClick = onAddNew, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Add it as a new garment")
                        }
                    }
                    OutlinedButton(onClick = leave, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Browse the closet") }
                }
                else -> {
                    val top = m.hits.first()
                    SectionTitle(if (top.candidate.confidence == "high") "This looks like" else "Best guess")
                    HitRow(top, highlighted = true, vm = vm, forPlan = m.forPlan, onOpen = onOpen, onPicked = onPickedForPlan)
                    if (m.hits.size > 1) {
                        SectionTitle("Or did you mean")
                        m.hits.drop(1).forEach { HitRow(it, highlighted = false, vm = vm, forPlan = m.forPlan, onOpen = onOpen, onPicked = onPickedForPlan) }
                    }
                    Spacer(Modifier.height(10.dp))
                    if (top.candidate.confidence != "high") {
                        Note(NoteKind.INFO, if (m.forPlan) "Not sure? Tap the right one to add it to the load, or go back and tick it from the list."
                        else "Not sure? Tap an item to see its care guide, or browse the full closet.")
                    }
                    if (m.truncated) {
                        Note(NoteKind.INFO, "Only your ${ClosetViewModel.MAX_MATCH_ITEMS} most recent garments were searched.", Modifier.padding(top = 8.dp))
                    }
                    OutlinedButton(onClick = leave, modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
                        Text(if (m.forPlan) "None of these" else "None of these; browse the closet")
                    }
                }
            }
            BottomSpacer(40)
        }
    }
}

@Composable
private fun HitRow(hit: MatchHit, highlighted: Boolean, vm: ClosetViewModel, forPlan: Boolean, onOpen: (String) -> Unit, onPicked: () -> Unit) {
    val g = hit.garment
    GarmentRow(
        name = g.name,
        subtitle = hit.candidate.reason.ifBlank { g.care.fabric },
        photo = g.coverPhoto?.let(vm::photoFile),
        highlighted = highlighted,
        trailing = { ConfidenceChip(hit.candidate.confidence) },
    ) {
        if (forPlan) {
            vm.addToPlan(g.id)
            vm.clearMatch()
            onPicked()
        } else {
            onOpen(g.id)
        }
    }
}
