package com.emustation.emusync.desktop.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Structure
import java.awt.event.KeyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * コントローラーのボタン。位置で呼ぶ（Xbox の名前）：A＝下（PlayStation の ×）、B＝右（○）、X＝左（□）、Y＝上（△）、
 * LB・RB＝L1・R1、Start＝メニュー（OPTIONS）、Back＝ビュー（SHARE・CREATE）
 */
enum class PadButton { Up, Down, Left, Right, A, B, X, Y, LB, RB, Start, Back }

/** ボタンの表示の種類（画面の下の説明を、そのコントローラーの記号で出す） */
enum class PadStyle { Xbox, PlayStation, Nintendo }

/** ある瞬間に押されているボタン（スティックの倒しも上下左右に直したもの）と、押したコントローラーの種類 */
data class PadState(val pressed: Set<PadButton>, val style: PadStyle? = null) {
    companion object {
        val NONE = PadState(emptySet())

        /** スティックをこれより倒したら、方向キーと同じに扱う（-32768〜32767 の半分ほど） */
        const val STICK_THRESHOLD = 16_000

        /** XInput の値（ボタンのビットと左スティック）から */
        fun fromXInput(buttons: Int, lx: Int, ly: Int): PadState {
            val s = mutableSetOf<PadButton>()
            fun bit(mask: Int, b: PadButton) { if (buttons and mask != 0) s += b }
            bit(0x0001, PadButton.Up); bit(0x0002, PadButton.Down); bit(0x0004, PadButton.Left); bit(0x0008, PadButton.Right)
            bit(0x0010, PadButton.Start); bit(0x0020, PadButton.Back)
            bit(0x0100, PadButton.LB); bit(0x0200, PadButton.RB)
            bit(0x1000, PadButton.A); bit(0x2000, PadButton.B); bit(0x4000, PadButton.X); bit(0x8000, PadButton.Y)
            // スティックは強く倒した向きだけ（斜めは大きい方）
            if (maxOf(kotlin.math.abs(lx), kotlin.math.abs(ly)) >= STICK_THRESHOLD) {
                s += if (kotlin.math.abs(lx) > kotlin.math.abs(ly)) {
                    if (lx > 0) PadButton.Right else PadButton.Left
                } else {
                    if (ly > 0) PadButton.Up else PadButton.Down
                }
            }
            return PadState(s)
        }

        /** 左スティックの倒しを上下左右に（XInput と同じく上が +） */
        fun stick(lx: Int, ly: Int): PadButton? {
            if (maxOf(kotlin.math.abs(lx), kotlin.math.abs(ly)) < STICK_THRESHOLD) return null
            return if (kotlin.math.abs(lx) > kotlin.math.abs(ly)) {
                if (lx > 0) PadButton.Right else PadButton.Left
            } else {
                if (ly > 0) PadButton.Up else PadButton.Down
            }
        }
    }
}

/**
 * 押したときに 1 回。上下左右（とLB・RB）は押し続けるとくり返す（キーボードのキーリピートのように）。
 * [now] はミリ秒
 */
class PadRepeater(private val initialDelayMs: Long = 300, private val repeatMs: Long = 80) {
    private val repeats = setOf(PadButton.Up, PadButton.Down, PadButton.Left, PadButton.Right, PadButton.LB, PadButton.RB)
    private var prev = PadState.NONE
    private val nextRepeat = HashMap<PadButton, Long>()

    fun update(state: PadState, now: Long): List<PadButton> {
        val out = mutableListOf<PadButton>()
        for (b in PadButton.entries) {
            val on = b in state.pressed
            val was = b in prev.pressed
            when {
                on && !was -> { out += b; if (b in repeats) nextRepeat[b] = now + initialDelayMs }
                on && b in repeats && now >= (nextRepeat[b] ?: Long.MAX_VALUE) -> { out += b; nextRepeat[b] = now + repeatMs }
                !on -> nextRepeat.remove(b)
            }
        }
        prev = state
        return out
    }
}

/** 画面の状態：XMB を選んでいる／パネル・ダイアログ（ボタンや入力欄がある）を開いている */
enum class PadMode { Xmb, Form }

