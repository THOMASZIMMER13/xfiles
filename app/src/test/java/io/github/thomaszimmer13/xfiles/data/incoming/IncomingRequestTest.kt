package io.github.thomaszimmer13.xfiles.data.incoming

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingRequestTest {
    @Test fun pickAcceptsWildcards() {
        val images = IncomingRequest.Pick(listOf("image/*"))
        assertTrue(images.accepts("image/jpeg"))
        assertFalse(images.accepts("application/pdf"))
        assertTrue(IncomingRequest.Pick(listOf("*/*")).accepts("application/pdf"))
        assertTrue(IncomingRequest.Pick(emptyList()).accepts("text/plain"))
        assertTrue(IncomingRequest.Pick(listOf("application/pdf", "image/png")).accepts("image/png"))
    }
}
