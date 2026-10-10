package dev.anchildress1.wildfind.harness

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.anchildress1.wildfind.core.harness.createGateDirectory
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Gate logs remain beneath their root on Android, and invalid targets never reach the log writers. */
@RunWith(AndroidJUnit4::class)
class GateDirectoryDeviceTest {
    private lateinit var temp: File
    private val stamp = "20261009-200000"

    @Before
    fun createFixture() {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        temp = Files.createTempDirectory(cache.toPath(), "gate-directory-test-").toFile()
    }

    @After
    fun removeFixture() {
        temp.deleteRecursively()
    }

    @Test
    fun scientificAndTutorialRunsKeepTheirMetadataAndLogsInsideGate() {
        val root = File(temp, "gate").apply { mkdir() }
        for (target in listOf("Quercus nigra", "grass", "Unknown species")) {
            val dir = createGateDirectory(root, stamp, target)
            GateLog(dir).use { it.run(JSONObject().put("target", target)) }

            assertEquals(root.canonicalFile, dir.parentFile)
            assertEquals(setOf("run.json", "frames.csv", "system.csv", "events.csv"), dir.list()!!.toSet())
            assertEquals(target, JSONObject(File(dir, "run.json").readText()).getString("target"))
        }
    }

    @Test
    fun traversalWithAnExistingPrefixNeverReachesTheLogWriters() {
        val root = File(temp, "gate").apply { mkdir() }
        File(root, "$stamp-x").mkdir()
        for (target in listOf("x/../../escaped", "x\\..\\escaped", "x%2f..%2fescaped", "x\n../escaped")) {
            val failure = runCatching {
                GateLog(createGateDirectory(root, stamp, target)).use { it.run(JSONObject().put("target", target)) }
            }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertEquals(listOf("$stamp-x"), root.list()!!.toList())
            assertFalse(File(temp, "escaped").exists())
        }
    }
}
