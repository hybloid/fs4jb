package fs4jb

import org.junit.Test
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
        fs.umount()
    }
}