package com.vizvag.shieldvideo.ui.browser

/**
 * One-shot intents for returning to the NAS browser from Music / Radio / Live TV
 * (share selection or Search on the shared rail).
 */
object BrowseNavRequests {
    @Volatile
    private var pendingShare: String? = null

    @Volatile
    private var pendingOpenSearch: Boolean = false

    fun requestShare(share: String) {
        pendingShare = share
    }

    fun requestOpenSearch() {
        pendingOpenSearch = true
    }

    fun takeShare(): String? {
        val share = pendingShare
        pendingShare = null
        return share
    }

    fun takeOpenSearch(): Boolean {
        val open = pendingOpenSearch
        pendingOpenSearch = false
        return open
    }
}
