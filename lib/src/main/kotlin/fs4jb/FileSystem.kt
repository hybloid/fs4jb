package fs4jb

import mu.KotlinLogging
import java.nio.ByteBuffer
import kotlin.collections.ArrayDeque


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

    // FIXME : Test only argument, example of bad design. Remove and adjust all failing tests
    fun format(skipRootCreation : Boolean = false) {
        sb = SuperBlock(disk.nBlocks)
        disk.open(true)
        sb.write(disk)
        // reserve data for inodes
        for (i in 0 until sb.inodeBlocks) {
            disk.write(i, Constants.ZERO_BLOCK())
        }
        if (!skipRootCreation) {
            for (i in 0 until sb.inodeBlocks * Constants.INODES_PER_BLOCK) {
                freeInodes.addLast(i)
            }
            for (i in sb.inodeBlocks until sb.blocks) {
                freeDataBlocks.addLast(i)
            }
            createDir(Constants.DELIMITER)
        }
        disk.close()
        logger.info("Disk formatted with ${disk.nBlocks} blocks")
    }

    fun getRootFolder() = retrieveINode(0)

    fun createFile(name : String, target : INode) : INode {
        val inode = createINode()
        writeInodeToDisk(inode, Constants.ZERO_BLOCK())
        addToDir(name, inode, target)
        return inode
    }

    fun createDir(name : String, target : INode? = null) : INode {
        val inode = createINode()
        inode.isDir = true
        val buffer = ByteBuffer.allocate(Constants.DENTRY_SIZE * 2)
        buffer.put(".".toByteArray())
        buffer.position(Constants.FILENAME_SIZE)
        buffer.putInt(inode.number)
        buffer.put("..".toByteArray())
        buffer.position(Constants.DENTRY_SIZE + Constants.FILENAME_SIZE)
        if (target == null) {
            buffer.putInt(inode.number)
        } else {
            buffer.putInt(target.number)
        }
        write(inode, buffer, 0, buffer.limit())
        if (target != null) {
            addToDir(name, inode, target)
        }
        return inode
    }

    fun addToDir(name : String, source: INode, target : INode) {
        // FIXME: we do not check name duplicates here
        // FIXME: we do not check . and .. here
        assert(!name.contains(Constants.DELIMITER))
        assert(name.length <= Constants.FILENAME_SIZE)
        val bufToAppend = ByteBuffer.allocate(Constants.DENTRY_SIZE)
        bufToAppend.put(wrapDentryName(name))
        bufToAppend.putInt(source.number)
        write(target, bufToAppend, target.size, bufToAppend.limit())
    }

    fun removeFromDir(source: INode, target : INode) {
        // FIXME: we do not check . and .. here
        assert(target.size.mod(Constants.DENTRY_SIZE) == 0)
        val dentries = target.size / Constants.DENTRY_SIZE
        assert(dentries >= 2)
        val targetBuf = ByteBuffer.allocate(target.size) // Expensive
        read(target, targetBuf, 0, target.size)
        for (i in 0 until dentries) {
            val num = targetBuf.getInt(i * Constants.DENTRY_SIZE + Constants.FILENAME_SIZE)
            if (num == source.number) {
                if (i == dentries - 1) {
                    // corner case
                    truncate(target, i * Constants.DENTRY_SIZE)
                    return
                } else {
                    val restBuf = ByteBuffer.allocate((dentries - i) * Constants.DENTRY_SIZE)
                    targetBuf.position((i + 1) * Constants.DENTRY_SIZE)
                    restBuf.put(targetBuf)
                    truncate(target, i * Constants.DENTRY_SIZE)
                    write(target, restBuf, i * Constants.DENTRY_SIZE, restBuf.limit())
                    return
                }
            }
        }
        assert(false) // we should never reach this point
    }

    fun findInDir(name : String, target: INode) : INode? {
        assert(target.size.mod(Constants.DENTRY_SIZE) == 0)
        val dentries = target.size / Constants.DENTRY_SIZE
        assert(dentries >= 2)
        val targetBuf = ByteBuffer.allocate(target.size) // Expensive
        read(target, targetBuf, 0, target.size)
        val bufferForNextName = ByteArray(Constants.FILENAME_SIZE)
        val bufWithNameToCompare = wrapDentryName(name)
        val arrayWithTargetName = bufWithNameToCompare.array()
        for (i in 0 until dentries) {
            targetBuf.position(i * Constants.DENTRY_SIZE)
            targetBuf.get(bufferForNextName)
            if (arrayWithTargetName.contentEquals(bufferForNextName)) {
                val num = targetBuf.getInt(i * Constants.DENTRY_SIZE + Constants.FILENAME_SIZE)
                return retrieveINode(num)
            }
        }
        return null
    }

    fun listDir(target: INode) : List<Pair<String, Int>> {
        assert(target.size.mod(Constants.DENTRY_SIZE) == 0)
        val dentries = target.size / Constants.DENTRY_SIZE
        assert(dentries >= 2)
        val list = mutableListOf<Pair<String, Int>>()
        val targetBuf = ByteBuffer.allocate(target.size) // Expensive
        read(target, targetBuf, 0, target.size)
        val bufferForName = ByteArray(Constants.FILENAME_SIZE)
        for (i in 0 until dentries) {
            targetBuf.get(bufferForName)
            val name = String(trim(bufferForName), Constants.CHARSET)
            val num = targetBuf.int
            list.add(Pair(name, num))
        }
        return list
    }

    private fun wrapDentryName(name : String) : ByteBuffer {
        val bufWithNameToCompare = ByteBuffer.allocate(Constants.FILENAME_SIZE)
        bufWithNameToCompare.put(name.toByteArray(Constants.CHARSET))
        bufWithNameToCompare.rewind()
        return bufWithNameToCompare
    }

    private fun trim(bytes: ByteArray): ByteArray {
        var i = bytes.size - 1
        while (i >= 0 && bytes[i].toInt() == 0) {
            --i
        }
        return bytes.copyOf(i + 1)
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
        if (inode.indirectLoadNeeded()) {
            // dummy safety, already done by retrieve node
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
        if (inode.indirectLoadNeeded()) {
            // dummy safety, already done by retrieve node
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
        buffer.rewind()
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
            writeInodeToDisk(inode, buf)
        }
        return length
    }

    fun truncate(inode : INode, offset : Int) {
        assert(offset >= 0)
        assert(offset < inode.size)
        val buf = Constants.ZERO_BLOCK()
        if (inode.indirectLoadNeeded()) {
            // dummy safety, already done by retrieve node
            disk.read(inode.indirect, buf)
            inode.readIndirect(buf)
        }
        val blockId = offset / Constants.BLOCK_SIZE
        val lastBlockId = inode.size / Constants.BLOCK_SIZE
        for (i in lastBlockId downTo blockId + 1) {
            if (inode.links[i] != 0) {
                freeDataBlocks.addFirst(inode.links[i])
                inode.links[i] = 0
            }
        }
        if (blockId < Constants.LINKS_IN_INODE && inode.indirect != 0) {
            freeDataBlocks.addFirst(inode.indirect)
            inode.indirect = 0
        }
        if (offset == 0) {
            // special case, remove all blocks
            if (inode.links[0] != 0) {
                freeDataBlocks.addFirst(inode.links[0])
                inode.links[0] = 0
            }
        } else {
            val positionInLastBlock = offset.mod(Constants.BLOCK_SIZE)
            disk.read(inode.links[blockId], buf)
            buf.position(positionInLastBlock)
            for (i in positionInLastBlock until Constants.BLOCK_SIZE) {
                buf.put(0)
            }
            disk.write(inode.links[blockId], buf)
        }
        inode.size = offset
        writeInodeToDisk(inode, buf)
    }

    private fun writeInodeToDisk(inode: INode, buf : ByteBuffer) {
        disk.read(INode.getBlockNumber(inode.number), buf)
        inode.write(buf)
        disk.write(INode.getBlockNumber(inode.number), buf)
        if (inode.indirect != 0) {
            inode.writeIndirect(buf)
            disk.write(inode.indirect, buf)
        }
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
                        busyBlocks.add(iNode.indirect)
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

