package tv.zapparr.apps

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import androidx.leanback.widget.VerticalGridView
import tv.zapparr.R
import tv.zapparr.live.Images
import tv.zapparr.look.Look
import tv.zapparr.look.Option

/**
 * The Apps deck: a Favorites row on top, then Continue Watching and On now while they have
 * anything to show, then every launchable app, six to a row.
 */
class AppsDeck(private val rows: VerticalGridView, private val store: FavoritesStore) {

    private val context: Context get() = rows.context
    private var apps: List<AppEntry> = emptyList()
    private var favorites: List<String> = emptyList()
    private var continuing: List<Card> = emptyList()
    private var onNow: List<Card> = emptyList()
    private val adapter = run {
        val height = context.resources.getDimensionPixelSize(R.dimen.card_height)
        val resolver = context.applicationContext.contentResolver
        RowsAdapter(
            onOpen = { context.openApp(it) },
            onMenu = ::showMenu,
            pictures = Images(height, PICTURE_CACHE, opaque = true, resolver = resolver),
            logos = Images(height, LOGO_CACHE, resolver = resolver),
        )
    }

    init {
        rows.adapter = adapter
        if (Look.current[Option.HOME_SPACING] == "compact") rows.verticalSpacing = context.resources.getDimensionPixelSize(R.dimen.row_spacing_compact)
    }

    fun submit(installed: List<AppEntry>) {
        apps = installed
        favorites = Favorites.visible(store.saved(), installed.map { it.packageName })
        rebuild()
    }

    internal fun showContinueWatching(cards: List<Card>) {
        continuing = cards
        rebuild()
    }

    internal fun showOnNow(cards: List<Card>) {
        onNow = cards
        rebuild()
    }

    /** Focuses the first favorite, or the first app when there are none, as Home does. */
    fun focusFirst() {
        val row = if (favorites.isEmpty()) firstAppsRow() else FAVORITES_ROW
        if (row >= adapter.itemCount) return
        adapter.selectTile(rows, row, 0)
        rows.selectedPosition = row
        rows.requestFocus()
    }

    private fun rebuild() {
        val byPackage = apps.associateBy { it.packageName }
        // Favorites always comes first, even when empty, so it never changes index.
        val list = mutableListOf<DeckRow>(
            TileRow(
                id = "favorites",
                title = context.getString(R.string.favorites),
                apps = favorites.mapNotNull { byPackage[it] },
                hint = context.getString(R.string.favorites_hint),
            ),
        )
        if (continuing.isNotEmpty()) list += CardRow("continue", context.getString(R.string.continue_watching), continuing)
        if (onNow.isNotEmpty()) list += CardRow("on_now", context.getString(R.string.on_now), onNow)
        apps.chunked(COLUMNS).forEachIndexed { i, chunk ->
            list += TileRow(id = "apps:$i", title = if (i == 0) context.getString(R.string.all_apps) else null, apps = chunk)
        }
        adapter.submit(list)
    }

    private fun firstAppsRow() = FAVORITES_ROW + 1 + (if (continuing.isEmpty()) 0 else 1) + (if (onNow.isEmpty()) 0 else 1)

    private fun showMenu(app: AppEntry, anchor: View) {
        val index = favorites.indexOf(app.packageName)
        val popup = PopupMenu(context, anchor)
        if (index < 0) {
            popup.menu.add(0, ADD, 0, R.string.menu_add_favorite)
        } else {
            popup.menu.add(0, REMOVE, 0, R.string.menu_remove_favorite)
            if (index > 0) popup.menu.add(0, MOVE_LEFT, 1, R.string.menu_move_left)
            if (index < favorites.lastIndex) popup.menu.add(0, MOVE_RIGHT, 2, R.string.menu_move_right)
        }
        popup.menu.add(0, APP_INFO, 3, R.string.menu_app_info)
        popup.setOnMenuItemClickListener { item ->
            val pkg = app.packageName
            when (item.itemId) {
                ADD -> updateFavorites(Favorites.add(favorites, pkg), focus = null)
                REMOVE -> updateFavorites(Favorites.remove(favorites, pkg), focus = null)
                MOVE_LEFT -> updateFavorites(Favorites.move(favorites, pkg, -1), focus = pkg)
                MOVE_RIGHT -> updateFavorites(Favorites.move(favorites, pkg, 1), focus = pkg)
                APP_INFO -> context.openAppInfo(app)
            }
            true
        }
        popup.show()
    }

    private fun updateFavorites(row: List<String>, focus: String?) {
        favorites = row
        store.save(row)
        rebuild()
        when {
            row.isEmpty() -> focusFirst()
            focus != null -> adapter.selectTile(rows, FAVORITES_ROW, row.indexOf(focus))
        }
    }

    private companion object {
        const val COLUMNS = 6
        const val FAVORITES_ROW = 0
        // Pictures at 16 bits a pixel: about fifty cards' worth. Logos are small.
        const val PICTURE_CACHE = 8 * 1024 * 1024
        const val LOGO_CACHE = 2 * 1024 * 1024

        const val ADD = 1
        const val REMOVE = 2
        const val MOVE_LEFT = 3
        const val MOVE_RIGHT = 4
        const val APP_INFO = 5
    }
}
