package fs4jb

class Metrics {
    companion object {
        var reads = 0
        var writes = 0

        fun incRead() = reads++
        fun incWrite() = writes++
        fun reset() {
            reads = 0
            writes = 0
        }
    }
}