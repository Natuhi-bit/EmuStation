package com.emustation.emusync.ui.common

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** strings.xml の文言を、引数ごと ViewModel から UI に渡すための型 */
class UiText(@StringRes val id: Int, vararg val args: Any) {
    @Composable
    fun resolve(): String = stringResource(id, *args)

    fun resolve(context: Context): String = context.getString(id, *args)
}
