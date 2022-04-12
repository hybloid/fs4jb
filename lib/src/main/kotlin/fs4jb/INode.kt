package fs4jb

import fs4jb.BitMaskOps.Companion.check
import fs4jb.BitMaskOps.Companion.set
import java.nio.ByteBuffer

data class INode(
    val number: Int,
    var valid: Boolean,
    var isDir: Boolean,
    var size: Int,
    var links: Array<Int>,
    var indirect: Int
) {

    fun readIndirect(buffer: ByteBuffer) {
        if (indirect == 0) throw FSIllegalStateException("Illegal state, operation not possible")
        // TODO : should we stop loading on 0? or better set 0
        for (i in Constants.LINKS_IN_INODE until Constants.INODE_TOTAL_LINKS_COUNT) {
            links[i] = buffer.int
        }
    }

    fun writeIndirect(buffer: ByteBuffer) {
        if (indirect == 0) throw FSIllegalStateException("Illegal state, operation not possible")
        buffer.clear()
        // TODO : should we stop loading on 0? or better set 0
        for (i in Constants.LINKS_IN_INODE until Constants.INODE_TOTAL_LINKS_COUNT) {
            buffer.putInt(links[i])
        }
    }

    fun write(buffer: ByteBuffer) {
        buffer.position(getPositionInBlock(number))
        buffer.putInt(getMask())
        buffer.putInt(size)
        for (i in 0 until Constants.LINKS_IN_INODE) {
            buffer.putInt(links[i])
        }
        buffer.putInt(indirect)
        // TODO : handle indirect
    }

    private fun getMask() = 0.set(0, valid).set(1, isDir && valid)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as INode

        if (number != other.number) return false
        if (valid != other.valid) return false
        if (isDir != other.isDir) return false
        if (size != other.size) return false
        if (!links.contentEquals(other.links)) return false
        if (indirect != other.indirect) return false

        return true
    }

    override fun hashCode(): Int {
        var result = number
        result = 31 * result + valid.hashCode()
        result = 31 * result + isDir.hashCode()
        result = 31 * result + size
        result = 31 * result + links.contentHashCode()
        result = 31 * result + indirect
        return result
    }

    companion object {
        fun read(number: Int, buffer: ByteBuffer): INode {
            buffer.position(getPositionInBlock(number))
            val mask = buffer.int
            val valid = mask.check(0)
            val isDir = mask.check(1)
            val links = Constants.LINKS_ARRAY.copyOf()

            return if (valid) {
                val size = buffer.int
                for (i in 0 until Constants.LINKS_IN_INODE) {
                    links[i] = buffer.int
                }
                val indirect = buffer.int
                INode(number, true, isDir, size, links, indirect)
            } else {
                INode(number, false, isDir, 0, links, 0)
            }
        }

        fun isValid(number: Int, buffer: ByteBuffer): Boolean {
            val mask = buffer.getInt(getPositionInBlock(number))
            return mask.check(0)
        }

        fun getBlockNumber(number: Int) = number / Constants.INODES_PER_BLOCK
        private fun getPositionInBlock(number: Int) = number.mod(Constants.INODES_PER_BLOCK) * Constants.INODE_SIZE
    }
}