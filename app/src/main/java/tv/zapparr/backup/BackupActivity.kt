package tv.zapparr.backup

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import java.io.IOException
import tv.zapparr.R
import tv.zapparr.look.applyLook

/**
 * Backup and restore, as TiviMate has it: back up now (to Zapparr's own storage and to
 * Downloads/Zapparr), whether the server logins go along, a phone or computer on the same Wi-Fi
 * ([Transfer]), and the backups on this box to restore from. A restore asks first.
 */
class BackupActivity : ComponentActivity() {

    private lateinit var backups: Backups
    private lateinit var settings: BackupSettings
    private lateinit var rows: LinearLayout
    private var transfer: Transfer? = null
    private var address: String? = null
    private var stoppedForWrongCodes = false

    // On Android 10 to 12, backups Zapparr didn't save itself (before it was installed again) need this.
    private val readStorage = registerForActivityResult(ActivityResultContracts.RequestPermission()) { showRows("storage") }

    override fun onCreate(savedInstanceState: Bundle?) {
        applyLook()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_backup)
        backups = Backups(this)
        settings = BackupSettings(this)
        rows = findViewById(R.id.backup_rows)
        showRows("save")
    }

    override fun onDestroy() {
        transfer?.stop()
        super.onDestroy()
    }

    private fun showRows(focus: String) {
        rows.removeAllViews()
        row("save", getString(R.string.backup_save), getString(R.string.backup_save_hint)) { save() }
        row("logins", getString(R.string.backup_logins), getString(R.string.backup_logins_hint),
            getString(if (settings.withLogins) R.string.value_include else R.string.value_leave_out)) {
            settings.withLogins = !settings.withLogins
            showRows("logins")
        }
        val transferHint = when {
            address != null -> getString(R.string.backup_transfer_on, address)
            stoppedForWrongCodes -> getString(R.string.backup_transfer_stopped)
            else -> getString(R.string.backup_transfer_hint)
        }
        row("transfer", getString(R.string.backup_transfer), transferHint, getString(if (transfer != null) R.string.value_on else R.string.value_off)) {
            toggleTransfer()
        }
        if (Build.VERSION.SDK_INT in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
        ) {
            row("storage", getString(R.string.backup_find_more), getString(R.string.backup_find_more_hint)) {
                readStorage.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        heading(getString(R.string.backup_restore_heading))
        val found = backups.list()
        if (found.isEmpty()) heading(getString(R.string.backup_none), dim = true)
        for (backup in found) {
            val title = DateUtils.formatDateTime(this, backup.created, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_YEAR)
            val where = getString(if (backup.file != null) R.string.backup_on_box else R.string.backup_in_downloads)
            row(backup.name, title, where, getString(R.string.backup_restore)) {
                try {
                    confirmRestore(backups.read(backup))
                } catch (e: IOException) {
                    failed(e.message)
                }
            }
        }
        (rows.findViewWithTag<View>(focus) ?: rows.getChildAt(0)).requestFocus()
    }

    private fun save() {
        val name = try {
            backups.save(settings.withLogins)
        } catch (e: IOException) {
            return failed(e.message)
        }
        Toast.makeText(this, getString(R.string.backup_saved, name), Toast.LENGTH_LONG).show()
        showRows("save")
    }

    private fun toggleTransfer() {
        val running = transfer
        if (running != null) {
            running.stop()
            transfer = null
            address = null
            return showRows("transfer")
        }
        stoppedForWrongCodes = false
        val started = Transfer(
            backup = { backups.snapshot(settings.withLogins) },
            received = { json -> runOnUiThread { confirmRestore(json) } },
            stopped = {
                runOnUiThread {
                    transfer = null
                    address = null
                    stoppedForWrongCodes = true
                    showRows("transfer")
                }
            },
        )
        address = started.start()
        if (address == null) {
            Toast.makeText(this, R.string.backup_transfer_no_network, Toast.LENGTH_LONG).show()
        } else {
            transfer = started
        }
        showRows("transfer")
    }

    // A restore replaces everything it holds, so it asks first, and says whether the logins go too.
    private fun confirmRestore(json: String) {
        val logins = try {
            Backup.hasLogins(json)
        } catch (e: BackupException) {
            return failed(e.message)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.backup_confirm_title)
            .setMessage(if (logins) R.string.backup_confirm_logins else R.string.backup_confirm)
            .setPositiveButton(R.string.backup_restore) { _, _ ->
                try {
                    backups.restore(json)
                    Toast.makeText(this, R.string.backup_restored, Toast.LENGTH_LONG).show()
                    finish()
                } catch (e: BackupException) {
                    failed(e.message)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun failed(why: String?) = Toast.makeText(this, getString(R.string.backup_failed, why.orEmpty()), Toast.LENGTH_LONG).show()

    private fun row(tag: String, title: String, hint: String, value: String? = null, action: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_option, rows, false)
        row.tag = tag
        row.findViewById<TextView>(R.id.option_title).text = title
        row.findViewById<TextView>(R.id.option_hint).text = hint
        row.findViewById<TextView>(R.id.option_value).text = value
        row.setOnClickListener { action() }
        rows.addView(row)
    }

    private fun heading(text: String, dim: Boolean = false) {
        val view = LayoutInflater.from(this).inflate(R.layout.item_heading, rows, false) as TextView
        view.text = text
        if (dim) view.alpha = DIM
        rows.addView(view)
    }

    private companion object {
        const val DIM = 0.7f
    }
}
