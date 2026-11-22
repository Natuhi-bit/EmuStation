package com.emustation.emusync.desktop.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

/** 項目のオプション（PS3 の △ メニュー、Steam の右クリック） */
data class XmbOption(val label: String, val enabled: Boolean = true, val action: () -> Unit)

data class XmbItem(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val icon: ImageVector? = null,
    /** ゲームのカバーなど（あればアイコンの代わりに出す） */
    val image: ImageBitmap? = null,
    /** 右に出す小さな状態（例「競合 2」） */
    val badge: String? = null,
    val badgeColor: Color = XmbText.secondary,
    /** Enter・ダブルクリックで実行（なければオプションを開く） */
    val onActivate: (() -> Unit)? = null,
    val options: List<XmbOption> = emptyList(),
)

data class XmbCategory(val id: String, val label: String, val icon: ImageVector, val items: List<XmbItem>)

/** 選んでいるカテゴリーと項目 */
@Stable
class XmbState(initialCategory: Int = 1) {
    var category by mutableIntStateOf(initialCategory)
    private val itemIndex = mutableStateMapOf<String, Int>()
    var optionsOpen by mutableStateOf(false)
    var optionIndex by mutableIntStateOf(0)

    fun itemOf(c: XmbCategory) = (itemIndex[c.id] ?: 0).coerceIn(0, (c.items.size - 1).coerceAtLeast(0))

    fun selectItem(c: XmbCategory, i: Int) {
        itemIndex[c.id] = i.coerceIn(0, (c.items.size - 1).coerceAtLeast(0))
    }

    fun selectedItem(cats: List<XmbCategory>): XmbItem? = cats.getOrNull(category)?.let { it.items.getOrNull(itemOf(it)) }

    /** キー操作。扱ったら true */
    fun onKey(e: KeyEvent, cats: List<XmbCategory>): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        val c = cats.getOrNull(category) ?: return false
        val item = selectedItem(cats)
        if (optionsOpen) {
            val opts = item?.options.orEmpty()
            when (e.key) {
                Key.DirectionUp -> optionIndex = (optionIndex - 1).coerceAtLeast(0)
                Key.DirectionDown -> optionIndex = (optionIndex + 1).coerceAtMost((opts.size - 1).coerceAtLeast(0))
                Key.Enter, Key.NumPadEnter, Key.Spacebar -> opts.getOrNull(optionIndex)?.takeIf { it.enabled }?.let {
                    optionsOpen = false
                    it.action()
                }
                Key.Escape, Key.Backspace, Key.Tab, Key.DirectionLeft -> optionsOpen = false
                else -> return false
            }
            return true
        }
        when (e.key) {
            Key.DirectionLeft -> category = (category - 1).coerceAtLeast(0)
            Key.DirectionRight -> category = (category + 1).coerceAtMost(cats.size - 1)
            Key.DirectionUp -> selectItem(c, itemOf(c) - 1)
            Key.DirectionDown -> selectItem(c, itemOf(c) + 1)
            Key.Enter, Key.NumPadEnter, Key.Spacebar -> activate(item)
            Key.Tab, Key.Menu -> openOptions(item)
            else -> return false
        }
        return true
    }

    fun activate(item: XmbItem?) {
        when {
            item == null -> Unit
            item.onActivate != null -> item.onActivate.invoke()
            item.options.isNotEmpty() -> openOptions(item)
        }
    }

    fun openOptions(item: XmbItem?) {
        if (item?.options.isNullOrEmpty()) return
        optionIndex = 0
        optionsOpen = true
    }
}

private val shadowText = TextStyle(shadow = Shadow(Color(0x99000000), androidx.compose.ui.geometry.Offset(0f, 2f), 6f))

/**
 * PS3 の XMB：カテゴリーが横に並び、選んだカテゴリーの項目が縦に並ぶ。
 * 選んでいる項目は、カテゴリーの列のすぐ下に大きく出る。
 */
