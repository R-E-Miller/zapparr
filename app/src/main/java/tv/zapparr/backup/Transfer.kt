package tv.zapparr.backup

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Moves a backup to or from a phone or computer on the same network, for a box without a file
 * picker (most TV boxes) or a new box: while running, a small web page offers this box's backup to
 * download ([backup] makes it) and takes one to restore ([received] gets it, on the server's
 * thread). Every request must carry [code], shown only on the TV; after [MAX_WRONG] wrong ones it
 * stops by itself, so the code can't be guessed, and calls [stopped].
 */
class Transfer(private val backup: () -> String, private val received: (String) -> Unit, private val stopped: () -> Unit) {

    val code = (1000..9999).random().toString()
    private var server: ServerSocket? = null
    private var wrong = 0

    /** Starts listening; returns the address to open, or null if the box has no network address. */
    fun start(): String? {
        val host = localAddress() ?: return null
        val socket = try {
            ServerSocket(PORT)
        } catch (e: SocketException) {
            ServerSocket(0) // the usual port is taken: any will do
        }
        server = socket
        thread(name = "zapparr-transfer", isDaemon = true) {
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (e: SocketException) {
                    break // stopped
                }
                client.use(::answer)
            }
        }
        return "http://$host:${socket.localPort}/?code=$code"
    }

    fun stop() {
        server?.close()
        server = null
    }

    private fun answer(client: Socket) {
        try {
            client.soTimeout = TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val (method, target) = (readLine(input) ?: return).split(" ").let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
            var length = 0
            while (true) {
                val header = readLine(input) ?: return
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", ignoreCase = true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8") }
            val out = client.getOutputStream()
            fun reply(status: String, type: String, body: String, extra: String = "") {
                val bytes = body.toByteArray()
                out.write("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${bytes.size}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                out.write(bytes)
                out.flush()
            }
            // Browsers ask for an icon on their own, without the code: that's no guess.
            if (path == "/favicon.ico") return reply("404 Not Found", "text/plain; charset=utf-8", "")
            if (query["code"] != code) {
                reply("403 Forbidden", HTML, page("Open the address shown on the TV, with its code."))
                if (++wrong >= MAX_WRONG) {
                    stop()
                    stopped()
                }
                return
            }
            when {
                method == "GET" && path == "/" -> reply("200 OK", HTML, page(null))
                method == "GET" && path == "/backup" ->
                    reply("200 OK", "application/json", backup(), "Content-Disposition: attachment; filename=\"zapparr-backup.json\"\r\n")
                method == "POST" && path == "/restore" && length in 1..MAX_BYTES -> {
                    received(readBody(input, length))
                    reply("200 OK", "text/plain; charset=utf-8", "Sent. Confirm the restore on the TV.")
                }
                else -> reply("404 Not Found", "text/plain; charset=utf-8", "Not here.")
            }
        } catch (e: Exception) { // IOException, a dropped connection, a malformed request
            // Nothing to tell anyone: the browser tries again.
        }
    }

    // The page a phone or computer sees: a download link, and a file to send.
    private fun page(error: String?) = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Zapparr backup</title><style>body{font:16px system-ui;background:#0b0e13;color:#f3f5f8;max-width:560px;margin:40px auto;padding:0 16px}
a,button,label{display:inline-block;margin:12px 0;padding:12px 18px;border-radius:24px;background:#ffc53d;color:#141414;text-decoration:none;font-weight:600}
input{display:none}p{color:#a3acb9}</style></head><body><h1>Zapparr backup</h1>
${if (error != null) "<p>$error</p>" else """<p>Your settings, favorites, channel order and where you left off, from the TV.</p>
<a href="/backup?code=$code">Download a backup</a>
<p>Or send a backup to the TV. It asks before it restores.</p>
<label>Choose a backup to send<input id="f" type="file" accept=".json,application/json"></label><p id="s"></p>
<script>document.getElementById('f').onchange=function(e){var r=new FileReader();r.onload=function(){fetch('/restore?code=$code',{method:'POST',body:r.result}).then(function(x){return x.text()}).then(function(t){document.getElementById('s').textContent=t})};r.readAsText(e.target.files[0])}</script>"""}
</body></html>"""

    private fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (line.size() == 0) null else line.toString("UTF-8")
            if (b == '\n'.code) return line.toString("UTF-8").trimEnd('\r')
            if (line.size() > MAX_LINE) return null
            line.write(b)
        }
    }

    private fun readBody(input: InputStream, length: Int): String {
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) break
            read += n
        }
        return String(body, 0, read, Charsets.UTF_8)
    }

    // The box's address on the home network.
    private fun localAddress(): String? = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    } catch (e: SocketException) {
        null
    }

    private companion object {
        const val PORT = 8765
        const val MAX_WRONG = 10
        const val TIMEOUT_MS = 10_000
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_LINE = 8 * 1024
        const val HTML = "text/html; charset=utf-8"
    }
}
