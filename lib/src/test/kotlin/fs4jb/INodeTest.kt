package fs4jb

import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class INodeTest {
    @Test
    fun writeAndReadINodes() {
        val disk = Disk(Paths.get("build", "out", "inode.jb"), 2)
        val i1 = INode(0, true, false, 1, Array(Constants.INODE_TOTAL_LINKS_COUNT) { 123 }, 1)
        val buf = Constants.ZERO_DATA_BLOCK
        disk.open(true)
        i1.write(buf)
        disk.write(0, buf)
        i1.writeIndirect(buf)
        disk.write(1, buf)
        disk.close()

        disk.open()
        disk.read(0, buf)
        val i2 = INode.read(0, buf)
        disk.read(1, buf)
        i2.readIndirect(buf)
        disk.close()
        assertEquals(i1, i2)
    }

    @Test
    fun writeAndReadInvalidInode() {
        val disk = Disk(Paths.get("build", "out", "inode.jb"), 2)
        val i1 = INode(0, false, true, 1, Array(Constants.INODE_TOTAL_LINKS_COUNT) { 123 }, 1)
        val buf = Constants.ZERO_DATA_BLOCK
        disk.open(true)
        i1.write(buf)
        disk.write(0, buf)
        i1.writeIndirect(buf)
        disk.write(1, buf)
        disk.close()

        disk.open()
        disk.read(0, buf)
        val i2 = INode.read(0, buf)
        // disk.read(1, buf)
        // i2.readIndirect(buf) read indirect will fail the assertion
        disk.close()
        assertNotEquals(i1, i2)
        assertEquals(INode(0, false, false, 0, Array(Constants.INODE_TOTAL_LINKS_COUNT) { 0 }, 0), i2)
    }
}