package fs4jb

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Paths
import kotlin.math.ceil
import kotlin.test.assertEquals


class FileSystemTest {
    @Test
    fun formatFs() {
        val blocks = 10
        val disk = Disk(Paths.get("build", "out", "formatfs.jb"), blocks)
        val fs = FileSystem(disk)
        fs.format()

        fs.debug()

        fs.mount()
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
        val blocks = 10
        val disk = Disk(Paths.get("build", "out", "readWriteStartPage.jb"), blocks)
        val fs = FileSystem(disk)
        fs.format()
        fs.mount()
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
        val blocks = 10
        val disk = Disk(Paths.get("build", "out", "readWriteEndPages.jb"), blocks)
        val fs = FileSystem(disk)
        fs.format()
        fs.mount()
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
        val blocks = 10
        val disk = Disk(Paths.get("build", "out", "readWriteTwoPages.jb"), blocks)
        val fs = FileSystem(disk)
        fs.format()
        fs.mount()
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
}