package fs4jb

import mu.KotlinLogging
import java.io.File
import java.nio.ByteBuffer
import java.util.BitSet
import kotlin.time.ExperimentalTime
import kotlin.time.measureTimedValue


class FileSystem(private val disk: Disk) {
    lateinit var sb: SuperBlock
    lateinit var freeInodes: BitSet
    lateinit var freeDataBlocks: BitSet
    private val logger = KotlinLogging.logger {}

    /**
     * FS Main routines
     */

    /**
     * Formats the drive. If it exists, the content will be overwritten
     */
    fun format() {
        disk.open(true)
        initSb()
        sb.write(disk)
        // reserve data for inodes
        for (i in 0 until sb.inodeBlocks) {
            disk.write(i, Constants.zeroBlock())
        }
        for (i in 0 until sb.inodes) {
            freeInodes.set(i)
        }
        for (i in sb.inodeBlocks until sb.blocks) {
            freeDataBlocks.set(i)
        }
        mkdir(Constants.SEPARATOR, null)
        logger.info("Disk formatted with ${disk.nBlocks} blocks")
        umount()
    }

    /**
     * Mounts the drive and runs the routine to locate free blocks/inodes
     */
    fun mount() {
        disk.open()
        initSb()
        if (sb.magicNumber != Constants.MAGIC) throw FSArgumentsException("Supplied disk image has incorrect structure")
        logger.info("Disk mounted")
        fsck()
        logger.info("FSCK completed")
    }

    /**
     * Umounts the drive and dumps the content to disk
     */
    fun umount() {
        disk.close()
        logger.info("Disk unmounted")
        freeInodes.clear()
        freeDataBlocks.clear()
    }

    /**
     * Umounts and mounts the drive back
     */
    fun remount() {
        umount()
        mount()
    }

    /**
     * FS i-node routines
     */

    /**
     * Allocate new inode with next free number
     * @return created inode object
     */
    fun createINode(): INode {
        val freeInodeNumber = getFreeInodeNum()
        return INode(freeInodeNumber, true, false, 0, Constants.LINKS_ARRAY.copyOf(), 0)
    }

    /**
     * Retrieve inode by number
     * @return inode object with the given id
     */
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

    private fun initSb() {
        sb = SuperBlock(disk.nBlocks)
        freeInodes = BitSet(sb.inodes)
        freeDataBlocks = BitSet(sb.blocks)
    }

    private fun getFreeInodeNum(): Int {
        val number = freeInodes.nextSetBit(0)
        freeInodes.clear(number)
        return number
    }

    private fun getFreeDataBlockNum(): Int {
        val number = freeDataBlocks.nextSetBit(sb.inodeBlocks)
        freeDataBlocks.clear(number)
        return number
    }

    /**
     * FS file/directory ops
     */

    /**
     * Get inode of the root directory
     * @return inode object of the "/" directory
     */
    fun getRootDir() = retrieveINode(0)

    /**
     * Get inode of file/directory by its path
     * @param path target path to the file/directory
     * @return inode object of the file/directory
     */
    fun open(path: String) = locateEntryAndParent(path).entry

    /**
     * Create a file in a given directory
     * @param path target path to the file
     * @return inode object of the created file
     */
    fun create(path: String): INode {
        val pathParts = parsePath(path)
        if (pathParts.directory == Constants.SEPARATOR) {
            return create(path.substring(1), getRootDir())
        }
        return create(pathParts.name, open(pathParts.directory))
    }

    /**
     * Create a file in a given directory
     * @param name file name
     * @param dir inode object of the target directory
     * @return inode object of the created file
     */
    fun create(name: String, dir: INode): INode {
        val inode = createINode()
        writeInodeToDisk(inode, Constants.zeroBlock())
        addToDir(name, inode, dir)
        return inode
    }

    /**
     * Delete a file from a given directory
     * @param path target path to the file/directory
     */
    fun delete(path: String) {
        val entryAndParent = locateEntryAndParent(path)
        delete(entryAndParent.entry, entryAndParent.parent)
    }

    /**
     * Delete a file from a given directory
     * @param entry inode object for the file/directory to be removed
     * @param dir inode object for the directory containing entry
     */
    fun delete(entry: INode, dir: INode) {
        if (entry.isDir && ls(entry).any { it.first != "." && it.first != ".." }) {
            throw FSArgumentsException("Dir is not empty")
        }
        deleteFromDir(entry, dir)
        entry.valid = false // state will be written during truncate
        truncate(entry, 0)
        freeInodes.set(entry.number)
    }

