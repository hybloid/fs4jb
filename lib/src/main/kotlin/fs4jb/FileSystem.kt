package fs4jb

import mu.KotlinLogging

class FileSystem(val disk: Disk) {
    lateinit var sb: SuperBlock
    private val logger = KotlinLogging.logger {}

    fun debug() {
        disk.open()
        val block = SuperBlock.read(disk)
        disk.close()
        logger.info { block }
    }

    fun format() {
        val block = SuperBlock(disk.nBlocks)
        disk.open(true)
        block.write(disk)
        // reserve data for inodes
        for (i in 0 until block.inodeBlocks) {
            disk.write(i, Constants.ZERO_DATA_BLOCK)
        }
        disk.close()
        logger.info("Disk formatted with ${disk.nBlocks} blocks")
    }

    fun mount() {
        disk.open()
        sb = SuperBlock.read(disk)
        assert(sb.magicNumber == Constants.MAGIC) // TODO : add hard check
        logger.info("Disk mounted")
    }

    fun umount() {
        disk.close()
        logger.info("Disk unmounted")
    }
}

