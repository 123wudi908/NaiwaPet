package com.naiwa.androidpet.update

object UpdatePresentation {
    fun message(result: CheckResult): String = when (result) {
        CheckResult.Idle -> "还没有检查过更新。"
        CheckResult.Checking -> "正在检查更新…"
        CheckResult.UpToDate -> "已经是最新版本啦！"
        is CheckResult.Available -> "发现新版本 ${result.release.manifest.versionName}"
        is CheckResult.Unavailable -> result.reason
        is CheckResult.Failure -> result.reason
    }
    fun showHomeCard(version: Long, ignored: Long, snoozedVersion: Long = 0,
        snoozeUntil: Long = 0, now: Long = 0): Boolean =
        version > 0 && version != ignored && !(version == snoozedVersion && now < snoozeUntil)
}

/** Persists completed results only; a missing or untrusted cached update is never presented as latest. */
object UpdateStatusCodec {
    fun encode(result: CheckResult): Pair<String, String> = when (result) {
        CheckResult.UpToDate -> "latest" to ""
        is CheckResult.Available -> "available" to ""
        is CheckResult.Unavailable -> "unavailable" to result.reason
        is CheckResult.Failure -> "failure" to result.reason
        else -> "" to ""
    }
    fun decode(kind: String, reason: String, lastCheck: Long): CheckResult = when (kind) {
        "latest" -> CheckResult.UpToDate
        "available" -> CheckResult.Unavailable("缓存的更新信息无法验证，请重新检查。")
        "unavailable" -> CheckResult.Unavailable(reason.ifBlank { "更新信息暂不可用。" })
        "failure" -> CheckResult.Failure(reason.ifBlank { "检查更新失败，请重试。" })
        else -> if (lastCheck == 0L) CheckResult.Idle else CheckResult.Unavailable("最近一次检查结果不可用，请重新检查。")
    }
}