    /**
     * Move file/directory from one to another
     * @param path current path to the file/directory
     * @param dstDir new target directory
     */
    fun move(path: String, dstDir: String) {
        val entryAndParent = locateEntryAndParent(path)
        val dstDirInode = open(dstDir)
        move(entryAndParent.entry, entryAndParent.parent, dstDirInode)
    }

    /**
     * Move file/directory from one to another
     * @param entry inode object of the file/directory to be moved
     * @param srcDir inode object of the source directory
     * @param dstDir inode object of the destination directory
     */
    fun move(entry: INode, srcDir: INode, dstDir: INode) {
        if (!srcDir.isDir || !dstDir.isDir) throw FSArgumentsException("Incorrect arguments")
        val name = deleteFromDir(entry, srcDir)
        addToDir(name, entry, dstDir)
    }

    /**
     * Rename file/directory in the given directory
     * @param name new file/directory name
     * @param path full path to the renamed file/directory
     */
    fun rename(name: String, path: String) {
        if (name.contains(Constants.SEPARATOR)) throw FSArgumentsException("Incorrect arguments")
        val entryAndParent = locateEntryAndParent(path)
        rename(name, entryAndParent.entry, entryAndParent.parent)
    }

    /**
     * Rename file/directory in the given directory
     * @param name new file/directory name
     * @param entry inode object of the file/directory to be renamed
     * @param parentDir inode object of the parent directory
     */
    fun rename(name: String, entry: INode, parentDir: INode) {
        if (!parentDir.isDir) throw FSArgumentsException("Incorrect arguments")
        // FIXME: we do not check . and .. here
        val dentries = parentDir.size / Constants.DENTRY_SIZE
        if (parentDir.size.mod(Constants.DENTRY_SIZE) != 0 || dentries < 2) throw FSBrokenStateException("FS is corrupted")
        val targetBuf = ByteBuffer.allocate(parentDir.size) // Expensive
        read(parentDir, targetBuf, 0, parentDir.size)
        for (i in 0 until dentries) {
            val num = targetBuf.getInt(i * Constants.DENTRY_SIZE + Constants.FILENAME_SIZE)
            if (num == entry.number) {
                targetBuf.position(i * Constants.DENTRY_SIZE)
                write(parentDir, wrapNameForDentry(name), i * Constants.DENTRY_SIZE, Constants.FILENAME_SIZE)
                return
            }
        }
        throw FSIOException("File not found")
    }

    /**
     * Create new directory
     * @param path full path to the new directory. Parent directory should exist
     * @return inode object of the created directory
     */
    fun mkdir(path: String): INode {
        val pathParts = parsePath(path)
        if (pathParts.directory == Constants.SEPARATOR) {
            return mkdir(pathParts.name, getRootDir())
        }
        return mkdir(pathParts.name, open(pathParts.directory))
    }

    /**
     * Create new directory
     * @param name short name of the new directory
     * @param parentDir inode object of the parent directory. Null as a special case for creating /
     * @return inode object of the created directory
     */
    fun mkdir(name: String, parentDir: INode?): INode {
        val inode = createINode()
        inode.isDir = true
        val buffer = ByteBuffer.allocate(Constants.DENTRY_SIZE * 2)
        buffer.put(wrapNameForDentry("."))
        buffer.putInt(inode.number)
        buffer.put(wrapNameForDentry(".."))
        if (parentDir == null) {
            buffer.putInt(inode.number)
        } else {
            buffer.putInt(parentDir.number)
        }
        write(inode, buffer, 0, buffer.limit())
        if (parentDir != null) {
            addToDir(name, inode, parentDir)
        }
        return inode
    }

    /**
     * List files/dictionaries in a given directory
     * @param path path to the target directory
     * @return list of pairs - file/directory name and inode id
     */
    fun ls(path: String): List<Pair<String, Int>> = ls(open(path))

