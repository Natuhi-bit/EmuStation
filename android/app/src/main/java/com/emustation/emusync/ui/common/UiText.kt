package com.emustation.emusync.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** strings.xml の文言を、引数ごと ViewModel から UI に渡すための型。引数に UiText を入れてもよい */
class UiText(@StringRes val id: Int, vararg val args: Any) {
    @Composable
    fun resolve(): String = resolve(LocalContext.current)

    fun resolve(context: Context): String =
        context.getString(id, *args.map { if (it is UiText) it.resolve(context) else it }.toTypedArray())

    override fun toString() = "UiText($id, ${args.toList()})"
}
