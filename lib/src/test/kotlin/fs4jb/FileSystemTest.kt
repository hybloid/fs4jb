package fs4jb

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Paths
import kotlin.math.ceil
import kotlin.test.assertEquals
import kotlin.test.assertTrue


class FileSystemTest {
    private fun prepareFs(name : String, blocks : Int = 10) : FileSystem {
        val disk = Disk(Paths.get("build", "out", "$name.jb"), blocks)
        val fs = FileSystem(disk)
        fs.format()
        fs.debug()
        fs.mount()
        return fs
    }

    @Test
    fun formatFs() {
        val blocks = 10
        val fs = prepareFs("formatFs", blocks)
        val blockCount = ceil(blocks/Constants.INODE_PROC).toInt()
        assertEquals(fs.sb.blocks, blocks)
        assertEquals(fs.sb.inodeBlocks, blockCount)
        assertEquals(fs.sb.inodes, blockCount * Constants.INODES_PER_BLOCK)
        // FSCK
        // TODO : will fail once we initialize 0 inode
        assertEquals(fs.freeStat().first, fs.sb.inodes)
        assertEquals(fs.freeStat().second, fs.sb.blocks - fs.sb.inodeBlocks)
        fs.umount()
    }

    @Test
    fun readWriteStartPage() {
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
    fun readWriteEndPages() {
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
    fun readWriteHugeNumber() {
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
    fun truncateFull() {
        val fs = prepareFs("truncateFull")
        val inodeCount = fs.freeStat().first
        val dataBlockCount = fs.freeStat().second
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
        assertEquals(fs.freeStat().first, inodeCount - 1)
        assertEquals(fs.freeStat().second, dataBlockCount)
    }

    @Test
    fun truncateOnePage() {
        val fs = prepareFs("truncateOnePage")
        val inode = fs.createINode()
        val array = ByteArray(Constants.BLOCK_SIZE * 6) { 123 }
        val wrappedArray = ByteBuffer.wrap(array)
        val size = wrappedArray.limit()
        fs.write(inode, wrappedArray, Constants.BLOCK_SIZE - 10, size)
        val inodeCount = fs.freeStat().first
        val dataBlockCount = fs.freeStat().second
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
        assertEquals(fs.freeStat().first, inodeCount)
        assertEquals(fs.freeStat().second, dataBlockCount + 1)
    }

    @Test
    fun truncateFew() {
        val fs = prepareFs("truncateFew")
        val inode = fs.createINode()
        val array = ByteArray(5)
        for (i in 0 until 5) {
            array[i] = (i + 1).toByte()
        }
        val wrappedArray = ByteBuffer.wrap(array)
        val size = wrappedArray.limit()
        fs.write(inode, wrappedArray, 0, size)
        val inodeCount = fs.freeStat().first
        val dataBlockCount = fs.freeStat().second
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
        assertEquals(fs.freeStat().first, inodeCount)
        assertEquals(fs.freeStat().second, dataBlockCount)
    }
}