    /**
     * List files/dictionaries in a given directory
     * @param dir inode object of the target directory
     * @return list of pairs - file/directory name and inode id
     */
    fun ls(dir: INode): List<Pair<String, Int>> {
        val dentries = dir.size / Constants.DENTRY_SIZE
        if (dir.size.mod(Constants.DENTRY_SIZE) != 0 || dentries < 2) throw FSBrokenStateException("FS is corrupted")
        val list = mutableListOf<Pair<String, Int>>()
        val targetBuf = ByteBuffer.allocate(dir.size) // Expensive
        read(dir, targetBuf, 0, dir.size)
        val dentryName = ByteArray(Constants.FILENAME_SIZE)
        for (i in 0 until dentries) {
            targetBuf.get(dentryName)
            val name = String(trim(dentryName), Constants.CHARSET)
            val num = targetBuf.int
            list.add(Pair(name, num))
        }
        return list
    }

    private fun addToDir(name: String, entry: INode, dir: INode) {
        // FIXME: we do not check name duplicates here
        // FIXME: we do not check . and .. here
        if (name.contains(Constants.SEPARATOR) || name.length > Constants.FILENAME_SIZE) {
            throw FSArgumentsException("Incorrect arguments")
        }
        val buffer = ByteBuffer.allocate(Constants.DENTRY_SIZE)
        buffer.put(wrapNameForDentry(name))
        buffer.putInt(entry.number)
        write(dir, buffer, dir.size, buffer.limit())
    }

    private fun deleteFromDir(entry: INode, dir: INode): String {
        if (!dir.isDir) throw FSArgumentsException("Incorrect arguments")
        // FIXME: we do not check . and .. here
        val dentries = dir.size / Constants.DENTRY_SIZE
        if (dir.size.mod(Constants.DENTRY_SIZE) != 0 || dentries < 2) throw FSBrokenStateException("FS is corrupted")
        val targetBuf = ByteBuffer.allocate(dir.size) // Expensive
        read(dir, targetBuf, 0, dir.size)
        for (i in 0 until dentries) {
            val num = targetBuf.getInt(i * Constants.DENTRY_SIZE + Constants.FILENAME_SIZE)
            if (num == entry.number) {
                val bufForName = ByteArray(Constants.FILENAME_SIZE)
                targetBuf.position(i * Constants.DENTRY_SIZE)
                targetBuf.get(bufForName)
                val name = String(trim(bufForName), Constants.CHARSET)
                if (i == dentries - 1) {
                    // corner case
                    truncate(dir, i * Constants.DENTRY_SIZE)
                } else {
                    val restBuf = ByteBuffer.allocate((dentries - i - 1) * Constants.DENTRY_SIZE)
                    targetBuf.position((i + 1) * Constants.DENTRY_SIZE)
                    restBuf.put(targetBuf)
                    truncate(dir, i * Constants.DENTRY_SIZE)
                    write(dir, restBuf, i * Constants.DENTRY_SIZE, restBuf.limit())
                }
                return name
            }
        }
        throw FSIOException("File not found")
    }

