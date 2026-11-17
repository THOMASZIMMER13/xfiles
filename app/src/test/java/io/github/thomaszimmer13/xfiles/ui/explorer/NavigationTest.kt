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

class FocusAfterRemovalTest {
    private val list = listOf("a", "b", "c", "d")

    @org.junit.Test fun nextRemainingThenPrevious() {
        org.junit.Assert.assertEquals(FocusRequest.Item("d"), focusAfterRemoval(list, setOf("b", "c")))
        org.junit.Assert.assertEquals(FocusRequest.Item("b"), focusAfterRemoval(list, setOf("c", "d")))
        org.junit.Assert.assertEquals(FocusRequest.Title, focusAfterRemoval(list, list.toSet()))
        org.junit.Assert.assertEquals(FocusRequest.Title, focusAfterRemoval(list, emptySet()))
    }
}
