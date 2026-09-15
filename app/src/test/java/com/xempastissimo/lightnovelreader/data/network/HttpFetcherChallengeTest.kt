package com.xempastissimo.lightnovelreader.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a response body is the site's bot-check interstitial.
 *
 * This matters twice over: a normal page that happens to contain the word "challenge" must not
 * be mistaken for one (the app would send the user to a browser check for content it already
 * has), and the real interstitial must not be mistaken for a page — parsed as content it shows
 * up as an empty book list, which is the failure mode that made discovery look broken.
 *
 * The interstitial is ~28 KB, so **size is not the signal**: the markers are the site's own
 * script names, which is what is asserted here.
 */
class HttpFetcherChallengeTest {

    @Test
    fun `the interstitial's own markers are recognised`() {
        assertTrue(HttpUrlConnectionFetcher.looksLikeChallenge("<title>Just a moment...</title>"))
        assertTrue(HttpUrlConnectionFetcher.looksLikeChallenge("<script>window.cf_chl_opt = {};</script>"))
        assertTrue(HttpUrlConnectionFetcher.looksLikeChallenge("<div id=\"cf-browser-verification\"></div>"))
        assertTrue(HttpUrlConnectionFetcher.looksLikeChallenge("Checking your browser before accessing"))
        assertTrue(HttpUrlConnectionFetcher.looksLikeChallenge("Enable JavaScript and cookies to continue"))
    }

    @Test
    fun `ordinary pages are not`() {
        assertFalse(HttpUrlConnectionFetcher.looksLikeChallenge(""))
        assertFalse(HttpUrlConnectionFetcher.looksLikeChallenge("   "))
        assertFalse(
            HttpUrlConnectionFetcher.looksLikeChallenge(
                "<html><head><title>测试小说 - 作者甲 - 测试文库 - 轻小说文库</title></head>" +
                    "<body><div id=\"content\">内容简介：正文</div></body></html>",
            ),
        )
        // A chapter that merely talks about a challenge is still a chapter.
        assertFalse(
            HttpUrlConnectionFetcher.looksLikeChallenge(
                "<div id=\"content\">他向这个 challenge 发起了挑战。<br>第二段<br></div>",
            ),
        )
    }

    @Test
    fun `the marker is found regardless of case`() {
        assertTrue(HttpUrlConnectionFetcher.looksLikeChallenge("<TITLE>JUST A MOMENT...</TITLE>"))
        assertTrue(HttpUrlConnectionFetcher.looksLikeChallenge("<script>CF_CHL_OPT</script>"))
    }
}
