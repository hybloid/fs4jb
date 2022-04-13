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
        logStats("Initial copy", fs.fstat())
        listOfFiles.shuffle()
        for (i in 0..(listOfFiles.size * 70 / 100)) {
            val osFile = listOfFiles[i]
            val osFolder = if (!osFile.contains(File.separator)) {
                ""
            } else {
                osFile.substring(0, osFile.lastIndexOf(File.separator))
            }
            val fsFile = FileSystem.relPath2fsPath(osFile)
            val fsFolder = FileSystem.relPath2fsPath(osFolder)
            fs.delete(fs.open(fsFile), fs.open(fsFolder))
        }
        logStats("Removing files", fs.fstat())
        val newPrefix = "newdir4test"
        val newRoot = fs.mkdir(newPrefix, fs.getRootFolder())
        val newListOfFiles = copyFilesToFS(rootPath, fs, newRoot)
        logStats("Reinsert files", fs.fstat())
        fs.umount()
        logStats("LRU to disk", fs.fstat())
        fs.mount()
        for (i in newListOfFiles.indices) {
            val osFile = newListOfFiles[i]
            val osPath = Paths.get(rootPath.toString(), osFile).toAbsolutePath()
            val fsFile = "${Constants.SEPARATOR}$newPrefix${FileSystem.relPath2fsPath(osFile)}"
            val fsBuf = fs.readToEnd(fs.open(fsFile))
            val osBuf = osPath.toFile().readBytes()
            assertTrue { fsBuf.contentEquals(osBuf) }
        }
        logStats("Verify files", fs.fstat())
        fs.umount()
    }

    private fun logStats(stage: String, stats: FileSystem.FileSystemStat) {
        println("=".repeat(20))
        println("Stage      : $stage")
        println("Write speed: ${Metrics.writeSpeed()} kb/sec")
        println("Read speed : ${Metrics.readSpeed()} kb/sec")
        println("Raw writes : ${Metrics.lowLevelWrites}")
        println("Raw reads  : ${Metrics.lowLevelReads}")
        println("Used inodes: ${stats.totalINodes - stats.freeInodes}(${stats.totalINodes})")
        println("Used blocks: ${stats.totalDataBlocks - stats.freeDataBlocks}(${stats.totalDataBlocks})")
        println("Used size  : ${(stats.totalDataBlocks - stats.freeDataBlocks) * Constants.BLOCK_SIZE / 1024} kb")
        println("Free size  : ${(stats.freeDataBlocks) * Constants.BLOCK_SIZE / 1024} kb")
        println("=".repeat(20))
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