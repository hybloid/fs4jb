package fs4jb

class LRU(val purgeRoutine: (Any, Any) -> Unit) {
    private val cache = LinkedHashMap<Any, Any>()
    private val keyCycle = ArrayDeque<Any>()

    fun put(idx: Any, elem: Any) {
        if (cache.size >= Constants.LRU_CACHE_LIMIT) {
            val lastIdx = keyCycle.removeLast()
            val entry = cache.remove(lastIdx) ?: throw FSBrokenStateException("FS Cache problem")
            purgeRoutine(lastIdx, entry)
        }
        cache[idx] = elem
        keyCycle.remove(idx)
        keyCycle.addFirst(idx)
    }

    fun get(idx: Any): Any? {
        val entry = cache[idx] ?: return null
        keyCycle.remove(idx)
        keyCycle.addFirst(idx)
        return entry
    }

    fun processRemaining() = cache.entries.forEach { purgeRoutine(it.key, it.value) }

    fun clear() {
        cache.clear()
        keyCycle.clear()
    }
}