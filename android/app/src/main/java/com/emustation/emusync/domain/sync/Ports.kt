package com.emustation.emusync.domain.sync

import java.io.OutputStream

/**
 * 同期エンジンが使うサーバーの操作（設計書 §3.1〜3.3）。
 * 実装は data/api/EmusyncApi。テストでは偽サーバーに差し替える。
 */
interface RemoteApi {
    /** `GET /api/manifest?prefix=`。キーは prefix を取り除いた相対パス */
    suspend fun manifest(prefix: String): Map<String, RemoteFile>

    /**
     * `GET /api/files/{path}`。本文を [out] に書き、SHA-256 を検証する。
     * 一致しなければ例外を投げる（[out] に書いた内容は呼び出し側が捨てる）。
     */
    suspend fun download(path: String, out: OutputStream, version: Int? = null): Downloaded

    /** `PUT /api/files/{path}`。409 は例外ではなく [UploadOutcome.Rejected] で返す */
    suspend fun upload(path: String, content: UploadContent, baseVersion: Int): UploadOutcome
}

/** 手元のファイル操作。実装は data/storage/JavaLocalFs */
interface LocalFs {
    /**
     * セーブフォルダを再帰的に走査して `{相対パス: sha256}` を返す（設計書 §4.1）。
     * `.emusync-tmp` で終わるファイルと、除外パターンに一致するファイルは含めない。
     * フォルダがなければ空。
     */
    fun scan(target: SyncTarget): Map<String, String>

    /** 送信のために、今の内容のサイズと SHA-256 を計算する */
    fun prepareUpload(path: String): UploadContent

    /** アトミックな書き込みを始める（設計書 §4.3） */
    fun beginAtomicWrite(path: String): AtomicWrite

    /** ファイルをコピーする（更新日時も保つ）。コピー先の親フォルダは作る */
    fun copy(src: String, dst: String)

    /** ファイルを移す（既存のファイルは置き換える）。移し先の親フォルダは作る */
    fun move(src: String, dst: String)
}

/** `<ファイル名>.emusync-tmp` に書き、[commit] で fsync して本来の名前に rename する */
interface AtomicWrite {
    val output: OutputStream

    fun commit()

    /** 一時ファイルを消す。commit 後に呼んでも何もしない */
    fun abort()
}

/** 前回同期した状態の保存先（設計書 §7 の base_state） */
interface BaseStateStore {
    suspend fun load(target: String): Map<String, BaseEntry>

    /** ターゲットの状態を丸ごと置き換える */
    suspend fun save(target: String, entries: Map<String, BaseEntry>)
}
