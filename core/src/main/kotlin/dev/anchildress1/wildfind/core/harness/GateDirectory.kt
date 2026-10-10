package dev.anchildress1.wildfind.core.harness

import java.io.File

private val runStamp = Regex("[0-9]{8}-[0-9]{6}")
private val targetName = Regex("[\\p{L}\\p{N} .×'-]+")

/** Creates a new gate log directory beneath [root], rejecting invalid names or an existing destination. */
fun createGateDirectory(root: File, stamp: String, target: String): File {
    require(runStamp.matches(stamp)) { "invalid gate run timestamp" }
    require(targetName.matches(target) && target.any { it.isLetter() }) { "invalid gate target" }
    val parent = root.canonicalFile
    require(parent.isDirectory) { "gate root is not a directory" }
    val dir = File(parent, "$stamp-${target.lowercase().replace(' ', '-')}").canonicalFile
    require(dir.parentFile == parent) { "gate run escapes its root" }
    check(dir.mkdir()) { "can't create gate run directory" }
    return dir
}
