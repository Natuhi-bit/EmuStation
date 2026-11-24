package com.emustation.emusync.testing

import com.emustation.emusync.AppContainer
import com.emustation.emusync.EmusyncApp
import com.emustation.emusync.data.prefs.TokenCipher

/** PC 上でアプリ全体を動かすためのアプリ。Android Keystore がないので、トークンは暗号化せずに置く */
class MonkeyApp : EmusyncApp() {
    override fun createContainer(): AppContainer = AppContainer(this, PlainTokenCipher)
}

object PlainTokenCipher : TokenCipher {
    override fun encrypt(plain: String): String = "plain:$plain"
    override fun decrypt(encoded: String): String? = encoded.removePrefix("plain:").takeIf { encoded.startsWith("plain:") }
}
