package com.openautolink.companion.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadSecurityPolicyTest {
    @Test fun acceptsOnlyHttpsEndpoints() {
        assertTrue(UploadEndpointPolicy.isAllowed("https://logs.example.com/upload"))
        assertFalse(UploadEndpointPolicy.isAllowed("http://logs.example.com/upload"))
        assertFalse(UploadEndpointPolicy.isAllowed("file:///tmp/logs"))
    }

    @Test fun migrationCopiesLegacyTokenOnlyWhenSecretStoreIsEmpty() {
        assertEquals(TokenMigration.COPY_AND_REMOVE_LEGACY, TokenMigration.plan("", "legacy-token"))
        assertEquals(TokenMigration.REMOVE_LEGACY_ONLY, TokenMigration.plan("new-token", "legacy-token"))
        assertEquals(TokenMigration.NONE, TokenMigration.plan("", ""))
    }
}
