package com.emustation.emusync.desktop.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * 選んでいる（フォーカスのある）ボタン・スイッチなどを、白い枠ではっきり見せる。
 * コントローラーやキーボードで操作するとき、どこを選んでいるか分かるように（暗い背景では標準の表示が見えにくい）
 */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(50)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    this.onFocusChanged { focused = it.isFocused || it.hasFocus }
        .border(2.dp, if (focused) Color.White else Color.Transparent, shape)
}
