package fs4jb

import java.nio.ByteBuffer
import kotlin.math.ceil

class Constants {
    companion object {
        const val BLOCK_SIZE = 4096
        const val BLOCK_SIZE_L = BLOCK_SIZE.toLong()
        
        const val MAGIC = 51966 // CAFE
        const val SUPERBLOCK_BLOCK_OFFSET = 0L
        const val SUPERBLOCK_SIZE = 16

        const val INODE_PROC = 10.0
        const val INODE_SIZE = 32
        const val LINKS_IN_INODE = 5
        const val INODE_TOTAL_LINKS_COUNT = LINKS_IN_INODE + BLOCK_SIZE / Int.SIZE_BYTES
        val INODES_PER_BLOCK_D = ceil(BLOCK_SIZE / INODE_SIZE.toDouble())
        val INODES_PER_BLOCK = INODES_PER_BLOCK_D.toInt()

        // TODO : find a better place, not really a constant
        val ZERO_DATA_BLOCK : ByteBuffer
            get() = field.duplicate()

        init {
            ZERO_DATA_BLOCK = ByteBuffer.allocate(BLOCK_SIZE)
            ZERO_DATA_BLOCK.put(ByteArray(BLOCK_SIZE))
            ZERO_DATA_BLOCK.rewind()
        }
    }
}