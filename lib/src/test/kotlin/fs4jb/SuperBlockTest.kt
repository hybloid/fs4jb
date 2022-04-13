package fs4jb

import org.junit.Test
import java.nio.file.Paths
import kotlin.test.assertEquals

class SuperBlockTest {
    @Test
    fun writeAndRead() {
        val disk = Disk(Paths.get("build", "superblock.jb"), 1)
        disk.open(true)
        val sb = SuperBlock(Constants.MAGIC, 3, 2, 1)
        sb.write(disk)
        disk.close()
        disk.open()
        val sb2 = SuperBlock.read(disk)
        assertEquals(sb, sb2)
    }
}