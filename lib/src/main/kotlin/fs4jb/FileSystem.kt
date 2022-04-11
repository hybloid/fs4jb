package fs4jb

import mu.KotlinLogging
import java.nio.ByteBuffer

class FileSystem(val disk: Disk) {
    lateinit var sb: SuperBlock
    private val logger = KotlinLogging.logger {}
    private val freeInodes = ArrayDeque<Int>()
    private val freeDataBlocks = ArrayDeque<Int>()

    fun freeStat() = Pair(freeInodes.size, freeDataBlocks.size) // TODO : Convert to normal stats

    fun debug() {
        disk.open()
        val block = SuperBlock.read(disk)
        disk.close()
        logger.info { block }
    }

    fun format() {
        val block = SuperBlock(disk.nBlocks)
        disk.open(true)
        block.write(disk)
        // reserve data for inodes
        for (i in 0 until block.inodeBlocks) {
            disk.write(i, Constants.ZERO_BLOCK())
        }
        disk.close()
        logger.info("Disk formatted with ${disk.nBlocks} blocks")
    }

    fun mount() {
        disk.open()
        sb = SuperBlock.read(disk)
        assert(sb.magicNumber == Constants.MAGIC) // TODO : add hard check
        logger.info("Disk mounted")
        fsck()
        logger.info("FSCK completed")
    }

    fun umount() {
        disk.close()
        logger.info("Disk unmounted")
        freeInodes.clear()
        freeDataBlocks.clear()
    }

    fun createINode() : INode {
        assert(freeInodes.size > 0)
        val buf = Constants.ZERO_BLOCK()
        val nextNumber = freeInodes.removeFirst()
        disk.read(INode.getBlockNumber(nextNumber), buf)
        val inode = INode.read(nextNumber, buf)
        inode.valid = true
        // TODO : let's try to postpone writing this inode
        return inode
    }

    fun retrieveINode(n : Int) : INode {
        val buf = Constants.ZERO_BLOCK()
        disk.read(INode.getBlockNumber(n), buf)
        val inode = INode.read(n, buf)
        if (inode.indirect != 0) {
            disk.read(inode.indirect, buf)
            inode.readIndirect(buf)
        }
        return inode
    }

    // TODO : get rid of INode, use number
    fun read(inode : INode, buffer : ByteBuffer, start : Int, length : Int) : Int {
        val end = start + length - 1
        assert(start >= 0)
        assert(end < inode.size)
        assert(buffer.capacity() >= length)
        buffer.clear()

        val buf = Constants.ZERO_BLOCK()
        // TODO: minimize calls to this, this is currently a bad hack
        if (inode.indirect != 0 && inode.links[Constants.LINKS_IN_INODE] == 0) {
            disk.read(inode.indirect, buf)
            inode.readIndirect(buf)
        }
        val startBlockId = start / Constants.BLOCK_SIZE
        val startBlockPosition = start.mod(Constants.BLOCK_SIZE)
        val endBlockId =  end / Constants.BLOCK_SIZE
        val endBlockPosition = end.mod(Constants.BLOCK_SIZE)

        var readen = 0
        disk.read(inode.links[startBlockId], buf)
        if (startBlockId == endBlockId) {
            // special case
            val lengthToRead = endBlockPosition - startBlockPosition + 1
            buffer.put(buf.array(), startBlockPosition, lengthToRead)
            readen += lengthToRead
        } else {
            val startPartLength = Constants.BLOCK_SIZE - startBlockPosition
            val endPartLength = endBlockPosition + 1
            buffer.put(buf.array(), startBlockPosition, startPartLength)
            readen += startPartLength
            for (i in startBlockId + 1 until endBlockId) {
                disk.read(inode.links[i], buf)
                buffer.put(buf)
                readen += Constants.BLOCK_SIZE
            }
            disk.read(inode.links[endBlockId], buf)
            buffer.put(buf.array(), 0, endPartLength)
            readen += endPartLength
        }
        assert(readen == length)
        buffer.flip()
        return length
    }

