package com.viruchith.shredder

import com.viruchith.shredder.MainActivity.SortOrder
import com.viruchith.shredder.MainActivity.SortType
import com.viruchith.shredder.browser.FileBrowserModel
import com.viruchith.shredder.browser.FileSelectionLogic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * ShredderUnitTest contains unit tests for the extracted pure Kotlin logic
 * including file listing, sorting, searching, selection, and engine metrics.
 */
class ShredderUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ==========================================
    // FileSelectionLogic Tests
    // ==========================================

    @Test
    fun testToggleSelection_addsAndRemovesFile() {
        val file1 = File("file1.txt")
        val file2 = File("file2.txt")
        var selected = emptyList<File>()

        // Add file1
        selected = FileSelectionLogic.toggleSelection(selected, file1)
        assertEquals(1, selected.size)
        assertTrue(selected.contains(file1))

        // Add file2
        selected = FileSelectionLogic.toggleSelection(selected, file2)
        assertEquals(2, selected.size)
        assertTrue(selected.contains(file2))

        // Remove file1
        selected = FileSelectionLogic.toggleSelection(selected, file1)
        assertEquals(1, selected.size)
        assertFalse(selected.contains(file1))
        assertTrue(selected.contains(file2))
    }

    @Test
    fun testSelectAll_addsMissingFiles() {
        val file1 = File("file1.txt")
        val file2 = File("file2.txt")
        val file3 = File("file3.txt")

        val currentFiles = listOf(file1, file2, file3)
        val selected = listOf(file1) // file1 is already selected

        val result = FileSelectionLogic.selectAll(selected, currentFiles)
        assertEquals(3, result.size)
        assertTrue(result.contains(file1))
        assertTrue(result.contains(file2))
        assertTrue(result.contains(file3))
    }

    @Test
    fun testDeselectAll_removesCurrentFiles() {
        val file1 = File("file1.txt")
        val file2 = File("file2.txt")
        val file3 = File("file3.txt")

        val currentFiles = listOf(file1, file2)
        val selected = listOf(file1, file2, file3)

        val result = FileSelectionLogic.deselectAll(selected, currentFiles)
        assertEquals(1, result.size)
        assertTrue(result.contains(file3))
        assertFalse(result.contains(file1))
        assertFalse(result.contains(file2))
    }

    @Test
    fun testIsAllSelected() {
        val file1 = File("file1.txt")
        val file2 = File("file2.txt")

        val currentFiles = listOf(file1, file2)

        // None selected
        assertFalse(FileSelectionLogic.isAllSelected(emptyList(), currentFiles))

        // Some selected
        assertFalse(FileSelectionLogic.isAllSelected(listOf(file1), currentFiles))

        // All selected
        assertTrue(FileSelectionLogic.isAllSelected(listOf(file1, file2), currentFiles))
    }

    // ==========================================
    // FileBrowserModel Tests
    // ==========================================

    @Test
    fun testProcessFiles_filtersBySearchQuery() {
        val file1 = tempFolder.newFile("apple.txt")
        val file2 = tempFolder.newFile("banana.txt")
        val file3 = tempFolder.newFile("pineapple.txt")

        val files = listOf(file1, file2, file3)

        val results = FileBrowserModel.processFiles(
            files = files,
            searchQuery = "apple",
            sortType = SortType.NAME,
            sortOrder = SortOrder.ASC
        )

        assertEquals(2, results.size)
        assertTrue(results.contains(file1))
        assertTrue(results.contains(file3))
        assertFalse(results.contains(file2))
    }

    @Test
    fun testProcessFiles_sortsByNameAscendingAndDescending() {
        val fileA = tempFolder.newFile("charles.txt")
        val fileB = tempFolder.newFile("alpha.txt")
        val fileC = tempFolder.newFile("bravo.txt")

        val files = listOf(fileA, fileB, fileC)

        // Ascending
        val ascResults = FileBrowserModel.processFiles(
            files = files,
            searchQuery = "",
            sortType = SortType.NAME,
            sortOrder = SortOrder.ASC
        )
        assertEquals("alpha.txt", ascResults[0].name)
        assertEquals("bravo.txt", ascResults[1].name)
        assertEquals("charles.txt", ascResults[2].name)

        // Descending
        val descResults = FileBrowserModel.processFiles(
            files = files,
            searchQuery = "",
            sortType = SortType.NAME,
            sortOrder = SortOrder.DESC
        )
        assertEquals("charles.txt", descResults[0].name)
        assertEquals("bravo.txt", descResults[1].name)
        assertEquals("alpha.txt", descResults[2].name)
    }

    @Test
    fun testProcessFiles_ensuresDirectoriesAlwaysAppearFirst() {
        val file = tempFolder.newFile("some_file.txt")
        val dir1 = tempFolder.newFolder("zebra_folder")
        val dir2 = tempFolder.newFolder("apple_folder")

        val files = listOf(file, dir1, dir2)

        // Even though zebra_folder is alphabetically after apple_folder and some_file,
        // and even though some_file is alphabetically before zebra_folder,
        // directories must be sorted at the top.
        val results = FileBrowserModel.processFiles(
            files = files,
            searchQuery = "",
            sortType = SortType.NAME,
            sortOrder = SortOrder.ASC
        )

        assertEquals(3, results.size)
        assertEquals("apple_folder", results[0].name) // Dir 1 (alpha first)
        assertEquals("zebra_folder", results[1].name) // Dir 2
        assertEquals("some_file.txt", results[2].name) // File (always last)
    }

    // ==========================================
    // ShredderEngine Calculations & Recursion Tests
    // ==========================================

    @Test
    fun testCalculateTotalSizeAndCountFiles_withSafeRecursion() {
        val root = tempFolder.newFolder("root_test")
        val file1 = File(root, "file1.bin")
        file1.writeBytes(ByteArray(100))

        val subDir = File(root, "sub")
        subDir.mkdir()
        val file2 = File(subDir, "file2.bin")
        file2.writeBytes(ByteArray(250))

        val filesList = listOf(root)

        // Check file count (should find 2 files recursively)
        val fileCount = ShredderEngine.countFiles(filesList)
        assertEquals(2, fileCount)

        // Check total size calculation (100 + 250 = 350 bytes)
        val totalSize = ShredderEngine.calculateTotalSize(filesList)
        assertEquals(350L, totalSize)
    }

    @Test
    fun testCalculateTotalSize_avoidsInfiniteLoopsWithSymlinks() {
        val root = tempFolder.newFolder("loop_test")
        val file1 = File(root, "real_file.bin")
        file1.writeBytes(ByteArray(50))

        // Simulate circular symlink references by manually duplicating the directory in input
        val filesList = listOf(root, root, file1)

        // ShredderEngine should be resilient and only count each unique file once
        val totalSize = ShredderEngine.calculateTotalSize(filesList)
        assertEquals(50L, totalSize)

        val count = ShredderEngine.countFiles(filesList)
        assertEquals(1, count)
    }

    // ==========================================
    // ShredAlgorithm Configuration Tests
    // ==========================================

    @Test
    fun testShredAlgorithms_specificationAndPasses() {
        // Fast (1-pass)
        val fast = ShredAlgorithm.Fast
        assertEquals("Fast (1-pass)", fast.name)
        assertEquals(1, fast.passes.size)
        assertTrue(fast.passes[0].type is PassType.RANDOM)

        // Standard (3-pass)
        val standard = ShredAlgorithm.Standard
        assertEquals("Standard (3-pass)", standard.name)
        assertEquals(3, standard.passes.size)
        assertTrue(standard.passes[0].type is PassType.RANDOM)
        assertTrue(standard.passes[1].type is PassType.PATTERN)
        assertTrue(standard.passes[2].type is PassType.RANDOM)

        // DoD 5220.22-M (7-pass)
        val dod = ShredAlgorithm.DoD5220_22M
        assertEquals(7, dod.passes.size)
        assertTrue(dod.passes[0].type is PassType.ZEROS)
        assertTrue(dod.passes[1].type is PassType.ONES)
        assertTrue(dod.passes[2].type is PassType.RANDOM)
        assertTrue(dod.passes[3].type is PassType.PATTERN)
        assertTrue(dod.passes[4].type is PassType.ZEROS)
        assertTrue(dod.passes[5].type is PassType.ONES)
        assertTrue(dod.passes[6].type is PassType.RANDOM)

        // Bruce Schneier (7-pass)
        val schneier = ShredAlgorithm.Schneier
        assertEquals(7, schneier.passes.size)
        assertTrue(schneier.passes[0].type is PassType.ZEROS)
        assertTrue(schneier.passes[1].type is PassType.ONES)
        assertTrue(schneier.passes[2].type is PassType.RANDOM)

        // Gutmann (35-pass)
        val gutmann = ShredAlgorithm.Gutmann
        assertEquals(35, gutmann.passes.size)
        // first 4 passes should be random
        repeat(4) { i ->
            assertTrue(gutmann.passes[i].type is PassType.RANDOM)
        }
        // last 4 passes should be random
        for (i in 31..34) {
            assertTrue(gutmann.passes[i].type is PassType.RANDOM)
        }
    }

    @Test
    fun testShredAlgorithm_fromNameFallback() {
        val parsed = ShredAlgorithm.fromName("Schneier (7-pass)")
        assertEquals(ShredAlgorithm.Schneier, parsed)

        val invalid = ShredAlgorithm.fromName("NonExistentAlgorithm")
        assertEquals(ShredAlgorithm.Standard, invalid)
    }

    @Test
    fun testFreeSpaceProgress_clampsAtNinetyNineUntilCleanup() {
        val p0 = ShredderEngine.computeFreeSpaceWipeProgress(0L, 100L)
        val pMid = ShredderEngine.computeFreeSpaceWipeProgress(50L, 100L)
        val pOver = ShredderEngine.computeFreeSpaceWipeProgress(200L, 100L)

        assertEquals(0f, p0, 0.0001f)
        assertEquals(0.5f, pMid, 0.0001f)
        assertEquals(0.99f, pOver, 0.0001f)
    }

    @Test
    fun testFreeSpaceWipe_cancelTransitionsToCancelled() {
        val storageRoot = tempFolder.newFolder("free_space_root")
        val sessionId = ShredderEngine.newSessionId()
        val latch = CountDownLatch(1)
        var finalResult: FreeSpaceWipeResult? = null

        ShredderEngine.wipeFreeSpaceSafely(storageRoot, sessionId) { result ->
            finalResult = result
            latch.countDown()
        }

        // Cooperative cancellation should be accepted for an active session.
        assertTrue(ShredderEngine.requestCancelFreeSpaceWipe(sessionId))

        if (!latch.await(20, TimeUnit.SECONDS)) {
            fail("Timed out waiting for free-space wipe cancellation result")
        }

        assertTrue(finalResult is FreeSpaceWipeResult.Cancelled)
        val state = ShredderEngine.freeSpaceWipeStateFlow.value
        assertEquals(FreeSpaceWipeStatus.CANCELLED, state.status)
    }
}
