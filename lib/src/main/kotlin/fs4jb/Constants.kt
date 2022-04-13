package fs4jb

import java.nio.ByteBuffer
import java.nio.charset.Charset
import kotlin.math.ceil

class Constants {
    companion object {
        const val BLOCK_SIZE = 4096
        const val BLOCK_SIZE_L = BLOCK_SIZE.toLong()
        const val LRU_CACHE_LIMIT = 1000

        const val MAGIC = 51966 // CAFE
        const val SUPERBLOCK_BLOCK_OFFSET = 0L
        const val SUPERBLOCK_SIZE = 16

        const val INODE_PROC = 10.0
        const val INODE_SIZE = 32
        const val LINKS_IN_INODE = 5
        const val INODE_TOTAL_LINKS_COUNT = LINKS_IN_INODE + BLOCK_SIZE / Int.SIZE_BYTES
        val INODES_PER_BLOCK = ceil(BLOCK_SIZE / INODE_SIZE.toDouble()).toInt()
        val LINKS_ARRAY = Array(INODE_TOTAL_LINKS_COUNT) { 0 }

        const val DENTRY_SIZE = 128
        const val FILENAME_SIZE = DENTRY_SIZE - Int.SIZE_BYTES
        const val SEPARATOR = "/"
        val CHARSET: Charset = Charset.forName("ASCII")

        fun zeroBlock(): ByteBuffer {
            val buf = ByteBuffer.allocate(BLOCK_SIZE)
            buf.put(ZERO_BLOCK_CONTENT)
            buf.rewind()
            return buf
        }

        private val ZERO_BLOCK_CONTENT: ByteBuffer = ByteBuffer.allocate(BLOCK_SIZE)

        init {
            ZERO_BLOCK_CONTENT.put(ByteArray(BLOCK_SIZE))
            ZERO_BLOCK_CONTENT.rewind()
        }
    }
}