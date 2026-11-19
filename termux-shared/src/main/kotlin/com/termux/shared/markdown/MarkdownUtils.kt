package com.termux.shared.markdown

import android.content.Context
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.BulletSpan
import android.text.style.QuoteSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import android.text.util.Linkify
import androidx.core.content.ContextCompat
import com.google.common.base.Strings
import com.termux.shared.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonSpansFactory
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.SpanFactory
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.util.regex.Matcher
import java.util.regex.Pattern
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.BlockQuote
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.ListItem
import org.commonmark.node.StrongEmphasis

object MarkdownUtils {

    const val backtick: String = "`"
    @JvmField val backticksPattern: Pattern = Pattern.compile("(" + backtick + "+)")

    /**
     * Get the markdown code [String] for a [String]. This ensures all backticks "`" are
     * properly escaped so that markdown does not break.
     *
     * @param string The [String] to convert.
     * @param codeBlock If the [String] is to be converted to a code block or inline code.
     * @return Returns the markdown code [String].
     **/
    @JvmStatic
    fun getMarkdownCodeForString(string: String?, codeBlock: Boolean): String? {
        var string = string
        if (string == null) {
            return null
        }
        if (string.isEmpty()) {
            return ""
        }

        val maxConsecutiveBackTicksCount: Int = getMaxConsecutiveBackTicksCount(string)

        // markdown requires surrounding backticks count to be at least one more than the count
        // of consecutive ticks in the string itself
        var backticksCountToUse: Int
        if (codeBlock) {
            backticksCountToUse = maxConsecutiveBackTicksCount + 3
        } else {
            backticksCountToUse = maxConsecutiveBackTicksCount + 1
        }

        // create a string with n backticks where n==backticksCountToUse
        val backticksToUse: String = Strings.repeat(backtick, backticksCountToUse)

        if (codeBlock) {
            return backticksToUse + "\n" + string + "\n" + backticksToUse
        } else {
            // add a space to any prefixed or suffixed backtick characters
            if (string.startsWith(backtick)) {
                string = " " + string
            }
            if (string!!.endsWith(backtick)) {
                string = string + " "
            }

            return backticksToUse + string + backticksToUse
        }
    }

    /**
     * Get the max consecutive backticks "`" in a [String].
     *
     * @param string The [String] to check.
     * @return Returns the max consecutive backticks count.
     **/
    @JvmStatic
    fun getMaxConsecutiveBackTicksCount(string: String?): Int {
        if (string == null || string.isEmpty()) {
            return 0
        }

        var maxCount = 0
        var matchCount: Int
        var match: String?

        val matcher: Matcher = backticksPattern.matcher(string)
        while (matcher.find()) {
            match = matcher.group(1)
            matchCount = if (match != null) match.length else 0
            if (matchCount > maxCount) {
                maxCount = matchCount
            }
        }

        return maxCount
    }

    @JvmStatic
    fun getSingleLineMarkdownStringEntry(label: String, `object`: Any?, def: String): String {
        if (`object` != null) {
            return "**" + label + "**: " + getMarkdownCodeForString(`object`.toString(), false) + "  "
        } else {
            return "**" + label + "**: " + def + "  "
        }
    }

    @JvmStatic
    fun getMultiLineMarkdownStringEntry(label: String, `object`: Any?, def: String): String {
        if (`object` != null) {
            return "**" + label + "**:\n" + getMarkdownCodeForString(`object`.toString(), true) + "\n"
        } else {
            return "**" + label + "**: " + def + "\n"
        }
    }

    @JvmStatic
    fun getLinkMarkdownString(label: String, url: String?): String {
        if (url != null) {
            return "[" + (label as java.lang.String).replaceAll("]", "\\\\]") + "](" + (url as java.lang.String).replaceAll("\\)", "\\\\)") + ")"
        } else {
            return label
        }
    }

    /** Check following for more info:
     * https://github.com/noties/Markwon/tree/v4.6.2/app-sample
     * https://noties.io/Markwon/docs/v4/recycler/
     * https://github.com/noties/Markwon/blob/v4.6.2/app-sample/src/main/java/io/noties/markwon/app/readme/ReadMeActivity.kt
     **/
    @JvmStatic
    fun getRecyclerMarkwonBuilder(context: Context): Markwon {
        return Markwon.builder(context).usePlugin(LinkifyPlugin.create(Linkify.EMAIL_ADDRESSES or Linkify.WEB_URLS)).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureVisitor(builder: MarkwonVisitor.Builder) {
                builder.on(FencedCodeBlock::class.java, MarkwonVisitor.NodeVisitor<FencedCodeBlock> { visitor, fencedCodeBlock ->
                    val code: CharSequence = visitor.configuration().syntaxHighlight().highlight(fencedCodeBlock.getInfo(), (fencedCodeBlock.getLiteral() as java.lang.String).trim())
                    visitor.builder().append(code)
                })
            }

            override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                builder.setFactory(Code::class.java, SpanFactory { configuration, props ->
                    arrayOf<Any>(BackgroundColorSpan(ContextCompat.getColor(context, R.color.background_markdown_code_inline)))
                })
            }
        }).build()
    }

    /** Check following for more info:
     * https://github.com/noties/Markwon/tree/v4.6.2/app-sample
     * https://github.com/noties/Markwon/blob/v4.6.2/app-sample/src/main/java/io/noties/markwon/app/samples/notification/NotificationSample.java
     **/
    @JvmStatic
    fun getSpannedMarkwonBuilder(context: Context): Markwon {
        return Markwon.builder(context).usePlugin(StrikethroughPlugin.create()).usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureSpansFactory(builder: MarkwonSpansFactory.Builder) {
                builder.setFactory(Emphasis::class.java, SpanFactory { configuration, props -> StyleSpan(Typeface.ITALIC) }).setFactory(StrongEmphasis::class.java, SpanFactory { configuration, props -> StyleSpan(Typeface.BOLD) }).setFactory(BlockQuote::class.java, SpanFactory { configuration, props -> QuoteSpan() }).setFactory(Strikethrough::class.java, SpanFactory { configuration, props -> StrikethroughSpan() }).setFactory(Code::class.java, SpanFactory { configuration, props ->
                    arrayOf<Any>(BackgroundColorSpan(ContextCompat.getColor(context, R.color.background_markdown_code_inline)), TypefaceSpan("monospace"), AbsoluteSizeSpan(48))
                }).setFactory(ListItem::class.java, SpanFactory { configuration, props -> BulletSpan() })
            }
        }).build()
    }

    @JvmStatic
    fun getSpannedMarkdownText(context: Context?, string: String?): Spanned? {
        if (context == null || string == null) {
            return null
        }
        val markwon: Markwon = getSpannedMarkwonBuilder(context)
        return markwon.toMarkdown(string)
    }
}
