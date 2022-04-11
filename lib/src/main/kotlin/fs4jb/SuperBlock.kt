package fs4jb

import java.nio.ByteBuffer
import kotlin.math.ceil

data class SuperBlock(val magicNumber: Int,
                      val blocks : Int,
                      val inodeBlocks : Int,
                      val inodes : Int) {

    constructor(blocks : Int) : this(
        magicNumber = Constants.MAGIC,
        blocks = blocks,
        inodeBlocks = ceil(blocks / Constants.INODE_PROC).toInt(),
        inodes = Constants.INODES_PER_BLOCK * (ceil(blocks / Constants.INODE_PROC).toInt())
    )

    fun write(disk: Disk) {
        val buf = ByteBuffer.allocate(Constants.SUPERBLOCK_SIZE)
        buf.putInt(magicNumber)
        buf.putInt(blocks)
        buf.putInt(inodeBlocks)
        buf.putInt(inodes)
        disk.writeSb(buf)
    }

    companion object {
        fun read(disk: Disk) : SuperBlock {
            val buf = ByteBuffer.allocate(Constants.SUPERBLOCK_SIZE)
            disk.readSb(buf)
            return SuperBlock(magicNumber = buf.getInt(0 * Int.SIZE_BYTES),
                blocks = buf.getInt(1 * Int.SIZE_BYTES),
                inodeBlocks = buf.getInt(2 * Int.SIZE_BYTES),
                inodes = buf.getInt(3 * Int.SIZE_BYTES))
        }
    }

}