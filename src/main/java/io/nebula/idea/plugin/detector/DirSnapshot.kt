package io.nebula.idea.plugin.detector

import com.google.common.io.ByteStreams
import java.nio.ByteBuffer

class DirSnapshot internal constructor() : Snapshot() {
    var subMap: HashMap<String, Snapshot> = hashMapOf()

    constructor(name: String) : this() {
        this.name = name
    }

    override fun toByteArray(): ByteArray {
        super.toByteArray()
        var subLen = 0
        val output = ByteStreams.newDataOutput()
        subMap.forEach {
            val snapshot = it.value
            val buffer = snapshot.toByteArray()
            subLen += buffer.size
        }
        val nameBytes = super.toByteArray()
        val byteBuffer = newBuffer(subLen + 1 + 8 + nameBytes.size)
        //type(byte) + child len(int) + name len(short) + name byte + sub
        byteBuffer.put(0)
            .putInt(subLen)
            .put(nameBytes)
            .put(output.toByteArray())
        return byteBuffer.array()
    }

    override fun readFromExternal(buffer: ByteBuffer): Long {
        val type = buffer.get().toInt()
        if (type != 0) {
            throw RuntimeException("wrong type!")
        }
        val subLen = buffer.long
        super.readFromExternal(buffer)
        var readSize: Long = 0
        while (readSize < subLen) {
            val subType = buffer.get().toInt()
            if (subType == 1) {
                val fileSnapshot = FileSnapshot()
                readSize += fileSnapshot.readFromExternal(buffer)
                subMap[fileSnapshot.name] = fileSnapshot
            } else {
                val dirSnapshot = DirSnapshot()
                readSize += dirSnapshot.readFromExternal(buffer)
                subMap[dirSnapshot.name] = dirSnapshot
            }
        }
        return readSize + 1 + 8
    }
}