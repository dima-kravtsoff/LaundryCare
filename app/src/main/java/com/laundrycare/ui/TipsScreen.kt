@file:OptIn(ExperimentalMaterial3Api::class)

package com.laundrycare.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private class TipSection(val title: String, val items: List<Pair<String, String>>)

/** General laundry knowledge, written for the owner's high-efficiency front-loader pair. */
private val TIPS = listOf(
    TipSection("Boosters & additives", listOf(
        "Oxygen bleach, the colour-safe one" to "Sodium percarbonate (the OxiClean type). This is what a tag means by “non-chlorine bleach”: the triangle with two diagonal lines. It lifts stains and brightens without stripping colour, but it needs warmth, around 40 °C and up. In a cold wash it does almost nothing, so pre-soak in warm water instead of dumping it in the drum.",
        "Colour-catcher sheets" to "Toss one in loose with the load. They absorb dye that bleeds into the water before it can settle on your other clothes. Best for new items, dark denim, and anything red for its first few washes. They reduce risk, they don't remove it: don't gamble a white shirt on one.",
        "White vinegar" to "About half a cup in the fabric-softener compartment. Helps strip detergent residue, softens hard water, and cuts the musty smell out of towels. Use it occasionally, not every wash: it is an acid, and several appliance makers caution about long-term effects on seals and hoses.",
        "Baking soda (bicarbonate)" to "A mild deodoriser: about half a cup straight into the drum. Helpful on smelly gym kit, but it is gentle stuff and will not replace detergent or shift real stains.",
        "Washing soda (sodium carbonate)" to "A much stronger alkali than baking soda, and a genuine cleaning booster in hard water. Never use it on wool or silk: alkali attacks protein fibres and will wreck them permanently.",
        "The vinegar + baking soda myth" to "Mixed together they neutralise each other: you get fizz, then salty water, and lose the benefit of both. Use one or the other in a given wash, never both.",
    )),
    TipSection("Detergent", listOf(
        "Use HE detergent" to "Your Bosch is a high-efficiency front-loader. Regular detergent produces too much foam, and that foam cushions the load so clothes rub against suds instead of water. It cleans worse, not better.",
        "You are probably using too much" to "This is the single most common laundry mistake. Excess detergent never rinses out: it leaves residue that stiffens fabric, traps odour, irritates skin and feeds mould inside the machine. Dose for the load size and your water hardness, and treat the cap's line as a maximum.",
        "Powder vs liquid" to "Powder generally does better on whites and in hard water. Liquid handles grease better and dissolves properly in cold washes.",
        "Skip fabric softener on" to "Towels (it coats the fibres and destroys absorbency), activewear and microfibre (it clogs the wicking that makes them work), and children's flame-resistant sleepwear (it can reduce the flame resistance).",
    )),
    TipSection("Looking after the machines", listOf(
        "Run Drum Clean monthly" to "Your washer has the program. Run it empty on Extra Hot. It clears the biofilm that builds up from cold washes and detergent residue, the usual cause of a machine that smells.",
        "Leave the door and drawer ajar" to "The commonest front-loader complaint is a mouldy smell, and this prevents it. Water sits in the rubber gasket after every wash; a closed door keeps it damp and dark.",
        "Wipe the door gasket" to "Pull back the fold of the rubber seal and dry it. Hair, grit and a surprising amount of water collect in there.",
        "Clean the detergent drawer" to "Every month or two. Residue builds into a sludge that stops the dispenser flushing properly.",
        "Check the drain pump filter" to "A few times a year. Coins, pins and lint collect there, and a blocked filter shows up as a cycle that will not drain or spin.",
        "Dryer: lint filter every single load" to "And because yours is a ventless condenser, it also has a secondary filter and condenser unit that need rinsing periodically. A condenser dryer that has started taking forever is almost always telling you this.",
        "Don't leave wet laundry in the drum" to "A few hours is all it takes to go sour, and then it needs rewashing.",
    )),
    TipSection("Before you load", listOf(
        "Close everything that can catch" to "Zip zips, fasten hooks and Velcro, undo buttons. Open zips act like little saws on knitwear.",
        "Turn dark and printed items inside out" to "Protects prints from abrasion and keeps dark fabric from picking up the grey sheen of surface wear.",
        "Empty the pockets" to "A tissue means re-washing everything. A pen means replacing it.",
        "Sort by weight and soil, not just colour" to "Towels and jeans in with t-shirts will abrade the lighter items regardless of whether the colours match.",
        "Never tumble dry a stain you have not removed" to "Heat sets most stains permanently. Check the garment while it is wet, and re-treat before it goes anywhere near the dryer.",
    )),
    TipSection("Stain first aid", listOf(
        "Blot, never rub" to "Rubbing drives the stain deeper into the weave and can fuzz the fabric surface.",
        "Blood, egg, dairy, sweat: cold water only" to "These are protein stains. Hot water cooks the protein into the fibre and the mark becomes permanent.",
        "Grease and oil: dish soap" to "Work a drop into the mark, leave it a few minutes, then wash at the warmest temperature the tag allows.",
        "Wine, coffee, tea: rinse cold first" to "These are tannin stains. Flush with cold water before anything else; starting with soap can set them.",
        "Test somewhere hidden" to "Inside a seam or hem, before you put any treatment on a visible area.",
    )),
    TipSection("Safety", listOf(
        "Never mix chlorine bleach with vinegar or any acid" to "It releases chlorine gas, which is genuinely dangerous in an enclosed room like a utility space or bathroom.",
        "Never mix chlorine bleach with ammonia" to "Or with cleaners containing it. This produces toxic chloramine vapour.",
        "Respect the bleach symbol" to "A triangle with two diagonal lines means non-chlorine only. Chlorine on those fabrics will strip the colour or weaken the fibre, and the damage cannot be undone.",
        "Keep detergent pods away from children" to "They look exactly like sweets, and the concentrated contents cause serious harm if bitten.",
    )),
)

@Composable
fun TipsScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar("Laundry tips", "Additives, machine care, stains", onBack = onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Note(NoteKind.INFO, "General guidance. Where it disagrees with a garment's own care label, the label wins.", Modifier.padding(top = 8.dp))
            TIPS.forEach { section ->
                SectionTitle(section.title)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        section.items.forEachIndexed { i, (heading, body) ->
                            if (i > 0) HorizontalDivider()
                            Column(Modifier.padding(vertical = 10.dp)) {
                                Text(heading, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                            }
                        }
                    }
                }
            }
            BottomSpacer(40)
        }
    }
}
