package fs4jb

import java.io.IOException

class FSIOException(message: String) : IOException(message)
class FSArgumentsException(message: String) : IllegalArgumentException(message)
class FSBrokenStateException(message: String) : Exception(message)
class FSIllegalStateException(message: String) : IllegalStateException(message)