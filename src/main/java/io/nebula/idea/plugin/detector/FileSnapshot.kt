package io.nebula.idea.plugin.detector

import java.io.File
import java.nio.ByteBuffer


open class FileSnapshot internal constructor() : Snapshot() {
    private var size: Long = 0
    private var lastModified: Long = 0

    constructor(name: String) : this() {
        this.name = name
    }

    constructor(file: File) : this(file.name) {
        this.size = file.length()
        this.lastModified = file.lastModified()
    }

    fun isModified(file: File): Boolean {
        if (file.name != name) {
            throw IllegalArgumentException("argument file name doesn't match , except:$name but got ${file.name}")
        }
        return size != file.length() || lastModified != file.lastModified()
    }

    override fun toByteArray(): ByteArray {
        val byteArray = super.toByteArray()
        //type(byte) + name len(short) + name byte + size(long) + last modified(long)
        val totalSize = 1 + byteArray.size + 8 + 8
        val buffer = newBuffer(totalSize)
        buffer.put(1)
        buffer.put(byteArray)
        buffer.putLong(size)
        buffer.putLong(lastModified)
        return buffer.array()
    }

    override fun readFromExternal(buffer: ByteBuffer): Long {
        //type(byte) + name len(short) + name byte + size(long) + last modified(long)
        val type = buffer.get().toInt()
        if (type != 1) {
            throw RuntimeException("wrong type.")
        }
        val totalSize: Long = 1 + super.readFromExternal(buffer) + 8 + 8
        size = buffer.long
        lastModified = buffer.long
        return totalSize
    }
}