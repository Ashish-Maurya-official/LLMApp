package com.example.llmapp.ui.chat.utils

import androidx.compose.foundation.lazy.LazyListState

/**
 * Returns true when the user is within [thresholdPx] pixels of the list bottom.
 */
fun LazyListState.isNearBottom(thresholdPx: Int = 100): Boolean {
    val layoutInfo = layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0) return true
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull() ?: return true
    if (lastVisible.index < totalItems - 1) return false
    val viewportBottom = layoutInfo.viewportEndOffset
    return (lastVisible.offset + lastVisible.size) >= (viewportBottom - thresholdPx)
}