    private fun locate(name: String, dir: INode): INode {
        val dentries = dir.size / Constants.DENTRY_SIZE
        if (dir.size.mod(Constants.DENTRY_SIZE) != 0 || dentries < 2) throw FSBrokenStateException("FS is corrupted")
        val targetBuf = ByteBuffer.allocate(dir.size) // Expensive
        read(dir, targetBuf, 0, dir.size)
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

    private fun locateEntryAndParent(path: String): INodePair {
        if (path[0] != Constants.SEPARATOR[0]) throw FSArgumentsException("Incorrect arguments")
        var inode = getRootDir()
        var parent = inode
        if (path == Constants.SEPARATOR) return INodePair(inode, parent)
        val subPaths = path.split(Constants.SEPARATOR)
        for (i in 1 until subPaths.size) {
            parent = inode
            inode = locate(subPaths[i], inode)
        }
        return INodePair(inode, parent)
    }

    /**
     * FS low level read-write routines
     */
    /**
     * Read the file contents from the beginning to the end
     */
    fun readToEnd(inode: INode): ByteArray {
        val buffer = ByteBuffer.allocate(inode.size)
        read(inode, buffer)
        return buffer.array()
    }

    /**
     * Read the file contents from the beginning to max buffer size
     */
    fun read(inode: INode, buffer: ByteBuffer) = read(inode, buffer, 0, buffer.limit())

    /**
     * Read the file contents from the given offset limited by length
     */
    @OptIn(ExperimentalTime::class)
    fun read(inode: INode, buffer: ByteBuffer, start: Int, length: Int): Int {
        // corner case excluded from the estimation
        if (length == 0) {
            buffer.clear()
            return 0
        }

        val (value, elapsed) = measureTimedValue {
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
            length
        }
        Metrics.logReadSpeed(value, elapsed)
        return value
    }

    /**
     * Write to the file from the beginning to max buffer size
     */
    fun write(inode: INode, buffer: ByteBuffer) = write(inode, buffer, 0, buffer.limit())

    /**
     * Append to the end of the file
     */
    fun append(inode: INode, buffer: ByteBuffer) = write(inode, buffer, inode.size, buffer.limit())

    /**
     * Write to the file from the given offset limited by length
     */
    @OptIn(ExperimentalTime::class)
    fun write(inode: INode, buffer: ByteBuffer, start: Int, length: Int): Int {
        // corner case excluded from the estimation
        if (length == 0) {
            buffer.clear()
            return 0
        }

        val (value, elapsed) = measureTimedValue {
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
                    inode.indirect = getFreeDataBlockNum()
                    disk.write(inode.indirect, buf) // TODO : do this only when this block was not allocated
                    inodeUpdateNeeded = true
                }

                if (inode.links[i] == 0) {
                    inode.links[i] = getFreeDataBlockNum()
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
            length
        }
        Metrics.logWriteSpeed(value, elapsed)
        return value
    }

    /**
     * Truncate file contents
     */
    fun truncate(inode: INode, offset: Int) {
        if (offset < 0 || offset >= inode.size && offset != 0) throw FSArgumentsException("Incorrect arguments")
        val buf = Constants.zeroBlock()
        val startBlockId = offset / Constants.BLOCK_SIZE
        val lastBlockId = inode.size / Constants.BLOCK_SIZE
        for (i in lastBlockId downTo startBlockId + 1) {
            if (inode.links[i] != 0) {
                freeDataBlocks.set(inode.links[i])
                inode.links[i] = 0
            }
        }
        if (startBlockId < Constants.LINKS_IN_INODE && inode.indirect != 0) {
            freeDataBlocks.set(inode.indirect)
            inode.indirect = 0
        }
        if (offset == 0) {
            // special case, remove all blocks
            if (inode.links[0] != 0) {
                freeDataBlocks.set(inode.links[0])
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

    /**
     * Filesystem stat routines
     */
    fun fstat() = FileSystemStat(
        freeInodes.stream().count().toInt(),
        sb.inodes,
        freeDataBlocks.stream().count().toInt(),
        sb.blocks - sb.inodeBlocks
    )

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

    private fun parsePath(path: String): NameAndDir {
        if (path == Constants.SEPARATOR) {
            throw FSArgumentsException("Incorrect arguments")
        }

        // /dir1/dir2/
        val lastIndexOfSeparator = path.lastIndexOf(Constants.SEPARATOR)
        if (lastIndexOfSeparator == path.length - 1) {
            throw FSArgumentsException("Incorrect arguments")
        }

        val name = path.substring(lastIndexOfSeparator + 1)
        if (lastIndexOfSeparator == 0) {
            return NameAndDir(name, Constants.SEPARATOR)
        }
        val dir = path.substring(0, lastIndexOfSeparator)
        return NameAndDir(name, dir)
    }

    private fun fsck() {
        val buf = Constants.zeroBlock()
        val indirectBuf = Constants.zeroBlock()
        val busyBlocks = mutableSetOf<Int>()
        for (i in 0 until sb.inodeBlocks) {
            disk.read(i, buf)
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
                    freeInodes.set(number)
                }
            }
        }
        for (i in sb.inodeBlocks until sb.blocks) {
            if (!busyBlocks.contains(i)) {
                freeDataBlocks.set(i)
            }
        }
    }

    companion object {
        /**
         * Transform relative OS path to FS path
         */
        fun relPath2fsPath(path: String) = "${Constants.SEPARATOR}${path.replace(File.separator, Constants.SEPARATOR)}"
    }

    data class FileSystemStat(
        val freeInodes: Int,
        val totalINodes: Int,
        val freeDataBlocks: Int,
        val totalDataBlocks: Int
    )

    private data class INodePair(
        val entry: INode,
        val parent: INode
    )

    private data class NameAndDir(
        val name: String,
        val directory: String
    )
}

