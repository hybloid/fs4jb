package fs4jb

import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.collections.ArrayDeque
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.test.assertTrue

class FileSystemFunctionalTest {
    @Test
    fun functionalTestJB() {
        val disk = Disk(Paths.get("build", "out", "jb.jb"), 1_000)
        val fs = FileSystem(disk)
        fs.format()
        fs.mount()

        val rootPath = File(".").toPath().toAbsolutePath()
        val listOfFiles = copyFilesToFS(rootPath, fs, fs.getRootFolder())
        listOfFiles.shuffle()
        for (i in 0..(listOfFiles.size * 70 / 100)) {
            val osFile = listOfFiles[i]
            val osFolder = if (!osFile.contains(File.separator)) {
                ""
            } else {
                osFile.substring(0, osFile.lastIndexOf(File.separator))
            }
            val fsFile = fs.path2fsPath(osFile)
            val fsFolder = fs.path2fsPath(osFolder)
            fs.delete(fs.open(fsFile), fs.open(fsFolder))
        }
        val newPrefix = "newdir4test"
        val newRoot = fs.mkdir(newPrefix, fs.getRootFolder())
        val newListOfFiles = copyFilesToFS(rootPath, fs, newRoot)
        fs.remount()
        for (i in newListOfFiles.indices) {
            val osFile = newListOfFiles[i]
            val osPath = Paths.get(rootPath.toString(), osFile).toAbsolutePath()
            val fsFile = "${Constants.SEPARATOR}$newPrefix${fs.path2fsPath(osFile)}"
            val fsBuf = fs.readToEnd(fs.open(fsFile))
            val osBuf = osPath.toFile().readBytes()
            assertTrue { fsBuf.contentEquals(osBuf) }
        }
        fs.umount()
    }

    private fun copyFilesToFS(
        rootPath: Path,
        fs: FileSystem,
        rootFolder: INode
    ): MutableList<String> {
        val list = mutableListOf<String>()
        val stack = ArrayDeque<PathINodePair>()
        Files.walk(rootPath).filter { !it.name.endsWith(".jb") }.forEach {
            val absolutePath = it.toAbsolutePath()
            if (it.isDirectory()) {
                if (!stack.isEmpty() && !absolutePath.startsWith(stack.first().path)) {
                    rewindStack(stack, absolutePath)
                }
                val folder = if (it.name != ".") {
                    fs.mkdir(it.name, stack.first().folder)
                } else {
                    rootFolder
                }
                stack.addFirst(PathINodePair(absolutePath, folder))
            } else {
                if (!absolutePath.startsWith(stack.first().path)) {
                    rewindStack(stack, absolutePath)
                }
                val buffer = ByteBuffer.wrap(absolutePath.toFile().readBytes())
                val node = fs.create(it.name, stack.first().folder)
                fs.write(node, buffer)
                list.add(rootPath.relativize(absolutePath).toString())
            }
        }
        return list
    }

    private fun rewindStack(
        stack: ArrayDeque<PathINodePair>,
        absolutePath: Path
    ) {
        do {
            stack.removeFirst()
        } while (!absolutePath.startsWith(stack.first().path))
    }

    private data class PathINodePair(val path: Path, val folder: INode)
}