    // TODO : get rid of INode, use number
    fun write(inode : INode, buffer : ByteBuffer, start : Int, length : Int) : Int {
        val end = start + length - 1
        assert(start >= 0)
        assert(end < Constants.INODE_TOTAL_LINKS_COUNT * Constants.BLOCK_SIZE)
        assert(buffer.capacity() >= length)
        var buf = Constants.ZERO_BLOCK()
        // TODO: minimize calls to this, this is currently a bad hack
        if (inode.indirect != 0 && inode.links[Constants.LINKS_IN_INODE] == 0) {
            disk.read(inode.indirect, buf)
            inode.readIndirect(buf)
        }
        // Need it empty for allocation of new blocks
        buf = Constants.ZERO_BLOCK()
        val startBlockId = start / Constants.BLOCK_SIZE
        val startBlockPosition = start.mod(Constants.BLOCK_SIZE)
        val endBlockId =  end / Constants.BLOCK_SIZE
        val endBlockPosition = end.mod(Constants.BLOCK_SIZE)
        // allocate needed blocks
        var inodeUpdateNeeded = false
        for (i in startBlockId..endBlockId) {
            if (i >= Constants.LINKS_IN_INODE && inode.indirect == 0) {
                assert(freeDataBlocks.size > 0)
                inode.indirect = freeDataBlocks.removeFirst()
                disk.write(inode.indirect, buf) // TODO : do this only when this block was not allocated
                inodeUpdateNeeded = true
            }

            if (inode.links[i] == 0) {
                assert(freeDataBlocks.size > 0)
                inode.links[i] = freeDataBlocks.removeFirst()
                disk.write(inode.links[i], buf) // TODO : do this only when this block was not allocated
                inodeUpdateNeeded = true
            }
        }
        disk.read(inode.links[startBlockId], buf)
        buf.position(startBlockPosition)
        var written = 0
        if (startBlockId == endBlockId) {
            // special case
            buf.put(buffer)
            assert(buf.position() == endBlockPosition + 1)
            disk.write(inode.links[startBlockId], buf)
            written = buffer.limit()
        } else {
            val startPartLength = Constants.BLOCK_SIZE - startBlockPosition
            val endPartLength = endBlockPosition + 1
            buf.put(buffer.array(), 0, startPartLength)
            disk.write(inode.links[startBlockId], buf)
            written += startPartLength
            buf.clear()
            for (i in startBlockId + 1 until endBlockId) {
                buf.put(buffer.array(), written, Constants.BLOCK_SIZE)
                disk.write(inode.links[i], buf)
                written += Constants.BLOCK_SIZE
                buf.clear()
            }
            buf.put(buffer.array(), written, endPartLength)
            disk.write(inode.links[endBlockId], buf)
            written += endPartLength
        }
        assert(written == length)
        // UPDATE INODE SIZE
        if (inode.size < end + 1) {
            inode.size = end + 1
            inodeUpdateNeeded = true
        }
        if (inodeUpdateNeeded) {
            disk.read(INode.getBlockNumber(inode.number), buf)
            inode.write(buf)
            disk.write(INode.getBlockNumber(inode.number), buf)
            if (inode.indirect != 0) {
                inode.writeIndirect(buf)
                disk.write(inode.indirect, buf)
            }
        }
        return length
    }

    private fun fsck() {
        // TODO : introduce states and check the state of FS
        val buf = Constants.ZERO_BLOCK()
        val indirectBuf = Constants.ZERO_BLOCK()
        val busyBlocks = mutableSetOf<Int>()
        for (i in 0 until sb.inodeBlocks) {
            disk.read(i, buf)
            for (j in 0 until Constants.INODES_PER_BLOCK) {
                val number = i * Constants.INODES_PER_BLOCK + j
                val iNode = INode.read(number, buf)
                if (iNode.valid) {
                    if (iNode.indirect != 0) {
                        disk.read(iNode.indirect, indirectBuf)
                        iNode.readIndirect(indirectBuf)
                    }
                    for (k in 0 .. Constants.INODE_TOTAL_LINKS_COUNT) {
                        if (iNode.links[k] != 0) {
                            busyBlocks.add(iNode.links[k])
                        } else {
                            break // TODO : can be problematic in case of error in algorithm. Add assert and test
                        }
                    }
                } else {
                    freeInodes.addLast(number)
                }
            }
        }
        for (i in sb.inodeBlocks until sb.blocks) {
            if (!busyBlocks.contains(i)) {
                freeDataBlocks.addLast(i)
            }
        }
    }
}

