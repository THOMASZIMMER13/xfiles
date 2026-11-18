package io.github.thomaszimmer13.xfiles.data.security

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppLockTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test fun locksOnlyAfterTheDelayAndClosesVaultsAfterFiveMinutes() {
        var vaultsClosed = 0
        val lock = AppLock(context) { vaultsClosed++ }
        lock.setEnabled(true)
        lock.setDelay(60)
        lock.setProtectServers(true)
        lock.unlocked()

        lock.onBackground()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(30))
        lock.onForeground()
        assertFalse(lock.locked.value)
        assertTrue(lock.serversConfirmed.value)

        lock.onBackground()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(61))
        lock.onForeground()
        assertTrue(lock.locked.value)
        assertFalse(lock.serversConfirmed.value)
        assertEquals(1, vaultsClosed) // verrouillage : les coffres se ferment aussi

        lock.unlocked()
        lock.setEnabled(false)
        lock.onBackground()
        ShadowSystemClock.advanceBy(Duration.ofMinutes(6))
        lock.onForeground()
        assertFalse(lock.locked.value)
        assertEquals(2, vaultsClosed)
    }

    @Test fun settingsSurviveRestart() {
        AppLock(context) {}.apply {
            setEnabled(true)
            setDelay(300)
        }
        val again = AppLock(context) {}
        assertTrue(again.enabled.value)
        assertTrue(again.locked.value) // démarrage : verrouillé
        assertEquals(300, again.delay.value)
    }
}