/** 押すキー（AWT のキーコード）。shift は Shift を押しながら */
data class KeyStroke(val code: Int, val shift: Boolean = false)

/**
 * コントローラーのボタン → キー。キーボードと同じ操作になるようにする。
 * - XMB：十字キー・スティック → 矢印、LB・RB → 左右（列の切り替え）、A → Enter、B → Esc、Y・Start → Tab（オプション）
 * - パネル・ダイアログ：上下 → Shift+Tab・Tab（前・次のボタンや入力欄へ）、左右 → 矢印、A → Enter（押す）、B → Esc（閉じる）
 * - View（Back）→ F11（全画面の切り替え）
 * [confirmIsB] なら A と B を入れ替える（PS3 の日本の並びのように、右のボタンで決定）
 */
object PadKeys {
    fun map(b: PadButton, mode: PadMode, confirmIsB: Boolean = false): KeyStroke? {
        val btn = when {
            confirmIsB && b == PadButton.A -> PadButton.B
            confirmIsB && b == PadButton.B -> PadButton.A
            else -> b
        }
        return when (btn) {
            PadButton.A -> KeyStroke(KeyEvent.VK_ENTER)
            PadButton.B -> KeyStroke(KeyEvent.VK_ESCAPE)
            PadButton.Back -> KeyStroke(KeyEvent.VK_F11)
            PadButton.Left -> KeyStroke(KeyEvent.VK_LEFT)
            PadButton.Right -> KeyStroke(KeyEvent.VK_RIGHT)
            else -> when (mode) {
                PadMode.Xmb -> when (btn) {
                    PadButton.Up -> KeyStroke(KeyEvent.VK_UP)
                    PadButton.Down -> KeyStroke(KeyEvent.VK_DOWN)
                    PadButton.LB -> KeyStroke(KeyEvent.VK_LEFT)
                    PadButton.RB -> KeyStroke(KeyEvent.VK_RIGHT)
                    PadButton.Y, PadButton.Start -> KeyStroke(KeyEvent.VK_TAB)
                    else -> null
                }
                PadMode.Form -> when (btn) {
                    PadButton.Up, PadButton.LB -> KeyStroke(KeyEvent.VK_TAB, shift = true)
                    PadButton.Down, PadButton.RB -> KeyStroke(KeyEvent.VK_TAB)
                    else -> null
                }
            }
        }
    }
}

/** コントローラーの読み取り口（テストでは差し替える） */
fun interface PadSource {
    /** 今の状態。コントローラーがなければ PadState.NONE */
    fun poll(): PadState
}

/** 画面とコントローラーの間で共有する状態 */
object Gamepad {
    /** 画面が今どちらか（XmbApp が書く） */
    @Volatile
    var mode: PadMode = PadMode.Xmb

    private val _lastInputWasPad = MutableStateFlow(false)

    /** 最後の操作がコントローラーか（画面の下の操作の説明を切り替える） */
    val lastInputWasPad: StateFlow<Boolean> = _lastInputWasPad.asStateFlow()

    /** コントローラーから送っている最中（キーボードの操作と見分けるため） */
    @Volatile
    var dispatching = false

    fun markPad() { _lastInputWasPad.value = true }

    private val _style = MutableStateFlow(PadStyle.Xbox)

    /** 最後に押したコントローラーの種類 */
    val style: StateFlow<PadStyle> = _style.asStateFlow()

    fun setStyle(s: PadStyle) { _style.value = s }

