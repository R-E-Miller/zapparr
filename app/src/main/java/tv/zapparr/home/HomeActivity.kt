package tv.zapparr.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.zapparr.R
import tv.zapparr.apps.AppRepository
import tv.zapparr.apps.AppsDeck
import tv.zapparr.apps.FavoritesStore
import tv.zapparr.apps.openSettings
import tv.zapparr.library.LibraryDeck
import tv.zapparr.library.LibraryStore
import tv.zapparr.live.Catalog
import tv.zapparr.live.GuideDeck
import tv.zapparr.live.GuideStore
import tv.zapparr.live.Lineup
import tv.zapparr.live.ServerActivity
import tv.zapparr.live.ServerKind
import tv.zapparr.live.ServerStore
import tv.zapparr.live.XcClient
import tv.zapparr.live.describe

/** The home screen: a clock bar over three decks, Apps, Guide and Library. */
class HomeActivity : ComponentActivity() {

    private lateinit var decks: DeckPager
    private lateinit var tabs: List<TextView>
    private lateinit var appsDeck: AppsDeck
    private lateinit var repository: AppRepository
    private var loadJob: Job? = null
    private var firstLoad = true

    // The Guide from Dispatcharr, the Library from the library server; each reloads as its server
    // changes or its content ages.
    private lateinit var guideFeed: ServerFeed<Lineup>
    private lateinit var libraryFeed: ServerFeed<Catalog>

    // Installs, updates, removals, and apps enabled or disabled (by the debloat tool too).
    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        repository = AppRepository(this)
        appsDeck = AppsDeck(findViewById(R.id.apps_rows), FavoritesStore(this))
        decks = findViewById(R.id.decks)
        tabs = listOf(R.id.tab_apps, R.id.tab_guide, R.id.tab_library).map { findViewById(it) }
        decks.onDeckChanged = ::showTab
        showTab(decks.current)
        bindTabs()

        findViewById<View>(R.id.settings).apply {
            setOnClickListener { openSettings() }
            setOnKeyListener(downInto { decks.current })
        }

        val guide = GuideDeck(findViewById(R.id.deck_guide), GuideStore(this)) { ServerActivity.open(this, ServerKind.LIVE) }
        guideFeed = ServerFeed(lifecycleScope, ServerStore(this, ServerKind.LIVE), guide, GUIDE_REFRESH, this::describe) { server ->
            val now = System.currentTimeMillis()
            XcClient(server).lineup(now - GUIDE_PAST, now + GUIDE_AHEAD)
        }
        val library = LibraryDeck(findViewById(R.id.deck_library), LibraryStore(this)) { ServerActivity.open(this, ServerKind.LIBRARY) }
        libraryFeed = ServerFeed(lifecycleScope, ServerStore(this, ServerKind.LIBRARY), library, LIBRARY_REFRESH, this::describe) { server ->
            XcClient(server).catalog()
        }

        // Home never closes: Back from the Guide, the Library or the top bar returns to the Apps deck.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (decks.current != Decks.APPS || !decks.hasFocus()) decks.show(Decks.APPS, takeFocus = true)
            }
        })

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        guideFeed.refresh()
        libraryFeed.refresh()
    }

    override fun onDestroy() {
        unregisterReceiver(packageChanges)
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Pressing Home always lands on the first favorite, like a cable box's Menu key.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            decks.show(Decks.APPS, takeFocus = false)
            appsDeck.focusFirst()
        }
    }

    private fun loadApps() {
        loadJob?.cancel()
        loadJob = lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { repository.load() }
            appsDeck.submit(apps)
            if (firstLoad) {
                firstLoad = false
                appsDeck.focusFirst()
            }
        }
    }

    // The tabs work like Google TV's: focusing one shows its deck, and Down or OK goes into it. Up
    // from the top of any deck comes back to its tab, so no deck needs paging through to leave it.
    private fun bindTabs() {
        tabs.forEachIndexed { index, tab ->
            tab.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) decks.show(index, takeFocus = false) }
            tab.setOnClickListener { decks.show(index, takeFocus = true) }
            tab.setOnKeyListener(downInto { index })
        }
        decks.exitUp = { tabs[decks.current] }
    }

    // Down from the top bar goes into the deck where it left off, not to whatever is nearest below.
    private fun downInto(deck: () -> Int) = View.OnKeyListener { _, keyCode, event ->
        if (keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return@OnKeyListener false
        if (event.action == KeyEvent.ACTION_DOWN) decks.show(deck(), takeFocus = true)
        true
    }

    private fun showTab(index: Int) {
        tabs.forEachIndexed { i, tab -> tab.isActivated = i == index }
    }

    private companion object {
        const val GUIDE_PAST = 60 * 60 * 1000L
        const val GUIDE_AHEAD = 12 * 60 * 60 * 1000L
        const val GUIDE_REFRESH = 60 * 60 * 1000L
        // vod-organizer re-syncs with the provider every six hours.
        const val LIBRARY_REFRESH = 6 * 60 * 60 * 1000L
    }
}