@Composable
fun Xmb(categories: List<XmbCategory>, state: XmbState, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val anchorX = maxWidth * 0.24f
        val height = maxHeight
        val rowY = maxHeight * 0.20f
        val catSpacing = 150.dp
        val catPos by animateFloatAsState(state.category.toFloat(), tween(220), label = "cat")

        // ---- カテゴリーの列
        categories.forEachIndexed { i, c ->
            val selected = i == state.category
            val x = anchorX + catSpacing * (i - catPos) - 70.dp
            Column(
                Modifier.offset(x, rowY - 40.dp).width(140.dp).alpha(if (selected) 1f else 0.55f)
                    .clickable(remember { MutableInteractionSource() }, null) { state.category = i }
                    .testTag("cat-${c.id}"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(c.icon, c.label, tint = XmbText.primary, modifier = Modifier.size(if (selected) 56.dp else 44.dp))
                if (selected) {
                    Spacer(Modifier.size(6.dp))
                    Text(c.label, color = XmbText.primary, fontSize = 15.sp, style = shadowText, maxLines = 1)
                }
            }
        }

        // ---- 項目の列（選んでいるカテゴリーだけ）
        val cat = categories.getOrNull(state.category) ?: return@BoxWithConstraints
        val sel = state.itemOf(cat)
        val itemPos by animateFloatAsState(sel.toFloat(), tween(180), label = "item")
        val step = 72.dp
        val belowStart = rowY + 70.dp
        val aboveStart = rowY - 110.dp
        val scroll = rememberScrollableState { d ->
            if (d < -20f) state.selectItem(cat, state.itemOf(cat) + 1) else if (d > 20f) state.selectItem(cat, state.itemOf(cat) - 1)
            d
        }
        Box(Modifier.fillMaxSize().scrollable(scroll, Orientation.Vertical)) {
            cat.items.forEachIndexed { i, item ->
                val rel = i - itemPos
                val y = if (rel >= -0.5f) belowStart + step * rel + (if (i > sel) 36.dp else 0.dp) else aboveStart + step * (rel + 1)
                if (y < -step || y > height + step) return@forEachIndexed
                val selected = i == sel
                ItemRow(
                    item, selected,
                    Modifier.offset { IntOffset(with(density) { (anchorX - 44.dp).roundToPx() }, with(density) { y.roundToPx() }) }
                        .clickable(remember { MutableInteractionSource() }, null) {
                            if (selected) state.activate(item) else state.selectItem(cat, i)
                        }
                        .testTag("item-${item.id}"),
                )
            }
        }

        // ---- オプション（右から出る）
        val item = cat.items.getOrNull(sel)
        AnimatedVisibility(
            state.optionsOpen && item != null,
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            Column(
                Modifier.fillMaxHeight().width(340.dp)
                    .background(Brush.horizontalGradient(listOf(Color(0x00000000), Color(0xCC000000), Color(0xEE000000))))
                    .padding(start = 56.dp, end = 24.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                item?.options?.forEachIndexed { i, o ->
                    val on = i == state.optionIndex
                    Text(
                        o.label,
                        color = if (!o.enabled) XmbText.dim.copy(alpha = 0.35f) else if (on) XmbText.primary else XmbText.secondary,
                        fontSize = if (on) 19.sp else 17.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        style = shadowText,
                        modifier = Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (on) Color(0x33FFFFFF) else Color.Transparent)
                            .clickable(enabled = o.enabled) { state.optionsOpen = false; o.action() }
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                            .testTag("option-$i"),
                    )
                }
            }
        }
    }
}

@Composable
private fun ItemRow(item: XmbItem, selected: Boolean, modifier: Modifier) {
    val iconSize = if (selected) 88.dp else 60.dp
    Row(modifier.alpha(if (selected) 1f else 0.62f), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            when {
                item.image != null -> Image(
                    item.image, null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(iconSize).shadow(if (selected) 12.dp else 0.dp, RoundedCornerShape(6.dp))
                        .clip(RoundedCornerShape(6.dp)),
                )
                item.icon != null -> Icon(item.icon, null, tint = XmbText.primary, modifier = Modifier.size(iconSize * 0.72f))
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.widthIn(max = 640.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    item.title, color = XmbText.primary, style = shadowText,
                    fontSize = if (selected) 22.sp else 17.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.dp),
                )
                item.badge?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(
                        it, color = item.badgeColor, fontSize = 12.sp,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x33000000))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            if (selected && item.subtitle != null) {
                Spacer(Modifier.size(4.dp))
                Text(item.subtitle, color = XmbText.secondary, fontSize = 14.sp, style = shadowText, maxLines = 2)
            }
        }
    }
}

/** 画面の下の、ボタンの案内（PS3 の ○ 決定 △ オプション） */
@Composable
fun KeyHints(hints: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
        hints.forEach { (key, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    key, color = XmbText.primary, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0x40FFFFFF)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(label, color = XmbText.secondary, fontSize = 13.sp, style = shadowText)
            }
        }
    }
}
