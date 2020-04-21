package io.nebula.idea.plugin.detector

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets.UTF_8

abstract class Snapshot internal constructor() {
    var name: String = ""

    open fun toByteArray(): ByteArray {
        val bytes = name.toByteArray(UTF_8)
        val buffer = newBuffer(bytes.size + 2)
        buffer.putShort(bytes.size.toShort())
        buffer.put(bytes)
        return buffer.array()
    }

    protected fun newBuffer(cap: Int): ByteBuffer {
        val buffer = ByteBuffer.allocate(cap)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return buffer
    }

    open fun writeToExternal(snapshot: File) {
        if (!snapshot.exists()) {
            snapshot.createNewFile()
        }
        FileOutputStream(snapshot).use {
            val size = toByteArray().size
            println("totoal size:" + size)
            it.write(toByteArray())
            it.flush()
        }
    }

    open fun readFromExternal(snapshot: File) {

    }

    open fun readFromExternal(buffer: ByteBuffer): Long {
        val nameLen = buffer.short
        val byteArray = ByteArray(nameLen.toInt())
        buffer.get(byteArray)
        name = String(byteArray, UTF_8)
        return (nameLen + 2).toLong()
    }
}
