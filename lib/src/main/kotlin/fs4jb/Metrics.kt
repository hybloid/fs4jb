package fs4jb

import kotlin.time.Duration
import kotlin.time.ExperimentalTime

class Metrics {
    companion object {
        var lowLevelReads = 0
        var lowLevelWrites = 0
        var fileReads = 0
        var fileReadsTotalSpeed: Float = 0.0F
        var fileWrites = 0
        var fileWritesTotalSpeed: Float = 0.0F

        fun incRead() = lowLevelReads++
        fun incWrite() = lowLevelWrites++

        @OptIn(ExperimentalTime::class)
        fun logReadSpeed(size: Int, time: Duration) {
            fileReads++
            fileReadsTotalSpeed += (size.toFloat() / time.inWholeMicroseconds)
        }

        fun readSpeed() = if (fileReads == 0 || fileReadsTotalSpeed == 0.0F) {
            0
        } else {
            fileReadsTotalSpeed / fileReads * 1_000_000 / 1024
        }

        @OptIn(ExperimentalTime::class)
        fun logWriteSpeed(size: Int, time: Duration) {
            fileWrites++
            fileWritesTotalSpeed += (size.toFloat() / time.inWholeMicroseconds)
        }

        fun writeSpeed() = if (fileWrites == 0 || fileWritesTotalSpeed == 0.0F) {
            0
        } else {
            fileWritesTotalSpeed / fileWrites * 1_000_000 / 1024
        }

        fun reset() {
            lowLevelReads = 0
            lowLevelWrites = 0
            fileReads = 0
            fileReadsTotalSpeed = 0.0F
            fileWrites = 0
            fileWritesTotalSpeed = 0.0F
        }
    }
}