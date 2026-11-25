package com.emustation.emusync.desktop.platform

import org.lwjgl.sdl.SDLEvents
import org.lwjgl.sdl.SDLGamepad
import org.lwjgl.sdl.SDLHints
import org.lwjgl.sdl.SDLInit

/**
 * SDL3 でコントローラーを読む（PlayStation の DualShock 4・DualSense、Xbox・ROG Ally、Switch のプロコン など）。
 * つないだり外したりしても追いかける。作ったスレッドからだけ使うこと（SDL の決まり）
 */
class SdlSource private constructor() : PadSource {
    private val open = LinkedHashMap<Int, Long>() // ID → ハンドル
    private var lastScan = 0L

    private fun rescan() {
        val ids = SDLGamepad.SDL_GetGamepads()?.let { buf -> IntArray(buf.remaining()) { buf.get(buf.position() + it) } } ?: IntArray(0)
        // 外れたもの
        for (id in open.keys.toList()) if (id !in ids) open.remove(id)?.let(SDLGamepad::SDL_CloseGamepad)
        // つながったもの
        for (id in ids) if (id !in open) SDLGamepad.SDL_OpenGamepad(id).takeIf { it != 0L }?.let { open[id] = it }
    }

    override fun poll(): PadState {
        SDLEvents.SDL_PumpEvents() // つないだ・外したを知るため
        val now = System.currentTimeMillis()
        if (now - lastScan > 1000) { lastScan = now; rescan() }
        SDLGamepad.SDL_UpdateGamepads()
        val all = mutableSetOf<PadButton>()
        var style: PadStyle? = null
        for (h in open.values) {
            val pressed = read(h)
            if (pressed.isNotEmpty() && style == null) style = styleOf(SDLGamepad.SDL_GetGamepadType(h))
            all += pressed
        }
        return PadState(all, style)
    }

    private fun read(h: Long): Set<PadButton> {
        val s = mutableSetOf<PadButton>()
        for ((sdl, b) in BUTTONS) if (SDLGamepad.SDL_GetGamepadButton(h, sdl)) s += b
        // SDL のスティックは下が +。XInput と同じく上を + にして直す
        PadState.stick(
            SDLGamepad.SDL_GetGamepadAxis(h, SDLGamepad.SDL_GAMEPAD_AXIS_LEFTX).toInt(),
            -SDLGamepad.SDL_GetGamepadAxis(h, SDLGamepad.SDL_GAMEPAD_AXIS_LEFTY).toInt(),
        )?.let { s += it }
        return s
    }

    companion object {
        /** SDL のボタン（位置の名前）→ このアプリのボタン */
        val BUTTONS: List<Pair<Int, PadButton>> = listOf(
            SDLGamepad.SDL_GAMEPAD_BUTTON_SOUTH to PadButton.A,
            SDLGamepad.SDL_GAMEPAD_BUTTON_EAST to PadButton.B,
            SDLGamepad.SDL_GAMEPAD_BUTTON_WEST to PadButton.X,
            SDLGamepad.SDL_GAMEPAD_BUTTON_NORTH to PadButton.Y,
            SDLGamepad.SDL_GAMEPAD_BUTTON_LEFT_SHOULDER to PadButton.LB,
            SDLGamepad.SDL_GAMEPAD_BUTTON_RIGHT_SHOULDER to PadButton.RB,
            SDLGamepad.SDL_GAMEPAD_BUTTON_START to PadButton.Start,
            SDLGamepad.SDL_GAMEPAD_BUTTON_BACK to PadButton.Back,
            SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_UP to PadButton.Up,
            SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_DOWN to PadButton.Down,
            SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_LEFT to PadButton.Left,
            SDLGamepad.SDL_GAMEPAD_BUTTON_DPAD_RIGHT to PadButton.Right,
        )

        fun styleOf(type: Int): PadStyle = when (type) {
            SDLGamepad.SDL_GAMEPAD_TYPE_PS3, SDLGamepad.SDL_GAMEPAD_TYPE_PS4, SDLGamepad.SDL_GAMEPAD_TYPE_PS5 -> PadStyle.PlayStation
            SDLGamepad.SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_PRO, SDLGamepad.SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_PAIR,
            SDLGamepad.SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_LEFT, SDLGamepad.SDL_GAMEPAD_TYPE_NINTENDO_SWITCH_JOYCON_RIGHT,
            SDLGamepad.SDL_GAMEPAD_TYPE_GAMECUBE -> PadStyle.Nintendo
            else -> PadStyle.Xbox
        }

        /** SDL が使えなければ null（ネイティブのライブラリがない など）。呼んだスレッドで以後も使うこと */
        fun open(): SdlSource? = runCatching {
            // ウィンドウを持たないので、前にいなくてもコントローラーの状態を読めるようにする（送るかどうかはウィンドウの状態で決める）
            SDLHints.SDL_SetHint(SDLHints.SDL_HINT_JOYSTICK_ALLOW_BACKGROUND_EVENTS, "1")
            if (!SDLInit.SDL_Init(SDLInit.SDL_INIT_GAMEPAD)) return null
            SdlSource()
        }.getOrNull()
    }
}
