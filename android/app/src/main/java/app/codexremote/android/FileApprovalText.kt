package app.codexremote.android

import android.graphics.Color
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import org.json.JSONObject

/** Style the exact review text without trimming, reordering, or hiding any patch lines. */
internal object FileApprovalText {
    fun style(detail: String, params: JSONObject, dark: Boolean): CharSequence {
        val result = SpannableString(detail)
        val changes = params.optJSONObject("fileChangeReview")?.optJSONArray("changes") ?: return result
        val added = if (dark) Color.rgb(74, 222, 128) else Color.rgb(20, 108, 46)
        val removed = if (dark) Color.rgb(248, 113, 113) else Color.rgb(179, 38, 30)
        var cursor = 0
        for (index in 0 until changes.length()) {
            val change = changes.optJSONObject(index) ?: continue
            val patch = change.optString("diff")
            if (patch.isBlank()) continue
            val title = "${change.optString("kind").replaceFirstChar(Char::uppercase)}: ${change.optString("path")}" +
                change.optString("movePath").takeIf(String::isNotBlank)?.let { " → $it" }.orEmpty()
            val titleStart = detail.indexOf(title, cursor)
            if (titleStart < 0) continue
            val start = detail.indexOf(patch, titleStart + title.length)
            if (start < 0) continue
            val end = start + patch.length
            result.setSpan(StyleSpan(Typeface.BOLD), titleStart, titleStart + title.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            result.setSpan(TypefaceSpan("monospace"), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            result.setSpan(RelativeSizeSpan(0.85f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            var lineStart = start
            patch.split('\n').forEach { line ->
                val color = when {
                    line.startsWith("@@") || line.startsWith("+++") || line.startsWith("---") || line.startsWith("Moved to:") -> null
                    line.startsWith('+') || change.optString("kind") == "add" -> added
                    line.startsWith('-') || change.optString("kind") == "delete" -> removed
                    else -> null
                }
                if (color != null && line.isNotEmpty()) result.setSpan(ForegroundColorSpan(color), lineStart, lineStart + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                lineStart += line.length + 1
            }
            cursor = end
        }
        return result
    }
}
