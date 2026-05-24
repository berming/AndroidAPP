package com.communicationcard.server.admin

import java.io.File
import java.time.Instant
import kotlin.random.Random

/**
 * Fuzz / property-based 测试的轻量基类。
 *
 * 设计取舍（vs 引入 kotest-property）：
 * - 现栈只有 kotlin.test + kotlinx-coroutines-test；新依赖会拉长 :server CI 时间
 * - 本仓库的 fuzz 重点是输入多样性 + 不变量断言；不需要 shrinking / minimization
 * - 用 [seededRandom] 让失败可复现：失败时打印 seed，下次手动 `runWithSeed(N)` 即可复现
 *
 * 记录回写：测试完成后调用 [recordResult] 写一条 `ut_fuzz_record` 事件到
 * `.quality/check-records.jsonl`，供下次 UC10 Double Check 消费。
 * 仅当环境变量 `WRITE_FUZZ_RECORDS=1` 时写盘（默认关闭，避免本地 IDE 跑测试
 * 污染审计文件）；CI 通过 `:server:fuzzTest` gradle task 设置该变量。
 */
abstract class FuzzTestBase {

    /** 默认种子；可通过 -Dfuzz.seed=N 覆盖（用于复现失败）。 */
    protected val seed: Long = System.getProperty("fuzz.seed")?.toLongOrNull()
        ?: System.currentTimeMillis()

    protected val seededRandom: Random by lazy {
        println("[FuzzTestBase] seed=$seed (use -Dfuzz.seed=$seed to reproduce)")
        Random(seed)
    }

    /**
     * 写一条 fuzz 记录到 `.quality/check-records.jsonl`，供 UC10 消费。
     * 仅当 `WRITE_FUZZ_RECORDS=1` 时生效，避免本地 IDE 误写盘。
     */
    protected fun recordResult(
        moduleName: String,
        testCaseCount: Int,
        passCount: Int,
        durationMs: Long,
        filesChanged: List<String>,
        notes: String? = null,
    ) {
        if (System.getenv("WRITE_FUZZ_RECORDS") != "1") return
        val recordsFile = locateQualityFile() ?: return
        val passRate = if (testCaseCount > 0) passCount.toDouble() / testCaseCount else 0.0
        val conclusion = if (passCount == testCaseCount) "passed" else "failed"
        val commit = runCatching {
            ProcessBuilder("git", "rev-parse", "HEAD")
                .redirectErrorStream(true)
                .start()
                .inputStream.bufferedReader().readText().trim()
        }.getOrDefault("unknown")
        val json = buildString {
            append("{")
            append("\"event\":\"ut_fuzz_record\",")
            append("\"check_type\":\"ut_fuzz\",")
            append("\"module_name\":\"$moduleName\",")
            append("\"commit_hash\":\"$commit\",")
            append("\"test_case_count\":$testCaseCount,")
            append("\"pass_count\":$passCount,")
            append("\"pass_rate\":$passRate,")
            append("\"execution_time_sec\":${durationMs / 1000.0},")
            append("\"conclusion\":\"$conclusion\",")
            append("\"seed\":$seed,")
            append("\"files_changed\":[${filesChanged.joinToString(",") { "\"$it\"" }}],")
            append("\"recorded_at\":\"${Instant.now()}\",")
            append("\"recorded_by\":\"FuzzTestBase\",")
            if (notes != null) append("\"notes\":\"${notes.replace("\"", "\\\"")}\",")
            append("\"passed\":${conclusion == "passed"}")
            append("}\n")
        }
        synchronized(FuzzTestBase::class.java) {
            recordsFile.appendText(json)
        }
    }

    /** 从测试工作目录向上找 `.quality/check-records.jsonl`，找不到返回 null。 */
    private fun locateQualityFile(): File? {
        var dir: File? = File(".").absoluteFile.canonicalFile
        repeat(6) {
            val candidate = File(dir, ".quality/check-records.jsonl")
            if (candidate.parentFile.exists()) return candidate
            dir = dir?.parentFile ?: return null
        }
        return null
    }

    /** 生成随机字节串。 */
    protected fun randomBytes(maxLen: Int): ByteArray {
        val len = seededRandom.nextInt(0, maxLen + 1)
        return ByteArray(len) { seededRandom.nextInt(0, 256).toByte() }
    }

    /** 生成随机 ASCII 字符串。 */
    protected fun randomAsciiString(maxLen: Int): String {
        val len = seededRandom.nextInt(0, maxLen + 1)
        return buildString(len) {
            repeat(len) { append(seededRandom.nextInt(32, 127).toChar()) }
        }
    }

    /** 生成随机 Unicode 字符串（覆盖 BMP 内常见区间 + emoji 代理对）。 */
    protected fun randomUnicodeString(maxLen: Int): String {
        val len = seededRandom.nextInt(0, maxLen + 1)
        val sb = StringBuilder(len * 2)
        repeat(len) {
            val cp = when (seededRandom.nextInt(0, 10)) {
                0, 1 -> seededRandom.nextInt(0x4E00, 0x9FFF)  // CJK 常用
                2 -> seededRandom.nextInt(0x1F300, 0x1F6FF)   // emoji（需代理对）
                3 -> seededRandom.nextInt(0x0080, 0x00FF)     // latin extended
                else -> seededRandom.nextInt(0x0020, 0x007F)  // printable ASCII
            }
            if (cp <= 0xFFFF) {
                sb.append(cp.toChar())
            } else {
                val offset = cp - 0x10000
                sb.append(((offset ushr 10) + 0xD800).toChar())
                sb.append(((offset and 0x3FF) + 0xDC00).toChar())
            }
        }
        return sb.toString()
    }
}
