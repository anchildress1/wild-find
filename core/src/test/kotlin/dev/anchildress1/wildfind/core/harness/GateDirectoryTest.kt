package dev.anchildress1.wildfind.core.harness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.nio.file.Files

class GateDirectoryTest {
    @TempDir
    lateinit var temp: File

    private val stamp = "20261009-200000"

    @ParameterizedTest
    @ValueSource(strings = ["Quercus nigra", "grass", "Unknown species", "Quercus ×alba", "A. species", "Érable"])
    fun `scientific names and tutorial targets create direct children`(target: String) {
        val root = File(temp, "gate").apply { mkdir() }

        val dir = createGateDirectory(root, stamp, target)

        assertEquals(root.canonicalFile, dir.parentFile)
        assertEquals("$stamp-${target.lowercase().replace(' ', '-')}", dir.name)
        assertTrue(dir.isDirectory)
    }

    @Test
    fun `traversal cannot create directories outside gate when the timestamp prefix exists`() {
        val root = File(temp, "gate").apply { mkdir() }
        File(root, "$stamp-x").mkdir()

        assertThrows<IllegalArgumentException> { createGateDirectory(root, stamp, "x/../../escaped") }

        assertFalse(File(temp, "escaped").exists())
        assertEquals(listOf("$stamp-x"), root.list()!!.toList())
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "x/../../escaped", "/tmp/escaped", "x\\..\\escaped", "x//../escaped", "x%2f..%2fescaped",
            "x%252f..%252fescaped", "x∕..∕escaped", "x\u0000escaped", "x\nescaped", "x\tescaped",
            "", " ", "..", "123",
        ],
    )
    fun `unsafe targets are rejected without creating directories`(target: String) {
        val root = File(temp, "gate").apply { mkdir() }

        assertEquals(
            "invalid gate target",
            assertThrows<IllegalArgumentException> { createGateDirectory(root, stamp, target) }.message,
        )

        assertTrue(root.list()!!.isEmpty())
    }

    @ParameterizedTest
    @ValueSource(strings = ["", "20261009", "../escape", "20261009-200000/..", "20261009-200000\n"])
    fun `unsafe timestamps are rejected without creating directories`(value: String) {
        val root = File(temp, "gate").apply { mkdir() }

        assertEquals(
            "invalid gate run timestamp",
            assertThrows<IllegalArgumentException> { createGateDirectory(root, value, "grass") }.message,
        )

        assertTrue(root.list()!!.isEmpty())
    }

    @Test
    fun `an existing directory and its logs are never reused`() {
        val root = File(temp, "gate").apply { mkdir() }
        val dir = createGateDirectory(root, stamp, "grass")
        val log = File(dir, "run.json").apply { writeText("existing run") }

        assertThrows<IllegalStateException> { createGateDirectory(root, stamp, "grass") }

        assertEquals("existing run", log.readText())
        assertEquals(listOf("$stamp-grass"), root.list()!!.toList())
    }

    @Test
    fun `a symlink to a sibling whose path shares the root prefix is rejected`() {
        val root = File(temp, "gate").apply { mkdir() }
        val sibling = File(temp, "gate-escaped").apply { mkdir() }
        Files.createSymbolicLink(File(root, "$stamp-grass").toPath(), sibling.toPath())

        assertEquals(
            "gate run escapes its root",
            assertThrows<IllegalArgumentException> { createGateDirectory(root, stamp, "grass") }.message,
        )

        assertTrue(sibling.list()!!.isEmpty())
    }

    @Test
    fun `an existing file is never replaced by a run directory`() {
        val root = File(temp, "gate").apply { mkdir() }
        val file = File(root, "$stamp-grass").apply { writeText("existing file") }

        assertThrows<IllegalStateException> { createGateDirectory(root, stamp, "grass") }

        assertEquals("existing file", file.readText())
    }

    @Test
    fun `a missing root is rejected without creating parents`() {
        val root = File(temp, "missing/gate")

        assertThrows<IllegalArgumentException> { createGateDirectory(root, stamp, "grass") }

        assertFalse(File(temp, "missing").exists())
    }

    @Test
    fun `a file cannot be used as the log root`() {
        val root = File(temp, "gate").apply { writeText("existing file") }

        assertThrows<IllegalArgumentException> { createGateDirectory(root, stamp, "grass") }

        assertEquals("existing file", root.readText())
    }
}
