package com.openautolink.app.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSessionDependencyContractTest {

    @Test
    fun `every native start funnels through one complete dependency preparer`() {
        val source = projectFile(
            "app/src/main/java/com/openautolink/app/session/SessionManager.kt",
        ).readText()
        val start = source.indexOf("private fun prepareNativeSessionStart(session: AasdkSession)")
        val end = source.indexOf("private fun startLocationForwarding", startIndex = start)

        assertTrue(start >= 0)
        assertTrue(end > start)
        val helper = source.substring(start, end)
        assertTrue(helper.contains("ensureVideoDecoder()"))
        assertTrue(helper.contains("ensureAudioPlayer()"))
        assertTrue(helper.contains("aasdkSession = session"))
        assertTrue(helper.contains("bindSessionCollectors(session)"))
        assertTrue(helper.contains("Native session dependencies ready:"))

        assertEquals(
            1,
            Regex("""session\.onNativeSessionStarting\s*=\s*\{\s*prepareNativeSessionStart\(session\)\s*}""")
                .findAll(source)
                .count(),
        )
    }

    private fun projectFile(relativePath: String): File {
        val workingDir = File(checkNotNull(System.getProperty("user.dir")))
        return generateSequence(workingDir) { it.parentFile }
            .map { root -> File(root, relativePath) }
            .firstOrNull(File::isFile)
            ?: error("Could not locate $relativePath from $workingDir")
    }
}
