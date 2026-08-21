package com.openautolink.app.ui.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoResolutionPresentationContractTest {
    private fun projectPath(relative: String): File {
        val start = File(requireNotNull(System.getProperty("user.dir")))
        val root = generateSequence(start) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("Could not locate project root from $start")
        return File(root, relative)
    }

    private fun projectFile(relative: String): String = projectPath(relative).readText()

    @Test
    fun `all resolution options use normal styling without developer mode warnings`() {
        val settings = projectFile(
            "app/src/main/java/com/openautolink/app/ui/settings/SettingsScreen.kt",
        )
        val resolutionBlock = settings.substringAfter("// --- Resolution Tier ---")
            .substringBefore("} // end if (!videoAutoNegotiate)")

        listOf(
            "Developer Mode",
            "developer settings",
            "isHighRes",
            "warningColor",
            "Color(0xFFFFB74D)",
        ).forEach { stale ->
            assertFalse("Resolution warning/styling remains: $stale", resolutionBlock.contains(stale))
        }

        listOf(
            "1440p (2560×1440)",
            "4K (3840×2160)",
            "1440p portrait (1440×2560)",
            "4K portrait (2160×3840)",
        ).forEach { option -> assertTrue("Missing resolution option: $option", resolutionBlock.contains(option)) }
    }

    @Test
    fun `screenshot catalog does not claim high resolutions require developer mode`() {
        val catalog = projectFile("docs/screenshots/README.md")

        assertFalse(catalog.contains("AA Developer Mode warnings for high-res"))
        assertFalse(catalog.contains("14-settings-video-manual-scrolled1"))
        assertFalse(projectPath("docs/screenshots/14-settings-video-manual-scrolled1.png").exists())
    }

    @Test
    fun `public README does not gate high resolutions on developer mode`() {
        val readme = projectFile("README.md")

        assertFalse(readme.contains("Up to 4K with AA Developer Mode"))
        assertFalse(readme.contains("Requires AA Developer Mode"))
        assertTrue(readme.contains("1440p (2560×1440)"))
        assertTrue(readme.contains("4K (3840×2160)"))
    }
}
