package tv.zapparr.apps

import android.content.Context
import android.view.View
import android.widget.PopupMenu
import androidx.leanback.widget.VerticalGridView
import tv.zapparr.R

/** The Apps deck: a Favorites row on top, then every launchable app, six to a row. */
class AppsDeck(private val rows: VerticalGridView, private val store: FavoritesStore) {

    private val context: Context get() = rows.context
    private var apps: List<AppEntry> = emptyList()
    private var favorites: List<String> = emptyList()
    private val adapter = RowsAdapter(onOpen = { context.openApp(it) }, onMenu = ::showMenu)

    init {
        rows.adapter = adapter
    }

    fun submit(installed: List<AppEntry>) {
        apps = installed
        favorites = Favorites.visible(store.saved(), installed.map { it.packageName })
        rebuild()
    }

    /** Focuses the first favorite, or the first app when there are none, as Home does. */
    fun focusFirst() {
        val row = if (favorites.isEmpty()) FIRST_APPS_ROW else FAVORITES_ROW
        if (row >= adapter.itemCount) return
        adapter.selectTile(rows, row, 0)
        rows.selectedPosition = row
        rows.requestFocus()
    }

    private fun rebuild() {
        val byPackage = apps.associateBy { it.packageName }
        // Favorites always comes first, even when empty, so the rows below never change index.
        val list = mutableListOf(
            TileRow(
                title = context.getString(R.string.favorites),
                apps = favorites.mapNotNull { byPackage[it] },
                hint = context.getString(R.string.favorites_hint),
            ),
        )
        apps.chunked(COLUMNS).forEachIndexed { i, chunk ->
            list += TileRow(title = if (i == 0) context.getString(R.string.all_apps) else null, apps = chunk)
        }
        adapter.submit(list)
    }

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
        const val FIRST_APPS_ROW = 1

        const val ADD = 1
        const val REMOVE = 2
        const val MOVE_LEFT = 3
        const val MOVE_RIGHT = 4
        const val APP_INFO = 5
    }
}
