package io.github.thomaszimmer13.xfiles.data.shizuku

import android.os.IBinder
import android.os.ParcelFileDescriptor
import io.github.thomaszimmer13.xfiles.data.remote.RemoteException
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShizukuRemoteTest {
    private class Fake : IFileService {
        val calls = mutableListOf<String>()
        override fun asBinder(): IBinder? = null
        override fun destroy() = Unit
        override fun list(path: String): String {
            calls += "list $path"
            if (path.endsWith("absent")) throw IllegalStateException(FileService.NOT_FOUND)
            return JSONArray().put(JSONObject().put("n", "com.exemple").put("d", true).put("s", 0).put("m", 5)).toString()
        }
        override fun openRead(path: String): ParcelFileDescriptor = throw IllegalStateException(FileService.DENIED)
        override fun openWrite(path: String): ParcelFileDescriptor = throw IllegalStateException(FileService.DENIED)
        override fun mkdir(path: String) {
            calls += "mkdir $path"
            throw IllegalStateException(FileService.EXISTS)
        }
        override fun rename(from: String, to: String) {
            calls += "rename $from $to"
        }
        override fun delete(path: String) {
            calls += "delete $path"
        }
        override fun exists(path: String) = true
    }

    @Test fun pathsAreUnderAndroidFolderAndErrorsTranslated() {
        val fake = Fake()
        val remote = ShizukuRemote { fake }
        val list = remote.list("/data")
        assertEquals("/storage/emulated/0/Android/data", fake.calls[0].removePrefix("list "))
        assertEquals("/data/com.exemple", list.single().path)
        assertTrue(list.single().isDirectory)
        remote.rename("/data/a", "/data/b")
        assertEquals("rename /storage/emulated/0/Android/data/a /storage/emulated/0/Android/data/b", fake.calls.last())
        fun reason(block: () -> Unit) = try {
            block()
            fail()
            null
        } catch (e: RemoteException) {
            e.reason
        }
        assertEquals(RemoteException.Reason.NOT_FOUND, reason { remote.list("/data/absent") })
        assertEquals(RemoteException.Reason.EXISTS, reason { remote.mkdir("/data/x") })
        assertEquals(RemoteException.Reason.DENIED, reason { remote.download("/obb/a.obb", java.io.ByteArrayOutputStream()) })
    }
}
