package io.github.thomaszimmer13.xfiles.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Audit M-11 : contrôleurs admis par la session de lecture. */
class TrustedControllersTest {
    private val callback = PlaybackService.TrustedControllers("io.github.thomaszimmer13.xfiles")

    @Test fun onlyTrustedOrOwn() {
        assertTrue(callback.accepts(trusted = false, controllerPackage = "io.github.thomaszimmer13.xfiles"))
        assertTrue(callback.accepts(trusted = true, controllerPackage = "com.android.systemui"))
        assertFalse(callback.accepts(trusted = false, controllerPackage = "com.example.espion"))
    }
}
