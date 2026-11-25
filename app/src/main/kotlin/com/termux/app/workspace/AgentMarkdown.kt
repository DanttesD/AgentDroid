package com.termux.app.workspace

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.LinkResolver
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.linkify.LinkifyPlugin
import java.util.Arrays
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.Paragraph
import org.commonmark.node.Text
import org.commonmark.parser.Parser

/** Native CommonMark rendering; no WebView, remote image loading or command execution.*/
object AgentMarkdown {
    @JvmStatic
    fun create(c: Context): Markwon {
        return Markwon.builder(c).usePlugin(StrikethroughPlugin.create()).usePlugin(LinkifyPlugin.create()).usePlugin(object : AbstractMarkwonPlugin() {
            override fun beforeRender(node: Node) {
                node.accept(object : AbstractVisitor() {
                    override fun visit(text: Text) {
                        if (text.getParent() is Paragraph && text.getParent().getParent() is ListItem && text.getParent().getFirstChild() === text) {
                            val value: String = text.getLiteral()
                            if (value.startsWith("[x] ") || value.startsWith("[X] ")) {
                                text.setLiteral("☑ " + value.substring(4))
                            } else if (value.startsWith("[ ] ")) {
                                text.setLiteral("☐ " + value.substring(4))
                            }
                        }
                    }
                })
            }

            override fun configureParser(builder: Parser.Builder) {
                builder.extensions(Collections.singletonList(TablesExtension.create()))
            }

            override fun configureTheme(b: MarkwonTheme.Builder) {
                b.linkColor(Pal.ACCENT).blockQuoteColor(Pal.ACCENT).blockQuoteWidth(3).codeTextColor(0xffbdddd2.toInt()).codeBackgroundColor(0xff14252b.toInt()).codeBlockBackgroundColor(Pal.PANEL).headingBreakHeight(0).headingTextSizeMultipliers(floatArrayOf(1.5f, 1.35f, 1.2f, 1.1f, 1.05f, 1f))
            }

            override fun configureConfiguration(b: MarkwonConfiguration.Builder) {
                b.linkResolver(LinkResolver { view, url -> openLink(view, url) })
            }
        }).build()
    }

    @JvmStatic
    fun openLink(view: View, url: String) {
        try {
            val uri: Uri = Uri.parse(url)
            if (!("https" as java.lang.String).equalsIgnoreCase(uri.getScheme()) && !("http" as java.lang.String).equalsIgnoreCase(uri.getScheme())) {
                return
            }
            val i = Intent(Intent.ACTION_VIEW, uri)
            if (!(view.getContext() is Activity)) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            view.getContext().startActivity(i)
        } catch (ignored: ActivityNotFoundException) {
        } catch (ignored: IllegalArgumentException) {
        }
    }

    @JvmStatic
    fun checkboxes(source: String): String {
        return source
    }

    @JvmStatic
    fun rich(source: String): Boolean {
        return source.contains("```") || source.contains("~~~") || Pattern.compile("(?m)^(?: {4}|\\t|#{1,6}\\s| {0,3}>|.*\\|.*\\n[ |:-]+\\|)").matcher(source).find()
    }

    @JvmStatic
    fun highlight(code: String, language: String): SpannableStringBuilder {
        val s = SpannableStringBuilder(code)
        if (code.length > 60000) {
            return s
        }
        val lang: String = (language as java.lang.String).toLowerCase(Locale.ROOT)
        if (!Arrays.asList("python", "py", "javascript", "js", "typescript", "ts", "java", "kotlin", "kt", "bash", "sh", "shell", "json", "c", "cpp", "rust", "rs").contains(lang)) {
            return s
        }
        val keywords: MutableSet<String> = HashSet<String>(Arrays.asList("from", "import", "def", "class", "return", "if", "else", "elif", "for", "while", "try", "catch", "except", "finally", "const", "let", "var", "function", "public", "private", "static", "void", "new", "true", "false", "null", "None", "True", "False", "fn", "use", "val", "fun", "echo", "export", "then", "fi", "do", "done"))
        var i = 0
        while (i < code.length) {
            val start: Int = i
            var color = 0
            val ch: Char = code[i]
            if (ch == '#' || ch == '/' && i + 1 < code.length && code[i + 1] == '/') {
                while (i < code.length && code[i] != '\n') {
                    i++
                }
                color = 0xff718e99.toInt()
            } else if (ch == '"' || ch == '\'') {
                i++
                while (i < code.length) {
                    val c: Char = code[i++]
                    if (c == '\\' && i < code.length) {
                        i++
                    } else if (c == ch) {
                        break
                    }
                }
                color = 0xffdfc08b.toInt()
            } else if (Character.isJavaIdentifierStart(ch)) {
                i++
                while (i < code.length && Character.isJavaIdentifierPart(code[i])) {
                    i++
                }
                if (keywords.contains(code.substring(start, i))) {
                    color = 0xff8bc7e5.toInt()
                }
            } else if (Character.isDigit(ch)) {
                i++
                while (i < code.length && (Character.isDigit(code[i]) || code[i] == '.')) {
                    i++
                }
                color = 0xffc5a6de.toInt()
            } else {
                i++
            }
            if (color != 0) {
                s.setSpan(ForegroundColorSpan(color), start, i, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return s
    }
}
