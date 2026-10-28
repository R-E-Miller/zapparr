package tv.zapparr.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Button
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
import tv.zapparr.apps.AppEntry
import tv.zapparr.apps.AppRepository
import tv.zapparr.apps.AppsDeck
import tv.zapparr.apps.FavoritesStore
import tv.zapparr.apps.openApp
import tv.zapparr.apps.openSettings
import tv.zapparr.live.Lineup
import tv.zapparr.live.Problem
import tv.zapparr.live.Server
import tv.zapparr.live.ServerActivity
import tv.zapparr.live.ServerStore
import tv.zapparr.live.XcClient
import tv.zapparr.live.XcException
import tv.zapparr.live.describe

/** The home screen: a clock bar over three decks, Apps, Guide and Library. */
class HomeActivity : ComponentActivity() {

    private lateinit var decks: DeckPager
    private lateinit var tabs: List<TextView>
    private lateinit var appsDeck: AppsDeck
    private lateinit var repository: AppRepository
    private var loadJob: Job? = null
    private var firstLoad = true

    private lateinit var servers: ServerStore
    private lateinit var guideBody: TextView
    private lateinit var guideAction: Button
    private var lineupJob: Job? = null
    // The server the Guide shows, and its channels once loaded. A failed load is retried on the next resume.
    private var lineupShown = false
    private var lineupServer: Server? = null
    private var lineup: Lineup? = null
    private var lineupFailed = false

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

        findViewById<View>(R.id.settings).setOnClickListener { openSettings() }

        servers = ServerStore(this)
        guideBody = findViewById(R.id.guide_body)
        guideAction = findViewById(R.id.guide_action)
        guideAction.setOnClickListener { startActivity(Intent(this, ServerActivity::class.java)) }

        // Home never closes: Back from the Guide or Library returns to Apps.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (decks.current != Decks.APPS) decks.show(Decks.APPS, takeFocus = true)
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
        loadLineup()
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
            bindLibraryAction(apps)
            if (firstLoad) {
                firstLoad = false
                appsDeck.focusFirst()
            }
        }
    }

    // Until the Library arrives, its deck opens TiviMate if it's installed.
    private fun bindLibraryAction(apps: List<AppEntry>) {
        val tivimate = apps.firstOrNull { it.packageName == TIVIMATE }
        val button = findViewById<Button>(R.id.library_action)
        if (tivimate != null) {
            button.text = getString(R.string.open_app, tivimate.label)
            button.setOnClickListener { openApp(tivimate) }
        } else {
            button.setText(R.string.coming_soon)
            button.setOnClickListener(null)
        }
    }

    // Loads channels and the guide when the saved server changes (or the last try failed).
    private fun loadLineup() {
        val server = servers.load()
        if (lineupShown && server == lineupServer && !lineupFailed) return
        lineupJob?.cancel()
        lineupShown = true
        lineupServer = server
        lineup = null
        lineupFailed = false
        if (server == null) {
            guideBody.setText(R.string.guide_not_set_up)
            guideAction.setText(R.string.guide_connect)
            return
        }
        guideBody.text = getString(R.string.guide_loading, server.address)
        guideAction.setText(R.string.guide_server)
        lineupJob = lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    XcClient(server).lineup(now - GUIDE_PAST, now + GUIDE_AHEAD)
                } catch (e: XcException) {
                    e.problem
                } catch (e: Exception) { // IOException while reading a reply
                    Problem.UNREACHABLE
                }
            }
            if (result is Lineup) {
                lineup = result
                val guided = result.channels.count { it.epgId in result.programs }
                guideBody.text = resources.getQuantityString(R.plurals.guide_loaded, result.channels.size, result.channels.size, guided)
            } else {
                lineupFailed = true
                guideBody.text = describe(result as Problem, server.address)
            }
        }
    }

    private fun showTab(index: Int) {
        tabs.forEachIndexed { i, tab -> tab.isActivated = i == index }
    }

    private companion object {
        const val TIVIMATE = "ar.tvplayer.tv"
        const val GUIDE_PAST = 60 * 60 * 1000L
        const val GUIDE_AHEAD = 12 * 60 * 60 * 1000L
    }
}
