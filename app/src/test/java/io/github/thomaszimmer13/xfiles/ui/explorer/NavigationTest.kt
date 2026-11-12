package io.github.thomaszimmer13.xfiles.ui.explorer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavigationTest {

    @Test fun directChild() {
        assertEquals("/a/b", directChildToward("/a", "/a/b/c/d"))
        assertEquals("/a/b", directChildToward("/a", "/a/b"))
        assertNull(directChildToward("/a", "/a"))
        assertNull(directChildToward("/a", "/ab/c"))
        assertNull(directChildToward("/a", null))
    }
}
