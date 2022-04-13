package fs4jb

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Paths
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class FileSystemRWTest {
    private fun prepareFs(name: String, blocks: Int = 10): FileSystem {
        val disk = Disk(Paths.get("build", "out", "$name.jb"), blocks)
        val fs = FileSystem(disk)
        fs.format()
        fs.mount()
        return fs
    }

    @Test
    fun formatFs() {
        val blocks = 10
        val fs = prepareFs("formatFs", blocks)
        val blockCount = ceil(blocks / Constants.INODE_PROC).toInt()
        assertEquals(fs.sb.blocks, blocks)
        assertEquals(fs.sb.inodeBlocks, blockCount)
        assertEquals(fs.sb.inodes, blockCount * Constants.INODES_PER_BLOCK)
        // -1 due to root folder
        assertEquals(fs.fstat().freeInodes, fs.sb.inodes - 1)
        assertEquals(fs.fstat().freeDataBlocks, fs.sb.blocks - fs.sb.inodeBlocks - 1)
        fs.umount()
    }

    @Test
    fun readWriteAtPageStart() {
        val fs = prepareFs("readWriteStartPage")
        val inode = fs.createINode()
        val gaudamus = ByteBuffer.wrap("Gaudeamus igitur, Iuvenes dum sumus".toByteArray())
        fs.write(inode, gaudamus, 0, gaudamus.limit())
        fs.umount()
        gaudamus.rewind()
        fs.mount()
        val inode2 = fs.retrieveINode(inode.number)
        assertEquals(inode, inode2)
        val inputBuffer = ByteBuffer.allocate(inode2.size)
        fs.read(inode2, inputBuffer, 0, inode2.size)
        inputBuffer.rewind()
        assertEquals(gaudamus, inputBuffer)
    }

    @Test
    fun readWriteAtPageName() {
        val fs = prepareFs("readWriteEndPages")
        val inode = fs.createINode()
        val gaudamus = ByteBuffer.wrap("Gaudeamus igitur, Iuvenes dum sumus".toByteArray())
        val size = gaudamus.limit()
        fs.write(inode, gaudamus, Constants.BLOCK_SIZE - size, size)
        fs.umount()
        gaudamus.rewind()
        fs.mount()
        val inode2 = fs.retrieveINode(inode.number)
        assertEquals(inode, inode2)
        val inputBuffer = ByteBuffer.allocate(size)
        fs.read(inode2, inputBuffer, Constants.BLOCK_SIZE - size, size)
        inputBuffer.rewind()
        assertEquals(gaudamus, inputBuffer)
    }

    @Test
    fun readWriteTwoPages() {
        val fs = prepareFs("readWriteTwoPages")
        val inode = fs.createINode()
        val gaudamus = ByteBuffer.wrap("Gaudeamus igitur, Iuvenes dum sumus".toByteArray())
        val size = gaudamus.limit()
        fs.write(inode, gaudamus, Constants.BLOCK_SIZE - 10, size)
        fs.umount()
        gaudamus.rewind()
        fs.mount()
        val inode2 = fs.retrieveINode(inode.number)
        assertEquals(inode, inode2)
        val inputBuffer = ByteBuffer.allocate(size)
        fs.read(inode2, inputBuffer, Constants.BLOCK_SIZE - 10, size)
        inputBuffer.rewind()
        assertEquals(gaudamus, inputBuffer)
    }

    @Test
    fun readWriteWithIndirectPages() {
        val fs = prepareFs("readWriteHugeNumber")
        val inode = fs.createINode()
        val array = ByteArray(Constants.BLOCK_SIZE * 6) { 123 }
        val wrappedArray = ByteBuffer.wrap(array)
        val size = wrappedArray.limit()
        fs.write(inode, wrappedArray, Constants.BLOCK_SIZE - 10, size)
        fs.umount()
        wrappedArray.rewind()
        fs.mount()
        val inode2 = fs.retrieveINode(inode.number)
        assertEquals(inode, inode2)
        val inputBuffer = ByteBuffer.allocate(size)
        fs.read(inode2, inputBuffer, Constants.BLOCK_SIZE - 10, size)
        inputBuffer.rewind()
        assertEquals(wrappedArray, inputBuffer)
    }

    @Test
    fun truncateToEmpty() {
        val fs = prepareFs("truncateFull")
        val inodeCount = fs.fstat().freeInodes
        val dataBlockCount = fs.fstat().freeDataBlocks
        val inode = fs.createINode()
        val array = ByteArray(Constants.BLOCK_SIZE * 6) { 123 }
        val wrappedArray = ByteBuffer.wrap(array)
        val size = wrappedArray.limit()
        fs.write(inode, wrappedArray, Constants.BLOCK_SIZE - 10, size)
        fs.umount()
        wrappedArray.rewind()
        fs.mount()
        val inode2 = fs.retrieveINode(inode.number)
        fs.truncate(inode2, 0)
        val inode3 = fs.retrieveINode(inode.number)
        assertEquals(inode2, inode3)
        assertEquals(inode2.size, 0)
        assertEquals(inode2.indirect, 0)
        assertTrue(inode2.links.all { it == 0 })
        assertEquals(fs.fstat().freeInodes, inodeCount - 1)
        assertEquals(fs.fstat().freeDataBlocks, dataBlockCount)
    }

    @Test
    fun truncateOnePage() {
        val fs = prepareFs("truncateOnePage")
        val inode = fs.createINode()
        val array = ByteArray(Constants.BLOCK_SIZE * 6) { 123 }
        val wrappedArray = ByteBuffer.wrap(array)
        val size = wrappedArray.limit()
        fs.write(inode, wrappedArray, Constants.BLOCK_SIZE - 10, size)
        val inodeCount = fs.fstat().freeInodes
        val dataBlockCount = fs.fstat().freeDataBlocks
        fs.umount()
        wrappedArray.rewind()
        fs.mount()
        val inode2 = fs.retrieveINode(inode.number)
        val newSize = inode2.size - Constants.BLOCK_SIZE
        fs.truncate(inode2, newSize)
        val inode3 = fs.retrieveINode(inode.number)
        assertEquals(inode2, inode3)
        assertEquals(inode2.size, newSize)
        assertTrue(inode2.indirect != 0)
        assertEquals(fs.fstat().freeInodes, inodeCount)
        assertEquals(fs.fstat().freeDataBlocks, dataBlockCount + 1)
    }

    @Test
    fun truncateFewBytes() {
        val fs = prepareFs("truncateFew")
        val inode = fs.createINode()
        val array = ByteArray(5)
        for (i in 0 until 5) {
            array[i] = (i + 1).toByte()
        }
        val wrappedArray = ByteBuffer.wrap(array)
        val size = wrappedArray.limit()
        fs.write(inode, wrappedArray, 0, size)
        val inodeCount = fs.fstat().freeInodes
        val dataBlockCount = fs.fstat().freeDataBlocks
        fs.umount()
        fs.mount()
        val inode2 = fs.retrieveINode(inode.number)
        val newSize = 3
        fs.truncate(inode2, newSize)
        fs.umount()
        fs.mount()
        val inode3 = fs.retrieveINode(inode.number)
        assertEquals(inode2, inode3)
        assertEquals(inode2.size, newSize)
        val readBuffer = ByteBuffer.allocate(3)
        fs.read(inode3, readBuffer, 0, 3)
        val expectedArray = ByteArray(3)
        for (i in 0 until 3) {
            expectedArray[i] = (i + 1).toByte()
        }
        val wrappedExpectedArray = ByteBuffer.wrap(expectedArray)
        assertEquals(readBuffer, wrappedExpectedArray)
        assertEquals(fs.fstat().freeInodes, inodeCount)
        assertEquals(fs.fstat().freeDataBlocks, dataBlockCount)
    }
}