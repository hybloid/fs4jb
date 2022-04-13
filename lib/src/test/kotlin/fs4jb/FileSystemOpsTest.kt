package fs4jb

import org.junit.Test
import java.nio.ByteBuffer
import java.nio.file.Paths
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class FileSystemOpsTest {
    private fun prepareFs(name: String, blocks: Int = 10): FileSystem {
        val disk = Disk(Paths.get("build", "out", "$name.jb"), blocks)
        val fs = FileSystem(disk)
        fs.format()
        fs.mount()
        return fs
    }

    @Test
    fun appendAndWriteToFile() {
        val fs = prepareFs("appendAndWriteToFile")
        val file = fs.create("file", fs.getRootFolder())
        fs.append(file, ByteBuffer.wrap("Hello ".toByteArray()))
        fs.append(file, ByteBuffer.wrap("world!".toByteArray()))
        assertEquals(String(fs.readToEnd(file)), "Hello world!")
        fs.write(file, ByteBuffer.wrap("!".toByteArray()), 5, 1)
        assertEquals(String(fs.readToEnd(file)), "Hello!world!")
        fs.truncate(file, 5)
        assertEquals(String(fs.readToEnd(file)), "Hello")
        fs.umount()
    }

    @Test
    fun readFromFile() {
        val fs = prepareFs("readFromFile")
        val file = fs.create("file", fs.getRootFolder())
        fs.write(file, ByteBuffer.wrap("Hello world!".toByteArray()))
        assertEquals(String(fs.readToEnd(file)), "Hello world!")
        var buf = ByteBuffer.allocate(12)
        fs.read(file, buf)
        assertEquals(String(buf.array()), "Hello world!")
        buf = ByteBuffer.allocate(6)
        fs.read(file, buf, 6, 6)
        assertEquals(String(buf.array()), "world!")
        fs.umount()
    }

    @Test
    fun readWriteZero() {
        val fs = prepareFs("readWriteZero")
        val file = fs.create("file", fs.getRootFolder())
        val zeroBuf = ByteBuffer.allocate(0)
        fs.write(file, zeroBuf) // does not fail
        fs.write(file, ByteBuffer.wrap("Hello world!".toByteArray()))
        zeroBuf.rewind()
        fs.write(file, zeroBuf) // does not fail
        fs.read(file, zeroBuf) // does not fail
        val buf = ByteBuffer.allocate(12)
        fs.read(file, buf)
        fs.read(file, zeroBuf) // does not fail
        assertEquals(String(buf.array()), "Hello world!")
        fs.umount()
    }

    @Test
    fun openRoot() {
        val fs = prepareFs("openRoot")
        assertEquals(fs.open(Constants.SEPARATOR), fs.getRootFolder())
        fs.umount()
    }

    @Test
    fun createAndOpen() {
        val fs = prepareFs("createAndOpen")
        val dir = fs.mkdir("one", fs.getRootFolder())
        val file1 = fs.create("two", dir)
        fs.write(file1, ByteBuffer.wrap("three".toByteArray()))
        fs.remount()
        val file2 = fs.open("/one/two")
        assertEquals(String(fs.readToEnd(file2)), "three")
        fs.umount()
    }

    @Test
    fun createAndDelete() {
        val fs = prepareFs("createAndDelete")
        val dir = fs.mkdir("one", fs.getRootFolder())
        val file = fs.create("two", dir)
        assertNotNull(fs.open("/one/two"))
        fs.delete(file, dir)
        assertEquals(file.valid, false)
        fs.remount()
        assertFailsWith<FSIOException> { fs.open("/one/two") }
        fs.umount()
    }

    @Test
    fun createAndMove() {
        val fs = prepareFs("createAndMove")
        val root = fs.getRootFolder()
        val dir = fs.mkdir("one", root)
        val file = fs.create("two", dir)
        assertNotNull(fs.open("/one/two"))
        assertFailsWith<FSIOException> { fs.open("/two") }
        fs.move(file, dir, fs.getRootFolder())
        assertNotNull(fs.open("/two"))
        fs.remount()
        assertFailsWith<FSIOException> { fs.open("/one/two") }
        assertNotNull(fs.open("/two"))
        fs.umount()
    }

    @Test
    fun createAndRename() {
        val fs = prepareFs("createAndRename")
        val root = fs.getRootFolder()
        val dir = fs.mkdir("one", root)
        val file = fs.create("two", dir)
        assertNotNull(fs.open("/one/two"))
        fs.rename("three", file, dir)
        fs.remount()
        assertNotNull(fs.open("/one/three"))
        assertFailsWith<FSIOException> { fs.open("/one/two") }
        fs.umount()
    }

    @Test
    fun funWithPathTraversal() {
        val fs = prepareFs("funWithPathTraversal")
        val dir = fs.mkdir("one", fs.getRootFolder())
        fs.mkdir("two", dir)
        val file = fs.create("three", dir)

        val try1 = fs.open("/one/three")
        val try2 = fs.open("/./one/three")
        val try3 = fs.open("/./one/./three")
        val try4 = fs.open("/./one/../one/three")
        val try5 = fs.open("/./one/two/./../three")
        val try6 = fs.open("/../one/two/./../three")
        assertEquals(file, try1)
        assertEquals(file, try2)
        assertEquals(file, try3)
        assertEquals(file, try4)
        assertEquals(file, try5)
        assertEquals(file, try6)
        fs.umount()
    }

    @Test
    fun deleteFromTheMiddle() {
        val fs = prepareFs("deleteFromTheMiddle")
        val root = fs.getRootFolder()
        val dir1 = fs.mkdir("one", root)
        fs.mkdir("two", root)
        fs.mkdir("three", root)
        fs.delete(dir1, root)
        assertEquals(fs.ls(root).map { it.first }, listOf(".", "..", "two", "three"))
        fs.umount()
    }

    @Test
    fun deleteFromTheEnd() {
        val fs = prepareFs("deleteFromTheEnd")
        val root = fs.getRootFolder()
        fs.mkdir("one", root)
        fs.mkdir("two", root)
        val dir3 = fs.mkdir("three", root)
        fs.delete(dir3, root)
        assertEquals(fs.ls(root).map { it.first }, listOf(".", "..", "one", "two"))
        fs.umount()
    }
}