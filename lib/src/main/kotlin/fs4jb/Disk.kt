package fs4jb

import java.nio.ByteBuffer
import java.nio.file.StandardOpenOption.*
import java.nio.channels.FileChannel
import java.nio.file.Path

class Disk(private val path: Path, val nBlocks: Int) {
    private lateinit var channel: FileChannel

    fun open(recreate: Boolean = false) {
        Metrics.reset()
        channel = when (recreate) {
            true -> FileChannel.open(path, READ, WRITE, CREATE, TRUNCATE_EXISTING)
            else -> FileChannel.open(path, READ, WRITE, CREATE)
        }
    }

    fun close() {
        channel.force(true)
        channel.close()
    }

    fun readSb(buffer: ByteBuffer): Int {
        if (buffer.capacity() != Constants.SUPERBLOCK_SIZE) throw FSArgumentsException("Incorrect arguments")
        return channelRead(buffer, Constants.SUPERBLOCK_BLOCK_OFFSET)
    }

    fun writeSb(buffer: ByteBuffer): Int {
        if (buffer.capacity() != Constants.SUPERBLOCK_SIZE) throw FSArgumentsException("Incorrect arguments")
        return channelWrite(buffer, Constants.SUPERBLOCK_BLOCK_OFFSET)
    }

    fun read(blockNum: Int, buffer: ByteBuffer): Int {
        if (blockNum >= nBlocks || buffer.capacity() != Constants.BLOCK_SIZE) throw FSArgumentsException("Incorrect arguments")
        // TODO: cache

        val count = channelRead(buffer, blockOffset(blockNum))
        if (count != Constants.BLOCK_SIZE) throw FSIOException("IO error while reading")
        return count
    }

    fun write(blockNum: Int, buffer: ByteBuffer): Int {
        if (blockNum >= nBlocks || buffer.capacity() != Constants.BLOCK_SIZE) throw FSArgumentsException("Incorrect arguments")
        // TODO: cache reset

        val count = channelWrite(buffer, blockOffset(blockNum))
        if (count != Constants.BLOCK_SIZE) throw FSIOException("IO error while writing")
        return count
    }

    fun channelRead(buffer: ByteBuffer, offset: Long): Int {
        buffer.clear()
        Metrics.incRead()
        channel.position(offset)
        val count = channel.read(buffer)
        buffer.flip()
        return count
    }

    private fun blockOffset(blockNum: Int): Long =
        Constants.SUPERBLOCK_SIZE + blockNum * Constants.BLOCK_SIZE_L

    private fun channelWrite(buffer: ByteBuffer, offset: Long): Int {
        buffer.rewind()
        Metrics.incWrite()
        return channel.write(buffer, offset) // do not clear, since data could be reused (i.e. empty block)
    }
}