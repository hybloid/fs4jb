package fs4jb

import mu.KotlinLogging
import java.nio.ByteBuffer
import kotlin.collections.ArrayDeque


class FileSystem(private val disk: Disk) {
    lateinit var sb: SuperBlock
    private val logger = KotlinLogging.logger {}
    private val freeInodes = ArrayDeque<Int>()
    private val freeDataBlocks = ArrayDeque<Int>()

    fun freeStat() = Pair(freeInodes.size, freeDataBlocks.size) // TODO : Convert to normal stats

    /**
     * FS Main routines
     */
    // FIXME : Test only argument, example of bad design. Remove and adjust all failing tests
    fun format(skipRootCreation: Boolean = false) {
        sb = SuperBlock(disk.nBlocks)
        disk.open(true)
        sb.write(disk)
        // reserve data for inodes
        for (i in 0 until sb.inodeBlocks) {
            disk.write(i, Constants.zeroBlock())
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

    /**
     * FS i-node routines
     */
    fun createINode(): INode {
        assert(freeInodes.size > 0)
        val buf = Constants.zeroBlock()
        val freeInodeNumber = freeInodes.removeFirst()
        disk.read(INode.getBlockNumber(freeInodeNumber), buf)
        val inode = INode.read(freeInodeNumber, buf)
        inode.valid = true
        // TODO : let's try to postpone writing this inode
        return inode
    }

    fun retrieveINode(n: Int): INode {
        val buf = Constants.zeroBlock()
        disk.read(INode.getBlockNumber(n), buf)
        val inode = INode.read(n, buf)
        if (inode.indirect != 0) {
            disk.read(inode.indirect, buf)
            inode.readIndirect(buf)
        }
        return inode
    }

    /**
     * FS file/directory ops
     */
    fun getRootFolder() = retrieveINode(0)

    fun createFile(name: String, target: INode): INode {
        val inode = createINode()
        writeInodeToDisk(inode, Constants.zeroBlock())
        addToDir(name, inode, target)
        return inode
    }

    fun createDir(name: String, target: INode? = null): INode {
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

    fun addToDir(name: String, source: INode, target: INode) {
        // FIXME: we do not check name duplicates here
        // FIXME: we do not check . and .. here
        assert(!name.contains(Constants.DELIMITER))
        assert(name.length <= Constants.FILENAME_SIZE)
        val buffer = ByteBuffer.allocate(Constants.DENTRY_SIZE)
        buffer.put(wrapNameForDentry(name))
        buffer.putInt(source.number)
        write(target, buffer, target.size, buffer.limit())
    }

    fun removeFromDir(source: INode, target: INode) {
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

    fun findInDir(name: String, target: INode): INode? {
        assert(target.size.mod(Constants.DENTRY_SIZE) == 0)
        val dentries = target.size / Constants.DENTRY_SIZE
        assert(dentries >= 2)
        val targetBuf = ByteBuffer.allocate(target.size) // Expensive
        read(target, targetBuf, 0, target.size)
        val dentryName = ByteArray(Constants.FILENAME_SIZE)
        val targetName = wrapNameForDentry(name).array()
        for (i in 0 until dentries) {
            targetBuf.position(i * Constants.DENTRY_SIZE)
            targetBuf.get(dentryName)
            if (targetName.contentEquals(dentryName)) {
                val num = targetBuf.getInt(i * Constants.DENTRY_SIZE + Constants.FILENAME_SIZE)
                return retrieveINode(num)
            }
        }
        return null
    }

    fun listDir(target: INode): List<Pair<String, Int>> {
        assert(target.size.mod(Constants.DENTRY_SIZE) == 0)
        val dentries = target.size / Constants.DENTRY_SIZE
        assert(dentries >= 2)
        val list = mutableListOf<Pair<String, Int>>()
        val targetBuf = ByteBuffer.allocate(target.size) // Expensive
        read(target, targetBuf, 0, target.size)
        val dentryName = ByteArray(Constants.FILENAME_SIZE)
        for (i in 0 until dentries) {
            targetBuf.get(dentryName)
            val name = String(trim(dentryName), Constants.CHARSET)
            val num = targetBuf.int
            list.add(Pair(name, num))
        }
        return list
    }

    /**
     * FS low level read-write routines
     */
    fun read(inode: INode, buffer: ByteBuffer, start: Int, length: Int): Int {
        val end = start + length - 1
        assert(start >= 0)
        assert(end < inode.size)
        assert(buffer.capacity() >= length)
        buffer.clear()

        val buf = Constants.zeroBlock()
        if (inode.indirectLoadNeeded()) {
            // dummy safety, already done by retrieve node
            disk.read(inode.indirect, buf)
            inode.readIndirect(buf)
        }
        val startBlockId = start / Constants.BLOCK_SIZE
        val startBlockPosition = start.mod(Constants.BLOCK_SIZE)
        val endBlockId = end / Constants.BLOCK_SIZE
        val endBlockPosition = end.mod(Constants.BLOCK_SIZE)

        var readCount = 0
        disk.read(inode.links[startBlockId], buf)
        if (startBlockId == endBlockId) {
            // special case
            val lengthToRead = endBlockPosition - startBlockPosition + 1
            buffer.put(buf.array(), startBlockPosition, lengthToRead)
            readCount += lengthToRead
        } else {
            val startPartLength = Constants.BLOCK_SIZE - startBlockPosition
            val endPartLength = endBlockPosition + 1
            buffer.put(buf.array(), startBlockPosition, startPartLength)
            readCount += startPartLength
            for (i in startBlockId + 1 until endBlockId) {
                disk.read(inode.links[i], buf)
                buffer.put(buf)
                readCount += Constants.BLOCK_SIZE
            }
            disk.read(inode.links[endBlockId], buf)
            buffer.put(buf.array(), 0, endPartLength)
            readCount += endPartLength
        }
        assert(readCount == length)
        buffer.flip()
        return length
    }

    fun write(inode: INode, buffer: ByteBuffer, start: Int, length: Int): Int {
        val end = start + length - 1
        assert(start >= 0)
        assert(end < Constants.INODE_TOTAL_LINKS_COUNT * Constants.BLOCK_SIZE)
        assert(buffer.capacity() >= length)
        buffer.rewind()

        var buf = Constants.zeroBlock()
        if (inode.indirectLoadNeeded()) {
            // dummy safety, already done by retrieve node
            disk.read(inode.indirect, buf)
            inode.readIndirect(buf)
        }
        // Need it empty for allocation of new blocks
        buf = Constants.zeroBlock()
        val startBlockId = start / Constants.BLOCK_SIZE
        val startBlockPosition = start.mod(Constants.BLOCK_SIZE)
        val endBlockId = end / Constants.BLOCK_SIZE
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
        var writeCount = 0
        if (startBlockId == endBlockId) {
            // special case
            buf.put(buffer)
            assert(buf.position() == endBlockPosition + 1)
            disk.write(inode.links[startBlockId], buf)
            writeCount = buffer.limit()
        } else {
            val startPartLength = Constants.BLOCK_SIZE - startBlockPosition
            val endPartLength = endBlockPosition + 1
            buf.put(buffer.array(), 0, startPartLength)
            disk.write(inode.links[startBlockId], buf)
            writeCount += startPartLength
            buf.clear()
            for (i in startBlockId + 1 until endBlockId) {
                buf.put(buffer.array(), writeCount, Constants.BLOCK_SIZE)
                disk.write(inode.links[i], buf)
                writeCount += Constants.BLOCK_SIZE
                buf.clear()
            }
            buf.put(buffer.array(), writeCount, endPartLength)
            disk.write(inode.links[endBlockId], buf)
            writeCount += endPartLength
        }
        assert(writeCount == length)
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

    fun truncate(inode: INode, offset: Int) {
        assert(offset >= 0)
        assert(offset < inode.size)
        val buf = Constants.zeroBlock()
        if (inode.indirectLoadNeeded()) {
            // dummy safety, already done by retrieve node
            disk.read(inode.indirect, buf)
            inode.readIndirect(buf)
        }
        val startBlockId = offset / Constants.BLOCK_SIZE
        val lastBlockId = inode.size / Constants.BLOCK_SIZE
        for (i in lastBlockId downTo startBlockId + 1) {
            if (inode.links[i] != 0) {
                freeDataBlocks.addFirst(inode.links[i])
                inode.links[i] = 0
            }
        }
        if (startBlockId < Constants.LINKS_IN_INODE && inode.indirect != 0) {
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
            disk.read(inode.links[startBlockId], buf)
            buf.position(positionInLastBlock)
            for (i in positionInLastBlock until Constants.BLOCK_SIZE) {
                buf.put(0)
            }
            disk.write(inode.links[startBlockId], buf)
        }
        inode.size = offset
        writeInodeToDisk(inode, buf)
    }

    /**
     * FS Misc routines
     */
    private fun writeInodeToDisk(inode: INode, buf: ByteBuffer) {
        disk.read(INode.getBlockNumber(inode.number), buf)
        inode.write(buf)
        disk.write(INode.getBlockNumber(inode.number), buf)
        if (inode.indirect != 0) {
            inode.writeIndirect(buf)
            disk.write(inode.indirect, buf)
        }
    }

    private fun wrapNameForDentry(name: String): ByteBuffer {
        val buffer = ByteBuffer.allocate(Constants.FILENAME_SIZE)
        buffer.put(name.toByteArray(Constants.CHARSET))
        buffer.rewind()
        return buffer
    }

    private fun trim(bytes: ByteArray): ByteArray {
        var i = bytes.size - 1
        while (i >= 0 && bytes[i].toInt() == 0) {
            --i
        }
        return bytes.copyOf(i + 1)
    }

    private fun fsck() {
        // TODO : introduce states and check the state of FS
        val buf = Constants.zeroBlock()
        val indirectBuf = Constants.zeroBlock()
        val busyBlocks = mutableSetOf<Int>()
        for (i in 0 until sb.inodeBlocks) {
            disk.read(i, buf)
            for (j in 0 until Constants.INODES_PER_BLOCK) {
                val number = i * Constants.INODES_PER_BLOCK + j
                val inode = INode.read(number, buf)
                if (inode.valid) {
                    if (inode.indirect != 0) {
                        disk.read(inode.indirect, indirectBuf)
                        inode.readIndirect(indirectBuf)
                        busyBlocks.add(inode.indirect)
                    }
                    for (k in 0..Constants.INODE_TOTAL_LINKS_COUNT) {
                        if (inode.links[k] != 0) {
                            busyBlocks.add(inode.links[k])
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

