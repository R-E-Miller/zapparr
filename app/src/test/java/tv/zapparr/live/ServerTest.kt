package tv.zapparr.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerTest {

    @Test
    fun aBareAddressGetsHttpAndDispatcharrsPort() {
        assertEquals("http://192.168.1.20:9191", Server.normalizeAddress("192.168.1.20"))
        assertEquals("http://nas:9191", Server.normalizeAddress(" nas/ "))
    }

    @Test
    fun aTypedPortOrSchemeIsKept() {
        assertEquals("http://192.168.1.20:8080", Server.normalizeAddress("192.168.1.20:8080"))
        assertEquals("https://tv.example.com", Server.normalizeAddress("HTTPS://tv.example.com/"))
        assertEquals("http://tv.example.com", Server.normalizeAddress("http://tv.example.com"))
    }

    @Test
    fun aPathBehindAReverseProxyIsKept() {
        assertEquals("https://example.com/dispatcharr", Server.normalizeAddress("https://example.com/dispatcharr/"))
    }

    @Test
    fun anythingElseIsRefused() {
        for (typed in listOf("", "  ", "ftp://nas", "http://", "nas name", "http://nas:9191/?x=1", "http://me:pw@nas")) {
            assertNull(typed, Server.normalizeAddress(typed))
        }
    }
}
