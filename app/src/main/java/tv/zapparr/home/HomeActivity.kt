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

/** The home screen: a clock bar over three decks, Apps, Guide and Library. */
class HomeActivity : ComponentActivity() {

    private lateinit var decks: DeckPager
    private lateinit var tabs: List<TextView>
    private lateinit var appsDeck: AppsDeck
    private lateinit var repository: AppRepository
    private var loadJob: Job? = null
    private var firstLoad = true

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
            bindPlaceholderActions(apps)
            if (firstLoad) {
                firstLoad = false
                appsDeck.focusFirst()
            }
        }
    }

    // Until the Guide and Library arrive in Phase 2, both decks open TiviMate if it's installed.
    private fun bindPlaceholderActions(apps: List<AppEntry>) {
        val tivimate = apps.firstOrNull { it.packageName == TIVIMATE }
        for (id in intArrayOf(R.id.guide_action, R.id.library_action)) {
            val button = findViewById<Button>(id)
            if (tivimate != null) {
                button.text = getString(R.string.open_app, tivimate.label)
                button.setOnClickListener { openApp(tivimate) }
            } else {
                button.setText(R.string.coming_soon)
                button.setOnClickListener(null)
            }
        }
    }

    private fun showTab(index: Int) {
        tabs.forEachIndexed { i, tab -> tab.isActivated = i == index }
    }

    private companion object {
        const val TIVIMATE = "ar.tvplayer.tv"
    }
}