    /** ボタンの記号（画面の説明用）。位置で決まる */
    fun label(b: PadButton, s: PadStyle = style.value): String = when (s) {
        PadStyle.PlayStation -> when (b) {
            PadButton.A -> "×"; PadButton.B -> "○"; PadButton.X -> "□"; PadButton.Y -> "△"
            PadButton.LB -> "L1"; PadButton.RB -> "R1"; PadButton.Start -> "OPTIONS"; PadButton.Back -> "CREATE"
            else -> "✚"
        }
        // Switch のプロコンは位置と文字が逆（右が A、下が B）
        PadStyle.Nintendo -> when (b) {
            PadButton.A -> "B"; PadButton.B -> "A"; PadButton.X -> "Y"; PadButton.Y -> "X"
            PadButton.LB -> "L"; PadButton.RB -> "R"; PadButton.Start -> "+"; PadButton.Back -> "−"
            else -> "✚"
        }
        PadStyle.Xbox -> when (b) {
            PadButton.LB -> "LB"; PadButton.RB -> "RB"; PadButton.Start -> "メニュー"; PadButton.Back -> "ビュー"
            PadButton.Up, PadButton.Down, PadButton.Left, PadButton.Right -> "✚"
            else -> b.name
        }
    }

    fun markKeyboard() { if (!dispatching) _lastInputWasPad.value = false }
}

/**
 * Windows の XInput（ROG Ally・Xbox のコントローラーなど）。4 台まで読み、まとめて 1 つの状態にする。
 * つながっていないコントローラーを毎回読むと遅いので、2 秒おきにだけ確かめる
 */
class XInputSource private constructor(private val lib: Lib) : PadSource {
    @Suppress("FunctionName")
    private interface Lib : Library {
        fun XInputGetState(index: Int, state: State): Int
    }

    /** XINPUT_STATE（XINPUT_GAMEPAD を平らにしたもの。並びとずれは同じ） */
    @Structure.FieldOrder("dwPacketNumber", "wButtons", "bLeftTrigger", "bRightTrigger", "sThumbLX", "sThumbLY", "sThumbRX", "sThumbRY")
    class State : Structure() {
        @JvmField var dwPacketNumber: Int = 0
        @JvmField var wButtons: Short = 0
        @JvmField var bLeftTrigger: Byte = 0
        @JvmField var bRightTrigger: Byte = 0
        @JvmField var sThumbLX: Short = 0
        @JvmField var sThumbLY: Short = 0
        @JvmField var sThumbRX: Short = 0
        @JvmField var sThumbRY: Short = 0
    }

    private val state = State()
    private val connected = BooleanArray(4) { true }
    private var lastScan = 0L

    override fun poll(): PadState {
        val now = System.currentTimeMillis()
        val rescan = now - lastScan > 2000
        if (rescan) lastScan = now
        val all = mutableSetOf<PadButton>()
        for (i in 0 until 4) {
            if (!connected[i] && !rescan) continue
            val r = runCatching { lib.XInputGetState(i, state) }.getOrDefault(-1)
            connected[i] = r == 0
            if (r == 0) {
                all += PadState.fromXInput(state.wButtons.toInt() and 0xFFFF, state.sThumbLX.toInt(), state.sThumbLY.toInt()).pressed
            }
        }
        return PadState(all)
    }

    companion object {
        /** Windows 以外や、XInput がないときは null */
        fun open(): XInputSource? {
            if (!isWindows) return null
            for (name in listOf("xinput1_4", "xinput1_3", "xinput9_1_0")) {
                runCatching { return XInputSource(Native.load(name, Lib::class.java)) }
            }
            return null
        }
    }
}

/** キーを、ウィンドウで今フォーカスのあるところへ送る（キーボードで押したのと同じに扱われる） */
object AwtKeys {
    fun send(window: java.awt.Window, k: KeyStroke) {
        val target: java.awt.Component = window.mostRecentFocusOwner ?: window
        val mods = if (k.shift) java.awt.event.InputEvent.SHIFT_DOWN_MASK else 0
        val t = System.currentTimeMillis()
        fun ev(id: Int, code: Int) = target.dispatchEvent(KeyEvent(target, id, t, mods, code, KeyEvent.CHAR_UNDEFINED))
        Gamepad.dispatching = true
        try {
            if (k.shift) ev(KeyEvent.KEY_PRESSED, KeyEvent.VK_SHIFT)
            ev(KeyEvent.KEY_PRESSED, k.code)
            ev(KeyEvent.KEY_RELEASED, k.code)
            if (k.shift) ev(KeyEvent.KEY_RELEASED, KeyEvent.VK_SHIFT)
        } finally {
            Gamepad.dispatching = false
        }
    }
}
