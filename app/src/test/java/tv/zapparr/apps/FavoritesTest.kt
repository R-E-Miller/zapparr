package tv.zapparr.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class FavoritesTest {

    @Test
    fun defaultsKeepTheirOrderAndSkipAppsThatAreNotInstalled() {
        val installed = listOf("com.example.news", "com.google.android.youtube.tv", "ar.tvplayer.tv")

        assertEquals(listOf("ar.tvplayer.tv", "com.google.android.youtube.tv"), Favorites.visible(null, installed))
    }

    @Test
    fun aSavedRowReplacesTheDefaults() {
        val installed = listOf("com.example.news", "ar.tvplayer.tv", "com.cbs.ott")

        assertEquals(
            listOf("com.example.news", "ar.tvplayer.tv"),
            Favorites.visible(listOf("com.example.news", "com.example.gone", "ar.tvplayer.tv"), installed),
        )
    }

    @Test
    fun anEmptiedRowStaysEmptyRatherThanFallingBackToTheDefaults() {
        assertEquals(emptyList<String>(), Favorites.visible(emptyList(), Favorites.DEFAULTS))
    }

    @Test
    fun addAppendsOnlyOnce() {
        val row = Favorites.add(listOf("a"), "b")

        assertEquals(listOf("a", "b"), row)
        assertEquals(row, Favorites.add(row, "a"))
    }

    @Test
    fun removeDropsTheApp() {
        assertEquals(listOf("a", "c"), Favorites.remove(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun moveSwapsWithANeighbourAndStopsAtTheEnds() {
        val row = listOf("a", "b", "c")

        assertEquals(listOf("b", "a", "c"), Favorites.move(row, "b", -1))
        assertEquals(listOf("a", "c", "b"), Favorites.move(row, "b", 1))
        assertEquals(row, Favorites.move(row, "a", -1))
        assertEquals(row, Favorites.move(row, "c", 1))
        assertEquals(row, Favorites.move(row, "missing", 1))
    }
}
