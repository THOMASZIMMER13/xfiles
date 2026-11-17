package io.github.thomaszimmer13.xfiles.data.source

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocalFileSourceTest {

    @get:Rule val tmp = TemporaryFolder()
    private val source = LocalFileSource()

    @Test fun deleteRemovesFolderAndContent() = runBlocking {
        val dir = tmp.newFolder("Projet")
        File(dir, "sous/a.txt").apply { parentFile!!.mkdirs(); writeText("x") }
        source.delete(dir.path)
        assertFalse(dir.exists())
    }

    @Test(expected = FileOperationException::class)
    fun deleteMissing() = runBlocking {
        source.delete(File(tmp.root, "absent").path)
    }

    @Test fun trashFolderIsNeverListed() = runBlocking {
        tmp.newFolder(".xfiles-corbeille")
        tmp.newFolder("Documents")
        assertEquals(listOf("Documents"), source.list(tmp.root.path, includeHidden = true).map { it.name })
    }
}
