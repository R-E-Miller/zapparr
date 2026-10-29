package tv.zapparr.live

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.zapparr.R

/**
 * Where a server's address and login are entered: Dispatcharr's, or the library server's (see
 * [ServerKind]). Saves them only once the server accepts them.
 */
class ServerActivity : ComponentActivity() {

    private lateinit var kind: ServerKind
    private lateinit var store: ServerStore
    private lateinit var address: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var connect: Button
    private var connecting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_server)

        kind = ServerKind.entries.firstOrNull { it.name == intent.getStringExtra(EXTRA_KIND) } ?: ServerKind.LIVE
        store = ServerStore(this, kind)
        findViewById<TextView>(R.id.server_heading).setText(kind.title)
        findViewById<TextView>(R.id.server_help_text).setText(kind.help)
        findViewById<TextView>(R.id.address_label).setText(kind.addressLabel)
        findViewById<TextView>(R.id.password_label).setText(kind.passwordLabel)
        address = findViewById(R.id.address)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        status = findViewById(R.id.status)
        connect = findViewById(R.id.connect)
        val forget = findViewById<Button>(R.id.forget)

        val saved = store.load()
        if (saved != null) {
            address.setText(saved.address)
            username.setText(saved.username)
            password.setText(saved.password)
            forget.visibility = View.VISIBLE
            forget.setOnClickListener {
                store.clear()
                finish()
            }
            connect.requestFocus()
        } else {
            address.requestFocus()
        }

        connect.setOnClickListener { connect() }
        password.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) connect()
            false // let the keyboard close
        }
    }

    private fun connect() {
        if (connecting) return
        val base = Server.normalizeAddress(address.text.toString(), kind.defaultPort)
            ?: return showError(getString(R.string.server_bad_address), address)
        val server = Server(base, username.text.toString().trim(), password.text.toString())
        if (server.username.isEmpty()) return showError(getString(R.string.server_no_username), username)

        connecting = true
        connect.isEnabled = false
        status.setTextColor(getColor(R.color.text_secondary))
        status.text = getString(R.string.server_connecting, base)
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) {
                try {
                    XcClient(server).login()
                    null
                } catch (e: XcException) {
                    e.problem
                } catch (e: Exception) { // IOException while reading the reply
                    Problem.UNREACHABLE
                }
            }
            connecting = false
            connect.isEnabled = true
            if (problem == null) {
                store.save(server)
                finish()
            } else {
                showError(describe(problem, base), connect)
            }
        }
    }

    private fun showError(message: String, focus: View) {
        status.setTextColor(getColor(R.color.error))
        status.text = message
        focus.requestFocus()
    }

    companion object {
        private const val EXTRA_KIND = "kind"

        fun open(context: Context, kind: ServerKind) =
            context.startActivity(Intent(context, ServerActivity::class.java).putExtra(EXTRA_KIND, kind.name))
    }
}

/** [problem] in words, for a server at [address]. */
fun Context.describe(problem: Problem, address: String): String = when (problem) {
    Problem.UNREACHABLE -> getString(R.string.problem_unreachable, address)
    Problem.NOT_XC -> getString(R.string.problem_not_xc, address)
    Problem.WRONG_LOGIN -> getString(R.string.problem_wrong_login)
    Problem.ACCOUNT_INACTIVE -> getString(R.string.problem_account_inactive)
}
