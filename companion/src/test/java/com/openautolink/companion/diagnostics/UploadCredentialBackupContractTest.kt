package com.openautolink.companion.diagnostics

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadCredentialBackupContractTest {
    private fun projectFile(path: String): File {
        val candidates = listOf(File(path), File("companion/$path"), File("../companion/$path"))
        return candidates.first { it.exists() }
    }

    @Test fun allSharedPreferencesAreExcludedWhileLegacyTokenMayExist() {
        val source = projectFile("src/main/java/com/openautolink/companion/diagnostics/UploadCredentialStore.kt").readText()
        assertTrue(source.contains("OalUploadCredentials"))
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val file = projectFile("src/main/res/xml/$name")
            val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val excludes = document.getElementsByTagName("exclude")
            assertTrue("$name must contain an exclusion", excludes.length > 0)
            val sharedPrefRootExcludes = (0 until excludes.length).count { index ->
                val node = excludes.item(index)
                node.attributes.getNamedItem("domain")?.nodeValue == "sharedpref" &&
                    node.attributes.getNamedItem("path")?.nodeValue == "."
            }
            assertTrue("$name must exclude the complete legacy preference domain", sharedPrefRootExcludes > 0)
            assertEquals("$name must not allowlist backed-up preferences", 0, document.getElementsByTagName("include").length)
        }
    }

    @Test fun migrationRemovesLegacyTokenWithDurableCommits() {
        val source = projectFile("src/main/java/com/openautolink/companion/diagnostics/UploadCredentialStore.kt").readText()
        assertTrue(source.contains("putString(TOKEN_KEY, legacy).commit()"))
        assertTrue(source.contains("remove(CompanionPrefs.LOG_UPLOAD_TOKEN).commit()"))
        assertFalse(source.contains("remove(CompanionPrefs.LOG_UPLOAD_TOKEN).apply()"))
    }
}
