package com.openautolink.app.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UploadEndpointPolicyTest {
    @Test fun acceptsHttpsEndpoint() {
        assertTrue(UploadEndpointPolicy.isAllowed("https://logs.example.com/upload"))
    }

    @Test fun rejectsCleartextHttpEndpoint() {
        assertFalse(UploadEndpointPolicy.isAllowed("http://logs.example.com/upload"))
    }

    @Test fun rejectsNonHttpAndMalformedEndpoints() {
        assertFalse(UploadEndpointPolicy.isAllowed("ftp://logs.example.com/upload"))
        assertFalse(UploadEndpointPolicy.isAllowed("not a url"))
    }
}
