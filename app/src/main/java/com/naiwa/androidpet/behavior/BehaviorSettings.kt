package com.naiwa.androidpet.behavior

data class BehaviorSettings(
    val actionMinMillis: Long = 3_000,
    val actionMaxMillis: Long = 8_000,
    val moveMinMillis: Long = 30_000,
    val moveMaxMillis: Long = 90_000,
    val releaseDelayMillis: Long = 1_500,
    val moveSpeedDpPerSecond: Double = 32.0,
    val automaticByDefault: Boolean = true,
    val movementByDefault: Boolean = false,
) {
    init {
        require(actionMinMillis in 1_000..60_000 && actionMaxMillis in actionMinMillis..60_000)
        require(moveMinMillis in 1_000..3_600_000 && moveMaxMillis in moveMinMillis..3_600_000)
        require(releaseDelayMillis in 0..60_000)
        require(moveSpeedDpPerSecond.isFinite() && moveSpeedDpPerSecond in 1.0..200.0)
    }
    internal fun actionDelay(random: BehaviorRandom): Long =
        actionMinMillis + random.nextInt((actionMaxMillis-actionMinMillis+1).toInt())
    internal fun moveDelay(random: BehaviorRandom): Long =
        moveMinMillis + random.nextInt((moveMaxMillis-moveMinMillis+1).toInt())
}
