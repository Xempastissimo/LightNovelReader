package com.xempastissimo.lightnovelreader.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CookieStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private var now = 1_000_000L

    private fun store(file: File = File(folder.root, "cookies.json")) =
        CookieStore(file) { now }

    @Test
    fun `stores and returns a session cookie`() {
        val store = store()
        store.save("https://www.wenku8.net/login.php", listOf("PHPSESSID=abc123; path=/"))
        assertEquals("PHPSESSID=abc123", store.headerFor("https://www.wenku8.net/modules/article/bookcase.php"))
    }

    @Test
    fun `does not leak cookies to unrelated hosts`() {
        val store = store()
        store.save("https://www.wenku8.net/login.php", listOf("PHPSESSID=abc123; path=/"))
        assertNull(store.headerFor("https://example.com/"))
    }

    @Test
    fun `domain attribute cookies reach subdomains`() {
        val store = store()
        store.save("https://www.wenku8.net/", listOf("jieqiVisitInfo=x; Domain=.wenku8.net; Path=/"))
        assertEquals("jieqiVisitInfo=x", store.headerFor("https://pic.wenku8.net/pictures/0/1.jpg"))
    }

    @Test
    fun `host only cookies do not reach subdomains`() {
        val store = store()
        store.save("https://www.wenku8.net/", listOf("session=1; Path=/"))
        assertNull(store.headerFor("https://pic.wenku8.net/x.jpg"))
    }

    @Test
    fun `max age zero removes the cookie`() {
        val store = store()
        store.save("https://www.wenku8.net/", listOf("token=1; Path=/"))
        assertEquals("token=1", store.headerFor("https://www.wenku8.net/a"))
        store.save("https://www.wenku8.net/", listOf("token=; Max-Age=0; Path=/"))
        assertNull(store.headerFor("https://www.wenku8.net/a"))
    }

    @Test
    fun `expired cookies are dropped`() {
        val store = store()
        store.save("https://www.wenku8.net/", listOf("temp=1; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/"))
        assertNull(store.headerFor("https://www.wenku8.net/a"))
    }

    @Test
    fun `context path cookies only match their subtree`() {
        val store = store()
        store.save("https://www.wenku8.net/modules/article/x.php", listOf("scoped=1; Path=/modules/article"))
        assertNull(store.headerFor("https://www.wenku8.net/index.php"))
        assertTrue(store.headerFor("https://www.wenku8.net/modules/article/bookcase.php")!!.contains("scoped=1"))
    }

    @Test
    fun `persists across instances`() {
        val file = File(folder.root, "cookies.json")
        store(file).save("https://www.wenku8.net/", listOf("PHPSESSID=keepme; Path=/"))
        assertEquals("PHPSESSID=keepme", store(file).headerFor("https://www.wenku8.net/"))
    }

    @Test
    fun `login state follows the user info cookie`() {
        val store = store()
        assertFalse(store.isLoggedIn())
        store.save("https://www.wenku8.net/", listOf("jieqiUserInfo=jieqiUserId%3D1; Path=/"))
        assertTrue(store.isLoggedIn())
        store.clear()
        assertFalse(store.isLoggedIn())
    }

    @Test
    fun `imports a raw pasted cookie header`() {
        val store = store()
        store.importRawCookieHeader("PHPSESSID=1; jieqiUserInfo=2; jieqiVisitInfo=3")
        val header = store.headerFor("https://www.wenku8.net/index.php")!!
        assertTrue(header.contains("PHPSESSID=1"))
        assertTrue(header.contains("jieqiUserInfo=2"))
        assertEquals(3, store.snapshot().size)
    }

    @Test
    fun `parses http dates`() {
        assertEquals(1_623_233_894_000L, CookieStore.parseHttpDate("Wed, 09 Jun 2021 10:18:14 GMT"))
        assertEquals(0L, CookieStore.parseHttpDate("Thu, 01 Jan 1970 00:00:00 GMT"))
        assertEquals(0L, CookieStore.parseHttpDate("not a date"))
    }

    @Test
    fun `importing one domain does not wipe another domain`() {
        // Regression: importing used to clear the whole jar, so the last domain
        // (www.wenku8.com, analytics only) erased the session cookies read from
        // www.wenku8.net and the user appeared logged out.
        val store = store()
        store.importRawCookieHeader("PHPSESSID=abc; jieqiUserInfo=xyz", "www.wenku8.net")
        store.importRawCookieHeader("HMACCOUNT=1; Hm_lvt_x=2", "wenku8.net")
        store.importRawCookieHeader("Hm_lpvt_x=3", "www.wenku8.com")

        assertTrue(store.isLoggedIn())
        val header = store.headerFor("https://www.wenku8.net/modules/article/bookcase.php")!!
        assertTrue(header.contains("PHPSESSID=abc"))
        assertTrue(header.contains("jieqiUserInfo=xyz"))
        assertEquals(5, store.snapshot().size)
    }

    /**
     * A re-import *updates* the cookies it carries and leaves the rest of the domain alone.
     *
     * It used to replace the whole domain, which quietly logged the user out: `CookieManager`
     * answers with a different set per URL, so importing `www.wenku8.net` and then the bare
     * `wenku8.net` (a subset) deleted the session the first import had just established, and
     * pasting only `PHPSESSID` by hand deleted the `jieqiUserInfo` beside it.
     */
    @Test
    fun `re-importing the same domain updates its cookies without dropping the others`() {
        val store = store()
        store.importRawCookieHeader("PHPSESSID=old; jieqiUserInfo=keep", "www.wenku8.net")
        store.importRawCookieHeader("PHPSESSID=new", "www.wenku8.net")
        val header = store.headerFor("https://www.wenku8.net/index.php")!!
        assertTrue(header.contains("PHPSESSID=new"))
        assertTrue(header.contains("jieqiUserInfo=keep"))
        assertTrue(store.isLoggedIn())
    }

    @Test
    fun `merging a domain keeps cookies the import does not mention`() {
        val store = store()
        store.replaceForDomain(mapOf("keep" to "1"), "www.wenku8.net")
        store.replaceForDomain(mapOf("added" to "2"), "other.example")
        val snapshot = store.snapshot()
        assertEquals("1", snapshot["keep"])
        assertEquals("2", snapshot["added"])
    }

    @Test
    fun `domain scoped session cookies reach the www host`() {
        val store = store()
        // What CookieManager returns for the bare domain must still be sent to www.
        store.importRawCookieHeader("jieqiUserInfo=xyz", "wenku8.net")
        assertTrue(store.isLoggedIn())
        assertTrue(store.headerFor("https://www.wenku8.net/index.php")!!.contains("jieqiUserInfo=xyz"))
    }

    @Test
    fun `replaceAll clears the jar before importing`() {
        val store = store()
        store.importRawCookieHeader("PHPSESSID=old", "www.wenku8.net")
        store.replaceAll(mapOf("fresh" to "1"), "www.wenku8.net")
        assertEquals(mapOf("fresh" to "1"), store.snapshot())
    }

    @Test
    fun `extracts host and path`() {
        assertEquals("www.wenku8.net", CookieStore.hostOf("https://www.wenku8.net/book/1973.htm"))
        assertEquals("/book/1973.htm", CookieStore.pathOf("https://www.wenku8.net/book/1973.htm?x=1"))
        assertEquals("/", CookieStore.pathOf("https://www.wenku8.net"))
    }
}
