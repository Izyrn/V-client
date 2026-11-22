package com.vclient.android.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.vclient.android.demo.DemoGame
import com.vclient.android.demo.Inventory
import com.vclient.android.demo.ItemStack
import com.vclient.android.demo.Recipes
import com.vclient.android.demo.Village
import com.vclient.android.demo.VoxelRenderer
import com.vclient.android.ui.theme.Gold
import com.vclient.android.ui.theme.Midnight
import com.vclient.android.ui.theme.Offline
import com.vclient.android.ui.theme.Online
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.floor
import kotlin.math.hypot

const val DEMO_NOTICE = "This is a VYRN Demo World. Install Minecraft separately for the full game experience."

/**
 * The offline Demo World: an original voxel sandbox with no Minecraft content. A 512 x 512 block island with two
 * biomes and a village. Nothing is handed out: break blocks, collect materials, open the Bag and craft tools.
 * The world is saved on the device automatically.
 */
@Composable
fun DemoScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    val file = remember { File(context.filesDir, "demo_world.vcw") }
    var game by remember { mutableStateOf<DemoGame?>(null) }
    // Building 32 x 32 chunks (or rebuilding a saved world) takes a moment, so it runs off the main thread.
    LaunchedEffect(Unit) { game = withContext(Dispatchers.Default) { DemoGame.loadOrCreate(file) } }

    val ready = game
    if (ready == null) {
        BackHandler { onExit() }
        Box(Modifier.fillMaxSize().background(Midnight), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                CircularProgressIndicator(color = Gold)
                Text("Generating your world…", color = Color.White, fontWeight = FontWeight.Bold)
                Text("32 × 32 chunks · Oak Forest, Cherry Blossom and a village", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            }
        }
    } else {
        DemoWorldContent(ready, file, onExit)
    }
}

