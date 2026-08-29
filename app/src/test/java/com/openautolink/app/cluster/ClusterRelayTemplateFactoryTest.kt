package com.openautolink.app.cluster

import org.junit.Assert.assertNotNull
import org.junit.Test

class ClusterRelayTemplateFactoryTest {

    @Test
    fun windowlessRelayTemplateKeepsRequiredActionStrip() {
        val template = ClusterRelayTemplateFactory.build()

        assertNotNull(template.actionStrip)
    }
}
