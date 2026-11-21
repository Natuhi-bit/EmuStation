package com.emustation.emusync.domain.sync

import com.emustation.emusync.data.storage.JavaLocalFs
import com.emustation.emusync.testing.FakeServer
import com.emustation.emusync.testing.InMemoryBaseStateStore
import com.emustation.emusync.testing.sha256
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Python 版 `sync_target` と同じ動作になっているかを、全組み合わせで照合する。
 *
 * 期待値は scripts/gen_decision_cases.py が Python 版を実際に動かして作ったもの
 * （src/test/resources/decision_cases.json）。
 */
class PythonParityTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val doc = Json.parseToJsonElement(
        javaClass.classLoader!!.getResource("decision_cases.json")!!.readText(),
    ).jsonObject
    private val target = doc["target"]!!.jsonPrimitive.content
    private val rel = doc["rel"]!!.jsonPrimitive.content
    private val contents = doc["contents"]!!.jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val letterOf = contents.entries.associate { (k, v) -> sha256(v) to k }

    @Test
    fun allCasesMatchPython() {
        val cases = doc["cases"]!!.jsonArray
        assertTrue(cases.size > 1000)
        val failures = mutableListOf<String>()
        for ((i, c) in cases.withIndex()) {
            val input = c.jsonObject["input"]!!.jsonObject
            val expected = c.jsonObject["expected"]!!
            val actual = runBlocking { runCase(i, input) }
            if (actual != expected) failures += "#$i $input\n  expected=$expected\n  actual  =$actual"
        }
        assertEquals(
            "${failures.size} 件が Python 版と違う:\n" + failures.take(10).joinToString("\n"),
            0, failures.size,
        )
    }

    private fun JsonElement.strOrNull() = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
    private fun pair(e: JsonElement?) = (e as? JsonArray)?.let { it[0].jsonPrimitive.content to it[1].jsonPrimitive.int }

    private suspend fun runCase(i: Int, input: JsonObject): JsonElement {
        val local = input["local"]!!.strOrNull()
        val remote = pair(input["remote"])
        val base = pair(input["base"])
        val mode = SyncMode.valueOf(input["mode"]!!.jsonPrimitive.content)
        val prefer = input["prefer"]!!.strOrNull()?.let { Prefer.valueOf(it) }
        val dryRun = input["dryRun"]!!.jsonPrimitive.boolean
        val race = input["race"]!!.jsonPrimitive.boolean

        val dir = tmp.newFolder("case$i")
        val root = File(dir, "root").apply { mkdirs() }
        val appDir = File(dir, "app")
        val path = "$target/$rel"
        if (local != null) File(root, rel).apply { parentFile!!.mkdirs() }.writeText(contents.getValue(local))

        val server = FakeServer()
        if (remote != null) server.seed(path, contents.getValue(remote.first).toByteArray(), remote.second)
        if (race && remote != null) {
            server.afterManifest = {
                server.seed(path, contents.getValue("W").toByteArray(), remote.second + 1)
            }
        }
        val baseStore = InMemoryBaseStateStore()
        if (base != null) baseStore.data[target] = mapOf(rel to BaseEntry(sha256(contents.getValue(base.first)), base.second))

        val engine = SyncEngine(
            server.client("android"), JavaLocalFs(), baseStore, appDir.path,
            mutexes = TargetMutexes(),
        )
        val res = engine.sync(SyncTarget(target, root.path), mode, prefer, dryRun)

        val lf = File(root, rel)
        val st = baseStore.data[target]?.get(rel)
        val srv = server.get(path)
        val conflictsDir = File(appDir, "conflicts/$target")
        val copies = conflictsDir.walkTopDown().filter { it.isFile }.map {
            it.relativeTo(conflictsDir).invariantSeparatorsPath to letterOf.getValue(sha256(it.readBytes()))
        }.sortedBy { it.first }.toList()
        val backups = File(appDir, "backups").walkTopDown().filter { it.isFile }
            .map { letterOf.getValue(sha256(it.readBytes())) }.sorted().toList()

        return buildJsonObject {
            put("uploaded", JsonArray(res.uploaded.map(::JsonPrimitive)))
            put("downloaded", JsonArray(res.downloaded.map(::JsonPrimitive)))
            put("conflicts", buildJsonArray {
                res.conflicts.forEach { add(JsonArray(listOf(JsonPrimitive(it.rel), JsonPrimitive(it.kind.name)))) }
            })
            put("errors", res.errors.size)
            put("uploadBaseVersions", JsonArray(server.uploadLog.map { JsonPrimitive(it.second) }))
            put("local", if (lf.isFile) JsonPrimitive(letterOf.getValue(sha256(lf.readBytes()))) else JsonNull)
            put("server", srv?.let { JsonArray(listOf(JsonPrimitive(letterOf.getValue(it.sha256)), JsonPrimitive(it.version))) } ?: JsonNull)
            put("base", st?.let { JsonArray(listOf(JsonPrimitive(letterOf.getValue(it.sha256)), JsonPrimitive(it.version))) } ?: JsonNull)
            put("conflictCopies", JsonArray(copies.map { JsonArray(listOf(JsonPrimitive(it.first), JsonPrimitive(it.second))) }))
            put("backups", JsonArray(backups.map(::JsonPrimitive)))
        }
    }
}