@Composable
private fun DemoWorldContent(game: DemoGame, file: File, onExit: () -> Unit) {
    val renderer = remember(game) { VoxelRenderer(game.world) }
    var showWelcome by rememberSaveable { mutableStateOf(true) }
    var bagOpen by rememberSaveable { mutableStateOf(false) }
    val paused by rememberUpdatedState(showWelcome || bagOpen)
    var frameId by remember { mutableIntStateOf(0) }
    var uiRev by remember { mutableIntStateOf(0) }
    var fps by remember { mutableIntStateOf(0) }
    var slot by remember { mutableIntStateOf(game.selectedSlot) }
    var info by remember { mutableStateOf("") }
    var hint by remember { mutableStateOf<String?>(null) }
    var image by remember { mutableStateOf<ImageBitmap?>(null) }

    fun leave() {
        game.breakHeld = false; game.placeHeld = false; game.jumpHeld = false
        game.save(file, force = true)
        onExit()
    }
    BackHandler { if (bagOpen) bagOpen = false else leave() }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { game.save(file) }
    DisposableEffect(Unit) { onDispose { game.save(file) } }

    Box(Modifier.fillMaxSize().background(Midnight)) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val aspect = constraints.maxWidth.toFloat() / constraints.maxHeight.coerceAtLeast(1)
            val renderWidth = 288
            val renderHeight = (renderWidth / aspect).toInt().coerceIn(120, 420)

            // The game loop: advance the simulation, ray-cast a low-resolution frame, show it scaled up.
            LaunchedEffect(renderWidth, renderHeight) {
                val pixels = IntArray(renderWidth * renderHeight)
                val bitmap = Bitmap.createBitmap(renderWidth, renderHeight, Bitmap.Config.ARGB_8888)
                image = bitmap.asImageBitmap()
                var last = withFrameNanos { it }
                var frames = 0
                var clock = 0.0
                var infoClock = 1.0
                var sinceSave = 0.0
                var seenRevision = game.revision
                while (isActive) {
                    val now = withFrameNanos { it }
                    val dt = ((now - last) / 1_000_000_000.0).coerceAtMost(0.05)
                    last = now
                    if (!paused) game.tick(dt)
                    withContext(Dispatchers.Default) {
                        renderer.selected = game.target
                        renderer.breakProgress = game.breakProgress
                        renderer.render(pixels, renderWidth, renderHeight, game.camera())
                    }
                    bitmap.setPixels(pixels, 0, renderWidth, 0, 0, renderWidth, renderHeight)
                    frameId++
                    frames++
                    clock += dt
                    infoClock += dt
                    sinceSave += dt
                    if (game.revision != seenRevision) { seenRevision = game.revision; uiRev++ }
                    if (game.hint != hint) hint = game.hint
                    if (clock >= 1.0) { fps = frames; frames = 0; clock = 0.0 }
                    if (infoClock >= 0.25) { infoClock = 0.0; info = locationText(game) }
                    if (sinceSave >= 20.0) { game.save(file); sinceSave = 0.0 }
                }
            }

            // The scene, scaled with hard edges so the blocks stay crisp, plus the crosshair and dig progress.
            Canvas(Modifier.fillMaxSize()) {
                val current = frameId
                image?.let { drawImage(it, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.None) }
                if (current < 0) return@Canvas
                val c = Offset(size.width / 2, size.height / 2)
                val arm = 9.dp.toPx()
                drawLine(Color.White.copy(alpha = 0.85f), Offset(c.x - arm, c.y), Offset(c.x + arm, c.y), strokeWidth = 2.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.85f), Offset(c.x, c.y - arm), Offset(c.x, c.y + arm), strokeWidth = 2.dp.toPx())
                val progress = game.breakProgress
                if (progress > 0.0) {
                    val barWidth = 70.dp.toPx()
                    val top = c.y + 26.dp.toPx()
                    drawRect(Color.Black.copy(alpha = 0.5f), Offset(c.x - barWidth / 2, top), androidx.compose.ui.geometry.Size(barWidth, 6.dp.toPx()))
                    drawRect(Gold, Offset(c.x - barWidth / 2, top), androidx.compose.ui.geometry.Size((barWidth * progress).toFloat(), 6.dp.toPx()))
                }
            }

            // Look around: drag anywhere on the right side.
            Box(
                Modifier.align(Alignment.CenterEnd).fillMaxWidth(0.55f).fillMaxHeight().pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        game.look(drag.x * 0.0045, -drag.y * 0.0045)
                    }
                },
            )

            // Walk: a floating stick on the left side.
            var stickCenter by remember { mutableStateOf<Offset?>(null) }
            var stickKnob by remember { mutableStateOf(Offset.Zero) }
            Box(
                Modifier.align(Alignment.BottomStart).fillMaxWidth(0.45f).fillMaxHeight(0.65f).pointerInput(Unit) {
                    val radius = 56.dp.toPx()
                    fun stop() { stickCenter = null; game.moveRight = 0.0; game.moveForward = 0.0 }
                    detectDragGestures(
                        onDragStart = { stickCenter = it; stickKnob = it },
                        onDragEnd = { stop() },
                        onDragCancel = { stop() },
                        onDrag = { change, _ ->
                            change.consume()
                            val origin = stickCenter ?: return@detectDragGestures
                            var d = change.position - origin
                            val length = d.getDistance()
                            if (length > radius) d = d * (radius / length)
                            stickKnob = origin + d
                            game.moveRight = (d.x / radius).toDouble()
                            game.moveForward = (-d.y / radius).toDouble()
                        },
                    )
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val origin = stickCenter ?: return@Canvas
                    drawCircle(Color.White.copy(alpha = 0.18f), radius = 56.dp.toPx(), center = origin)
                    drawCircle(Color.White.copy(alpha = 0.7f), radius = 56.dp.toPx(), center = origin, style = Stroke(2.dp.toPx()))
                    drawCircle(Gold.copy(alpha = 0.9f), radius = 24.dp.toPx(), center = stickKnob)
                }
            }

            // Action buttons.
            HoldButton("Jump", 76, Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 76.dp)) { game.jumpHeld = it }
            HoldButton("Break", 60, Modifier.align(Alignment.BottomEnd).padding(end = 112.dp, bottom = 64.dp)) { game.breakHeld = it }
            HoldButton("Place", 60, Modifier.align(Alignment.BottomEnd).padding(end = 112.dp, bottom = 136.dp)) { game.placeHeld = it }

            // Hotbar: the first nine backpack slots. It starts empty; everything is gathered or crafted.
            val rev = uiRev
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                for (index in 0 until Inventory.HOTBAR) {
                    ItemIcon(
                        game.inventory.slots[index], size = 34.dp, selected = index == slot,
                        modifier = Modifier.clickable { game.selectedSlot = index; slot = index; uiRev++ },
                    )
                }
            }
            val heldLabel = remember(slot, rev) { game.inventory.slots[slot]?.item?.label ?: "Empty hand" }

            // Top bar: exit, bag, where you are, and what you hold.
            Column(Modifier.align(Alignment.TopStart).fillMaxWidth().padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { leave() }) { Text("Exit", color = Color.White, fontWeight = FontWeight.Bold) }
                    TextButton(onClick = { game.breakHeld = false; game.placeHeld = false; bagOpen = true }) {
                        Text("Bag & Crafting", color = Gold, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        "$heldLabel · $fps fps",
                        color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                }
                Text(info, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp, bottom = 4.dp))
                Text(
                    DEMO_NOTICE,
                    color = Color.White, fontSize = 11.sp, lineHeight = 14.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(Midnight.copy(alpha = 0.72f)).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }

            hint?.let {
                Text(
                    it, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center).padding(top = 120.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Midnight.copy(alpha = 0.8f)).padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }

        if (bagOpen) BagPanel(game, uiRev, onClose = { bagOpen = false }, onChanged = { uiRev++ })

        if (showWelcome) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text("VYRN Demo World") },
                text = {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            "$DEMO_NOTICE\n\n" +
                                "A 512 × 512 island with an Oak Forest, a Cherry Blossom grove and a village. " +
                                "You start with nothing: hold Break on a tree to collect logs, open Bag & Crafting to make planks, sticks, " +
                                "a Crafting Table and tools, then dig for stone, coal, iron and gold.\n\n" +
                                "Drag with your left thumb to walk and your right thumb to look. " +
                                "Your world is saved on this phone and works without internet.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                },
                confirmButton = { TextButton(onClick = { showWelcome = false }) { Text("Start exploring") } },
                dismissButton = { TextButton(onClick = { leave() }) { Text("Back") } },
            )
        }
    }
}

