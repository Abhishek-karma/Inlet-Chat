package com.assistant.app.ui.components

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.assistant.app.R
import kotlin.math.ceil

/** Mermaid renderer over the bundled engine, hardened as far as it can be. */

private const val REPORT_JS =
    "function report(){var c=document.getElementById('c');if(c&&window.Android){" +
        "var r=c.getBoundingClientRect();if(r.height>0)Android.setHeight(r.height);}}" +
        "if(window.ResizeObserver){new ResizeObserver(report).observe(document.getElementById('c'));}"

private fun rendererHtml(scripts: String, body: String, onReadyJs: String): String =
    """<!DOCTYPE html><html><head><meta charset="utf-8">""" +
        """<meta name="viewport" content="width=device-width,initial-scale=1">""" +
        """<style>html,body{margin:0;padding:0;background:transparent;overflow:hidden;}""" +
        """#c{padding:8px;}</style>$scripts</head><body><div id="c">$body</div>""" +
        """<script>$REPORT_JS$onReadyJs</script></body></html>"""

private fun htmlEscape(raw: String): String = raw
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")

internal fun diagramDocument(code: String, htmlLabels: Boolean = true): String =
    rendererHtml(
        scripts = "<script src=\"file:///android_asset/diagram/mermaid.min.js\"></script>",
        body = "<pre class=\"mermaid\">" + htmlEscape(code) + "</pre>",
        onReadyJs = "try{mermaid.initialize({startOnLoad:false,theme:'neutral'," +
            "suppressErrorRendering:true" +
            (if (htmlLabels) "" else ",flowchart:{htmlLabels:false},class:{htmlLabels:false},er:{htmlLabels:false}") +
            "});var pre=document.querySelector('.mermaid');" +
            "var src=pre.textContent;mermaid.run({querySelector:'.mermaid'}).then(" +
            "function(){report();setTimeout(report,400);},function(){pre.textContent=src;" +
            "report();setTimeout(report,400);});}catch(e){report();setTimeout(report,400);}" +
            "report();setTimeout(report,500);",
    )

@Composable
fun DiagramBlock(code: String, modifier: Modifier = Modifier) {
    var viewerOpen by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth()) {
            RichBlockWebView(
                html = diagramDocument(code),
                modifier = Modifier.clickable { viewerOpen = true },
            )
            Text(
                text = "mermaid",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
    }
    if (viewerOpen) {
        DiagramViewerDialog(code = code, onDismiss = { viewerOpen = false })
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun RichBlockWebView(html: String, modifier: Modifier = Modifier) {
    var contentHeight by remember { mutableIntStateOf(0) }
    key(html) {
        AndroidView(
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .then(
                    if (contentHeight > 0) {
                        Modifier.height(contentHeight.dp)
                    } else {
                        Modifier.heightIn(min = 48.dp)
                    },
                ),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.allowFileAccess = true
                    settings.allowContentAccess = false
                    settings.domStorageEnabled = false
                    settings.databaseEnabled = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.setGeolocationEnabled(false)
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false
                    webViewClient = WebViewClient()
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun setHeight(height: Float) {
                                // JS bridges run off the main thread; state
                                // writes must not happen here.
                                post { contentHeight = ceil(height).toInt() }
                            }
                        },
                        "Android",
                    )
                    loadDataWithBaseURL(
                        "file:///android_asset/",
                        html,
                        "text/html",
                        "utf-8",
                        null,
                    )
                }
            },
            onRelease = { view ->
                view.stopLoading()
                view.destroy()
            },
        )
    }
}
