package fs4jb

import mu.KotlinLogging
import java.io.File
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
            mkdir(Constants.SEPARATOR)
        }
        disk.close()
        logger.info("Disk formatted with ${disk.nBlocks} blocks")
    }

    fun mount() {
        disk.open()
        sb = SuperBlock.read(disk)
        if (sb.magicNumber != Constants.MAGIC) throw FSArgumentsException("Supplied disk image has incorrect structure")
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

    fun remount() {
        umount()
        mount()
    }

    /**
     * FS i-node routines
     */
    fun createINode(): INode {
        if (freeInodes.size == 0) throw FSIOException("Not enough inodes")
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

    fun open(path: String): INode {
        var inode = getRootFolder()
        val subPaths = path.split(Constants.SEPARATOR)
        if (path == Constants.SEPARATOR) return inode
        for (i in 1 until subPaths.size) {
            inode = locate(subPaths[i], inode)
        }
        return inode
    }

    fun create(name: String, folder: INode): INode {
        val inode = createINode()
        writeInodeToDisk(inode, Constants.zeroBlock())
        addToDir(name, inode, folder)
        return inode
    }

    fun delete(entry: INode, folder: INode) {
        if (entry.isDir && ls(entry).any { it.first != "." && it.first != ".." }) {
            throw FSArgumentsException("Dir is not empty")
        }
        entry.valid = false // state will be written during truncate
        truncate(entry, 0)
        deleteFromDir(entry, folder)
    }

    fun move(entry: INode, srcFolder: INode, dstFolder: INode) {
        if (!srcFolder.isDir || !dstFolder.isDir) throw FSArgumentsException("Incorrect arguments")
        val name = deleteFromDir(entry, srcFolder)
        addToDir(name, entry, dstFolder)
    }

    fun rename(name: String, entry: INode, folder: INode) {
        if (!folder.isDir) throw FSArgumentsException("Incorrect arguments")
        // FIXME: we do not check . and .. here
        val dentries = folder.size / Constants.DENTRY_SIZE
        if (folder.size.mod(Constants.DENTRY_SIZE) != 0 || dentries < 2) throw FSBrokenStateException("FS is corrupted")
        val targetBuf = ByteBuffer.allocate(folder.size) // Expensive
        read(folder, targetBuf, 0, folder.size)
        for (i in 0 until dentries) {
            val num = targetBuf.getInt(i * Constants.DENTRY_SIZE + Constants.FILENAME_SIZE)
            if (num == entry.number) {
                targetBuf.position(i * Constants.DENTRY_SIZE)
                write(folder, wrapNameForDentry(name), i * Constants.DENTRY_SIZE, Constants.FILENAME_SIZE)
                return
            }
        }
        throw FSIOException("File not found")
    }

    fun mkdir(name: String, folder: INode? = null): INode {
        val inode = createINode()
        inode.isDir = true
        val buffer = ByteBuffer.allocate(Constants.DENTRY_SIZE * 2)
        buffer.put(wrapNameForDentry("."))
        buffer.putInt(inode.number)
        buffer.put(wrapNameForDentry(".."))
        if (folder == null) {
            buffer.putInt(inode.number)
        } else {
            buffer.putInt(folder.number)
        }
        write(inode, buffer, 0, buffer.limit())
        if (folder != null) {
            addToDir(name, inode, folder)
        }
        return inode
    }

    fun ls(folder: INode): List<Pair<String, Int>> {
        val dentries = folder.size / Constants.DENTRY_SIZE
        if (folder.size.mod(Constants.DENTRY_SIZE) != 0 || dentries < 2) throw FSBrokenStateException("FS is corrupted")
        val list = mutableListOf<Pair<String, Int>>()
        val targetBuf = ByteBuffer.allocate(folder.size) // Expensive
        read(folder, targetBuf, 0, folder.size)
        val dentryName = ByteArray(Constants.FILENAME_SIZE)
        for (i in 0 until dentries) {
            targetBuf.get(dentryName)
            val name = String(trim(dentryName), Constants.CHARSET)
            val num = targetBuf.int
            list.add(Pair(name, num))
        }
        return list
    }

    private fun addToDir(name: String, entry: INode, folder: INode) {
        // FIXME: we do not check name duplicates here
        // FIXME: we do not check . and .. here
        if (name.contains(Constants.SEPARATOR) || name.length > Constants.FILENAME_SIZE) {
            throw FSArgumentsException("Incorrect arguments")
        }
        val buffer = ByteBuffer.allocate(Constants.DENTRY_SIZE)
        buffer.put(wrapNameForDentry(name))
        buffer.putInt(entry.number)
        write(folder, buffer, folder.size, buffer.limit())
    }

    private fun deleteFromDir(entry: INode, folder: INode): String {
        if (!folder.isDir) throw FSArgumentsException("Incorrect arguments")
        // FIXME: we do not check . and .. here
        val dentries = folder.size / Constants.DENTRY_SIZE
        if (folder.size.mod(Constants.DENTRY_SIZE) != 0 || dentries < 2) throw FSBrokenStateException("FS is corrupted")
        val targetBuf = ByteBuffer.allocate(folder.size) // Expensive
        read(folder, targetBuf, 0, folder.size)
        for (i in 0 until dentries) {
            val num = targetBuf.getInt(i * Constants.DENTRY_SIZE + Constants.FILENAME_SIZE)
            if (num == entry.number) {
                val bufForName = ByteArray(Constants.FILENAME_SIZE)
                targetBuf.position(i * Constants.DENTRY_SIZE)
                targetBuf.get(bufForName)
                val name = String(trim(bufForName), Constants.CHARSET)
                if (i == dentries - 1) {
                    // corner case
                    truncate(folder, i * Constants.DENTRY_SIZE)
                } else {
                    val restBuf = ByteBuffer.allocate((dentries - i - 1) * Constants.DENTRY_SIZE)
                    targetBuf.position((i + 1) * Constants.DENTRY_SIZE)
                    restBuf.put(targetBuf)
                    truncate(folder, i * Constants.DENTRY_SIZE)
                    write(folder, restBuf, i * Constants.DENTRY_SIZE, restBuf.limit())
                }
                return name
            }
        }
        throw FSIOException("File not found")
    }

    private fun locate(name: String, folder: INode): INode {
        val dentries = folder.size / Constants.DENTRY_SIZE
        if (folder.size.mod(Constants.DENTRY_SIZE) != 0 || dentries < 2) throw FSBrokenStateException("FS is corrupted")
        val targetBuf = ByteBuffer.allocate(folder.size) // Expensive
        read(folder, targetBuf, 0, folder.size)
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
        throw FSIOException("File not found")
    }

    /**
     * FS low level read-write routines
     */
    fun readToEnd(inode: INode): ByteArray {
        val buffer = ByteBuffer.allocate(inode.size)
        read(inode, buffer)
        return buffer.array()
    }

    fun read(inode: INode, buffer: ByteBuffer) = read(inode, buffer, 0, buffer.limit())

    fun read(inode: INode, buffer: ByteBuffer, start: Int, length: Int): Int {
        if (length == 0) {
            buffer.clear()
            return 0
        }
        val end = start + length - 1
        if (start < 0 || end >= inode.size || buffer.capacity() < length) {
            throw FSArgumentsException("Incorrect arguments")
        }
        buffer.clear()

        val buf = Constants.zeroBlock()
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
        if (readCount != length) throw FSIOException("Incorrect length was read")
        buffer.flip()
        return length
    }

    fun write(inode: INode, buffer: ByteBuffer) = write(inode, buffer, 0, buffer.limit())
    fun append(inode: INode, buffer: ByteBuffer) = write(inode, buffer, inode.size, buffer.limit())

    fun write(inode: INode, buffer: ByteBuffer, start: Int, length: Int): Int {
        if (length == 0) {
            buffer.clear()
            return 0
        }
        val end = start + length - 1
        if (start < 0 || end >= Constants.INODE_TOTAL_LINKS_COUNT * Constants.BLOCK_SIZE || buffer.capacity() < length) {
            throw FSArgumentsException("Incorrect arguments")
        }
        buffer.rewind()

        val buf = Constants.zeroBlock()
        val startBlockId = start / Constants.BLOCK_SIZE
        val startBlockPosition = start.mod(Constants.BLOCK_SIZE)
        val endBlockId = end / Constants.BLOCK_SIZE
        val endBlockPosition = end.mod(Constants.BLOCK_SIZE)
        // allocate needed blocks
        var inodeUpdateNeeded = false
        for (i in startBlockId..endBlockId) {
            if (i >= Constants.LINKS_IN_INODE && inode.indirect == 0) {
                if (freeDataBlocks.size == 0) throw FSIOException("Not enough data blocks")
                inode.indirect = freeDataBlocks.removeFirst()
                disk.write(inode.indirect, buf) // TODO : do this only when this block was not allocated
                inodeUpdateNeeded = true
            }

            if (inode.links[i] == 0) {
                if (freeDataBlocks.size == 0) throw FSIOException("Not enough data blocks")
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
            if (buf.position() != endBlockPosition + 1) {
                throw FSIOException("IO when writing to file")
            }
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
        if (writeCount != length) throw FSIOException("Incorrect length was written")
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
        if (offset < 0 || offset >= inode.size && offset != 0) throw FSArgumentsException("Incorrect arguments")
        val buf = Constants.zeroBlock()
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
    fun path2fsPath(path: String) = "${Constants.SEPARATOR}${path.replace(File.separator, Constants.SEPARATOR)}"

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
        val inodeBlocksBuf = ByteBuffer.allocate(sb.inodeBlocks * Constants.BLOCK_SIZE)
        val indirectBuf = Constants.zeroBlock()
        val busyBlocks = mutableSetOf<Int>()
        disk.channelRead(inodeBlocksBuf, Constants.SUPERBLOCK_SIZE.toLong())
        for (i in 0 until sb.inodeBlocks) {
            buf.clear()
            buf.put(inodeBlocksBuf.array(), i * Constants.BLOCK_SIZE, Constants.BLOCK_SIZE)
            for (j in 0 until Constants.INODES_PER_BLOCK) {
                val number = i * Constants.INODES_PER_BLOCK + j
                if (INode.isValid(number, buf)) {
                    val inode = INode.read(number, buf)
                    if (inode.indirect != 0) {
                        disk.read(inode.indirect, indirectBuf)
                        inode.readIndirect(indirectBuf)
                        busyBlocks.add(inode.indirect)
                    }
                    for (k in 0..Constants.INODE_TOTAL_LINKS_COUNT) {
                        if (inode.links[k] != 0) {
                            busyBlocks.add(inode.links[k])
                        } else {
                            break
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

