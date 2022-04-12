package fs4jb

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Paths
import kotlin.test.assertEquals

class DiskTest {
    @Test
    fun writeAndReadBlocks() {
        val blocks = 10
        val disk = Disk(Paths.get("build", "out", "test.jb"), blocks)

        disk.open(true)
        val buf = Constants.zeroBlock()
        buf.put("HelloWorld".toByteArray())
        for (i in 0 until blocks) {
            assertEquals(disk.write(i, buf), Constants.BLOCK_SIZE)
        }
        disk.close()
        assertEquals(Metrics.reads, 0)
        assertEquals(Metrics.writes, blocks)
        buf.rewind()
        val readBuf = ByteBuffer.allocate(Constants.BLOCK_SIZE)
        disk.open()
        for (i in 0 until blocks) {
            assertEquals(disk.read(i, readBuf), Constants.BLOCK_SIZE)
            assertEquals(buf, readBuf)
        }
        disk.close()
        assertEquals(Metrics.reads, blocks)
        assertEquals(Metrics.writes, 0)
    }
}