package com.example.llmapp.ui.chat.composables.cards

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext

/**
 * High performance Markwon Android Interop container enabling full markdown parsing with table/strikethrough plugins.
 */
@Composable
fun MarkwonText(markdown: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val markwon = remember(context) {
        io.noties.markwon.Markwon.builder(context)
            .usePlugin(io.noties.markwon.ext.strikethrough.StrikethroughPlugin.create())
            .usePlugin(io.noties.markwon.ext.tables.TablePlugin.create(context))
            .build()
    }
    val surfaceColor = MaterialTheme.colorScheme.onSurface
    val bodyLargeSize = MaterialTheme.typography.bodyLarge.fontSize.value
    val lastRenderedContent = remember { mutableStateOf("") }

    androidx.compose.ui.viewinterop.AndroidView(
        factory = { ctx ->
            android.widget.TextView(ctx).apply {
                setTextColor(surfaceColor.toArgb())
                textSize = bodyLargeSize
                setLineSpacing(4f, 1.1f)
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        },
        update = { view ->
            if (lastRenderedContent.value != markdown) {
                lastRenderedContent.value = markdown
                markwon.setMarkdown(view, markdown)
            }
        },
        modifier = modifier.fillMaxWidth()
    )
}