private fun locationText(game: DemoGame): String {
    val p = game.player
    val x = floor(p.x).toInt()
    val z = floor(p.z).toInt()
    val where = when {
        p.inWater -> "Water"
        game.world.villageCenter?.let { hypot((x - it.first).toDouble(), (z - it.third).toDouble()) <= Village.RADIUS } == true -> "Village"
        else -> game.world.biomeAt(x, z).label
    }
    return "$where  ·  X $x  Y ${floor(p.y).toInt()}  Z $z"
}

/** A backpack slot: a colour tile for the item, a letter for tools and materials, and a count. */
@Composable
fun ItemIcon(stack: ItemStack?, size: Dp = 34.dp, selected: Boolean = false, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        modifier.size(size).clip(shape).background(Midnight.copy(alpha = 0.6f))
            .border(if (selected) 3.dp else 1.dp, if (selected) Gold else Color.White.copy(alpha = 0.35f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (stack != null) {
            Box(
                Modifier.size(size * 0.66f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF000000.toInt() or stack.item.swatch)),
                contentAlignment = Alignment.Center,
            ) {
                if (stack.item.tag.isNotEmpty()) Text(stack.item.tag, color = Color(0xFF101321), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            if (stack.count > 1) {
                Text(
                    "${stack.count}", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
                )
            }
        }
    }
}

/** The Bag: your 27 slots (tap two to swap) and every recipe, with a Craft button when you have what it needs. */
@Composable
private fun BagPanel(game: DemoGame, rev: Int, onClose: () -> Unit, onChanged: () -> Unit) {
    var pending by remember { mutableStateOf<Int?>(null) }
    val nearTable = remember(rev) { game.nearTable() }
    val recipes = remember(rev, nearTable) {
        Recipes.all.sortedBy { if (it.blocker(game.inventory, nearTable) == null) 0 else 1 }
    }
    Box(Modifier.fillMaxSize().background(Midnight).pointerInput(Unit) { detectTapGestures { } }) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bag & Crafting", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp, modifier = Modifier.weight(1f))
                Button(onClick = onClose) { Text("Close") }
            }
            Text(
                if (nearTable) "Crafting Table nearby: all recipes available" else "No Crafting Table nearby: only basic recipes. Craft one, place it, and stand next to it.",
                color = if (nearTable) Online else Color.White.copy(alpha = 0.7f), fontSize = 12.sp,
            )
            Text("Backpack (top row is your hotbar). Tap two slots to swap them.", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (col in 0 until 9) {
                        val index = row * 9 + col
                        ItemIcon(
                            game.inventory.slots[index], size = 36.dp, selected = pending == index,
                            modifier = Modifier.clickable {
                                val from = pending
                                when {
                                    from == null -> if (game.inventory.slots[index] != null) pending = index
                                    from == index -> pending = null
                                    else -> { game.swapSlots(from, index); pending = null; onChanged() }
                                }
                            },
                        )
                    }
                }
            }
            Text("Recipes", color = Gold, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (recipe in recipes) {
                    val blocker = recipe.blocker(game.inventory, nearTable)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.06f)).padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        ItemIcon(ItemStack(recipe.result, recipe.count), size = 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (recipe.count > 1) "${recipe.result.label} ×${recipe.count}" else recipe.result.label,
                                color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            )
                            Text(recipe.ingredientText + if (recipe.needsTable) "  (Crafting Table)" else "", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp)
                            Text(blocker ?: "Ready to craft", color = if (blocker == null) Online else Offline, fontSize = 11.sp)
                        }
                        Button(onClick = { if (game.craft(recipe)) onChanged() }, enabled = blocker == null) { Text("Craft") }
                    }
                }
            }
        }
    }
}

@Composable
private fun HoldButton(label: String, sizeDp: Int, modifier: Modifier, onHold: (Boolean) -> Unit) {
    Box(
        modifier.size(sizeDp.dp).clip(CircleShape)
            .background(Midnight.copy(alpha = 0.55f))
            .border(2.dp, Gold, CircleShape)
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    onHold(true)
                    tryAwaitRelease()
                    onHold(false)
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Gold, fontWeight = FontWeight.Bold, fontSize = 12.sp)
    }
}
