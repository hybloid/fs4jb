package fs4jb

class LRU<T, E>(val purgeRoutine: (T, E) -> Unit) {
    private val cache = LinkedHashMap<T, E>()
    private val keyCycle = ArrayDeque<T>()

    fun put(idx: T, elem: E) {
        if (cache.size >= Constants.LRU_CACHE_LIMIT) {
            val lastIdx = keyCycle.removeLast()
            val entry = cache.remove(lastIdx) ?: throw FSBrokenStateException("FS Cache problem")
            purgeRoutine(lastIdx, entry)
        }
        cache[idx] = elem
        keyCycle.remove(idx)
        keyCycle.addFirst(idx)
    }

    fun get(idx: T): Any? {
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