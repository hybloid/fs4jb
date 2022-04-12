package fs4jb

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class FileSystemHighLevelOpsTest {
    private fun prepareFs(name: String, blocks: Int = 10): FileSystem {
        val disk = Disk(Paths.get("build", "out", "$name.jb"), blocks)
        val fs = FileSystem(disk)
        fs.format()
        fs.mount()
        return fs
    }

    @Test
    fun listRoot() {
        val fs = prepareFs("listRoot")
        val result = fs.listDir(fs.retrieveINode(0))
        // println(result)
        assertEquals(result.toString(), "[(., 0), (.., 0)]")
    }

    @Test
    fun addFolderToRoot() {
        val fs = prepareFs("addFolderToRoot")
        val root = fs.getRootFolder()
        fs.createDir("1", root)
        fs.createDir("2", root)
        fs.createDir("3", root)
        fs.umount()
        fs.mount()
        val result = fs.listDir(fs.retrieveINode(0))
        // println(result)
        assertEquals(result.toString(), "[(., 0), (.., 0), (1, 1), (2, 2), (3, 3)]")
    }

    @Test
    fun createFileInRoot() {
        val fs = prepareFs("createFile")
        var root = fs.getRootFolder()
        val file = fs.createFile("README.txt", root)
        val buffer = ByteBuffer.wrap("Hello world!".toByteArray())
        fs.write(file, buffer, 0, buffer.limit())
        fs.umount()
        fs.mount()
        root = fs.getRootFolder()
        val foundFile = fs.findInDir("README.txt", root)
        assertNotNull(foundFile)
        val resultContentBuffer = ByteBuffer.allocate(foundFile.size)
        fs.read(foundFile, resultContentBuffer, 0, resultContentBuffer.limit())
        assertEquals(String(resultContentBuffer.array()), "Hello world!")
    }
}