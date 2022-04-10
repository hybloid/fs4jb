package fs4jb

import java.nio.ByteBuffer
import kotlin.math.floor

class INode (val number : Int,
             var valid : Boolean,
             var size : Int,
             var links : Array<Int>,
             var indirect : Int) {

    fun readIndirect(buffer: ByteBuffer) {
        assert(indirect != 0)
        // TODO : should we stop loading on 0? or better set 0
        for (i in Constants.LINKS_IN_INODE until Constants.INODE_TOTAL_LINKS_COUNT) {
            links[i] = buffer.int
        }
    }

    fun writeIndirect(buffer: ByteBuffer) {
        assert(indirect != 0)
        // TODO : should we stop loading on 0? or better set 0
        for (i in Constants.LINKS_IN_INODE until Constants.INODE_TOTAL_LINKS_COUNT) {
            buffer.putInt(links[i])
        }
    }

    fun write(buffer: ByteBuffer) {
        buffer.position(getPositionInBlock(number))
        buffer.putInt(if (valid) 1  else 0)
        buffer.putInt(size)
        for (i in 0 until Constants.LINKS_IN_INODE) {
            buffer.putInt(links[i])
        }
        buffer.putInt(indirect)
        // TODO : handle indirect
    }

    companion object {
        fun read(number : Int, buffer : ByteBuffer) : INode {
            buffer.position(getPositionInBlock(number))
            val valid = buffer.int != 0  // TODO: support other fields with BitMask
            val size = buffer.int
            val links = Array(Constants.INODE_TOTAL_LINKS_COUNT) { 0 }
            for (i in 0 until Constants.LINKS_IN_INODE) {
                links[i] = buffer.int
            }
            val indirect = buffer.int
            return INode(number, valid, size, links, indirect)
        }

        private fun getBlockNumber(number: Int) = floor(number / Constants.INODES_PER_BLOCK_D).toInt()
        private fun getPositionInBlock(number: Int) = number - getBlockNumber(number) * Constants.INODES_PER_BLOCK
    }
}