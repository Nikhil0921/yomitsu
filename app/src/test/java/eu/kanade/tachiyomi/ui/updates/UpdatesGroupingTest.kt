package eu.kanade.tachiyomi.ui.updates

import eu.kanade.presentation.updates.UpdatesUiModel
import eu.kanade.tachiyomi.data.download.model.Download
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.updates.model.UpdatesWithRelations
import java.time.LocalDate

class UpdatesGroupingTest {

    @Test
    fun `empty list stays empty`() {
        assertEquals(emptyList<UpdatesUiModel>(), groupConsecutiveUpdates(emptyList(), emptySet()))
    }

    @Test
    fun `single item stays flat`() {
        val models = listOf(item(mangaId = 1, chapterId = 10))
        assertEquals(models, groupConsecutiveUpdates(models, emptySet()))
    }

    @Test
    fun `different manga in a row are not grouped`() {
        val models = listOf(item(1, 10), item(2, 20))
        assertEquals(models, groupConsecutiveUpdates(models, emptySet()))
    }

    @Test
    fun `two consecutive items of same manga fold into one collapsed group`() {
        val a = item(1, 10)
        val b = item(1, 11)
        val result = groupConsecutiveUpdates(listOf(a, b), emptySet())
        val group = result.single() as UpdatesUiModel.Group
        assertEquals(1L, group.mangaId)
        assertEquals(listOf(a.item, b.item), group.items)
        assertEquals(false, group.expanded)
    }

    @Test
    fun `expanded group flags expanded state`() {
        val a = item(1, 10)
        val b = item(1, 11)
        val result = groupConsecutiveUpdates(listOf(a, b), setOf(1L))
        val group = result.single() as UpdatesUiModel.Group
        assertEquals(true, group.expanded)
    }

    @Test
    fun `date header splits runs so same manga never groups across days`() {
        val a1 = item(1, 10)
        val a2 = item(1, 11)
        val header = UpdatesUiModel.Header(LocalDate.of(2026, 9, 15))
        val a3 = item(1, 12)
        val result = groupConsecutiveUpdates(listOf(a1, a2, header, a3), emptySet())
        val group = result[0] as UpdatesUiModel.Group
        assertEquals(listOf(a1.item, a2.item), group.items)
        assertEquals(header, result[1])
        assertEquals(a3, result[2])
    }

    @Test
    fun `groups keep relative order of headers and other manga items`() {
        val a1 = item(1, 10)
        val a2 = item(1, 11)
        val b1 = item(2, 20)
        val result = groupConsecutiveUpdates(listOf(a1, a2, b1), emptySet())
        assertEquals(2, result.size)
        assertEquals(listOf(a1.item, a2.item), (result[0] as UpdatesUiModel.Group).items)
        assertEquals(b1, result[1])
    }

    private fun item(mangaId: Long, chapterId: Long): UpdatesUiModel.Item {
        val update = UpdatesWithRelations(
            mangaId = mangaId,
            mangaTitle = "Manga $mangaId",
            chapterId = chapterId,
            chapterName = "Chapter $chapterId",
            scanlator = null,
            chapterUrl = "url-$chapterId",
            read = false,
            bookmark = false,
            lastPageRead = 0L,
            sourceId = 1L,
            dateFetch = 0L,
            coverData = MangaCover(mangaId, 1L, true, null, 0L),
        )
        return UpdatesUiModel.Item(
            UpdatesItem(
                update = update,
                downloadStateProvider = { Download.State.NOT_DOWNLOADED },
                downloadProgressProvider = { 0 },
            ),
        )
    }
}
