package io.nebula.idea.plugin.detector

import com.intellij.openapi.project.Project
import org.apache.commons.io.FilenameUtils
import java.io.File

object ModifyChecker {
    //relative path base on module directory
    private val whiteList = setOf(
        "build",
        ".externalNativeBuild",
        "src/test",
        ".gitignore",
        "*.iml"
    )

    private var moduleModified = false

    fun checkForModifiedModuleList(project: Project, moduleDepList: Set<String>): List<String> {
        val startMillis = System.currentTimeMillis()
        val basePath = project.basePath
        println(basePath)
        val result = arrayListOf<String>()
        moduleDepList.forEach {
            val moduleDir = File(basePath, it)
            val buildFile = File(moduleDir.parent, "build")
            val snapshot = loadTopSnapshot(moduleDir, buildFile)
            if (snapshot == null) {
                //no previous build config, full build, and save snapshots
                val dirSnapshot = constructSnapshot(moduleDir, moduleDir.absolutePath)
                writeTopSnapshot(dirSnapshot, moduleDir, buildFile)
                result.add(it)
                return@forEach
            }
            moduleModified = false
            checkRecursive(moduleDir, snapshot, basePath!!)
            if (moduleModified) {
                result.add(it)
            }
            snapshot.writeToExternal(getSnapshotFile(moduleDir, buildFile))
        }
        println("check cost:${System.currentTimeMillis() - startMillis}")
        return result
    }

    private fun constructSnapshot(dir: File, basePath: String): Snapshot {
        val dirSnapshot = DirSnapshot(dir.name)
        constructSnapshotInternal(dirSnapshot, dir, basePath)
        return dirSnapshot
    }

    private fun constructSnapshotInternal(
        dirSnapshot: DirSnapshot,
        dir: File,
        basePath: String
    ) {
        dir.listFiles()?.forEach {
            if (accept(it, basePath)) {
                return@forEach
            }
            if (it.isDirectory) {
                val dirTemp = DirSnapshot(it.name)
                dirSnapshot.subMap[it.name] = dirTemp
                constructSnapshotInternal(dirTemp, it, basePath)
            } else {
                dirSnapshot.subMap[it.name] = FileSnapshot(it)
            }
        }
    }

    private fun accept(file: File, basePath: String): Boolean {
        whiteList.forEach {
            if (FilenameUtils.wildcardMatch(file.absolutePath, "$basePath/$it")) {
                return true
            }
        }
        return false
    }

    private fun checkRecursive(file: File, fileSnapshot: Snapshot, basePath: String) {
        if (fileSnapshot !is DirSnapshot) {
            throw RuntimeException("top level module directory changed to file? It's impossible!")
        }
        val originSubMap = fileSnapshot.subMap
        val existFileSet = hashSetOf<Snapshot>()
        val addFileSet = hashSetOf<Snapshot>()
        file.listFiles()?.forEach {
            if (accept(it, basePath)) {
                return@forEach
            }
            val currSnapshot = originSubMap.remove(it.name)
            if (currSnapshot == null) {
                moduleModified = true
                if (it.isDirectory) {
                    println("add dir:${it.absolutePath}")
                    addFileSet.add(constructSnapshot(it, basePath))
                } else {
                    println("add file:${it.absolutePath}")
                    addFileSet.add(FileSnapshot(it))
                }
                return@forEach
            }
            if (it.isDirectory) {
                if (currSnapshot is DirSnapshot) {
                    checkRecursive(it, currSnapshot, basePath)
                } else {
                    moduleModified = true
                    println("dir: ${it.absolutePath} changed to file.")
                    existFileSet.add(constructSnapshot(it, basePath))
                }
            } else {
                if (currSnapshot is DirSnapshot) {
                    moduleModified = true
                    existFileSet.add(FileSnapshot(it))
                    println("fie: ${it.absolutePath} changed to dir.")
                } else {
                    //file type didn't changed, check content whether changed
                    if ((currSnapshot as FileSnapshot).isModified(it)) {
                        moduleModified = true
                        println("file modified:${it.absolutePath}")
                        existFileSet.add(FileSnapshot(it))
                    } else {
                        //file didn't changed, use origin snapshot
                        existFileSet.add(currSnapshot)
                    }
                }
            }
        }
        if (originSubMap.isNotEmpty()) {
            println("file deleted:")
            originSubMap.forEach {
                print(it.key + ", ")
            }
        }
        fileSnapshot.subMap.clear()
        existFileSet.forEach {
            fileSnapshot.subMap[it.name] = it
        }
        addFileSet.forEach {
            fileSnapshot.subMap[it.name] = it
        }
    }

    private fun loadTopSnapshot(file: File, buildFile: File): DirSnapshot? {
        val snapshotFile = getSnapshotFile(file, buildFile)
        if (!snapshotFile.exists()) {
            return null
        }
        val snapshot = DirSnapshot(file.name)
        snapshot.readFromExternal(snapshotFile)
        return snapshot
    }

    private fun writeTopSnapshot(snapshot: Snapshot, file: File, buildFile: File) {
        val snapshotFile = getSnapshotFile(file, buildFile)
        snapshot.writeToExternal(snapshotFile)
    }

    private fun getSnapshotFile(file: File, buildFile: File): File {
        return File(buildFile, file.name + ".snapshot")
    }
}