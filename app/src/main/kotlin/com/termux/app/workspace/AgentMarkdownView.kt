package com.termux.app.workspace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import io.noties.markwon.Markwon
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Text

/** Reuses stable block views while the final Markdown block streams.*/
class AgentMarkdownView : LinearLayout {
    private val markdown: Markwon?
    private val signatures: ArrayList<String> = ArrayList<String>()
    private val blocks: ArrayList<View> = ArrayList<View>()
    private val author: TextView

    constructor(c: Context) : this(c, AgentMarkdown.create(c))

    constructor(c: Context, renderer: Markwon?) : super(c) {
        setOrientation(LinearLayout.VERTICAL)
        markdown = renderer
        author = text(12)
        author.setTextColor(Pal.ACCENT)
        addView(author)
    }

    private fun dp(n: Int): Int {
        return WorkspaceDialogs.dp(getContext(), n.toFloat())
    }

    private fun text(size: Int): TextView {
        val v = TextView(getContext())
        v.setTextColor(Pal.TEXT2)
        v.setTextSize(size.toFloat())
        v.setPadding(dp(10), dp(6), dp(10), dp(6))
        return v
    }

    private fun background(): GradientDrawable {
        val b = GradientDrawable()
        b.setColor(Pal.PANEL)
        b.setCornerRadius(dp(12).toFloat())
        b.setStroke(dp(1), 0xff30454e.toInt())
        return b
    }

    fun update(name: String, source: String, more: Runnable?) {
        author.setText(name)
        val value: String = AgentMarkdown.checkboxes(source)
        val document: Node = markdown!!.parse(value)
        val nodes: MutableList<Node> = ArrayList<Node>()
        var n: Node? = document.getFirstChild()
        while (n != null) {
            nodes.add(n)
            n = n.getNext()
        }
        var i = 0
        while (i < nodes.size) {
            val n: Node = nodes.get(i)
            val signature: String = signature(n)
            val old: View? = if (i < blocks.size) blocks.get(i) else null
            if (old != null && signatures.get(i).equals(signature)) {
                i++
                continue
            }
            var v: View
            if (n is FencedCodeBlock || n is IndentedCodeBlock) {
                val code: CodeBlock = if (old is CodeBlock) old as CodeBlock else CodeBlock()
                val language: String = if (n is FencedCodeBlock) (n as FencedCodeBlock).getInfo() else ""
                val literal: String = if (n is FencedCodeBlock) (n as FencedCodeBlock).getLiteral() else (n as IndentedCodeBlock).getLiteral()
                code.update(language, literal)
                v = code
            } else if (n is TableBlock) {
                v = table(n as TableBlock)
            } else {
                val t: TextView = if (old is TextView) old as TextView else text(14)
                t.setTextIsSelectable(true)
                markdown.setParsedMarkdown(t, markdown.render(n))
                v = t
            }
            if (old !== v) {
                if (old != null) {
                    removeView(old)
                    blocks.set(i, v)
                    signatures.set(i, signature)
                } else {
                    blocks.add(v)
                    signatures.add(signature)
                }
                val p = LinearLayout.LayoutParams(-1, -2)
                p.setMargins(0, dp(4), 0, dp(6))
                addView(v, i + 1, p)
            } else {
                signatures.set(i, signature)
            }
            i++
        }
        while (blocks.size > nodes.size) {
            val i: Int = blocks.size - 1
            removeView(blocks.removeAt(i))
            signatures.removeAt(i)
        }
        val existing: View? = findViewWithTag("markdown-more")
        if (existing != null) {
            removeView(existing)
        }
        if (more != null) {
            val button: TextView = text(12)
            button.setText(if (WorkspaceLanguage.code(getContext())!!.equals("ru")) "Нажмите для полного текста…" else "Tap for full text…")
            button.setTextColor(Pal.ACCENT)
            button.setTag("markdown-more")
            button.setOnClickListener(View.OnClickListener { v -> more.run() })
            addView(button)
        }
    }

    private fun table(node: TableBlock): View {
        val scroll = HorizontalScrollView(getContext())
        scroll.setFillViewport(true)
        scroll.setBackground(background())
        val table = TableLayout(getContext())
        table.setPadding(dp(4), dp(4), dp(4), dp(4))
        var count = 0
        var truncated = false
        var section: Node? = node.getFirstChild()
        while (section != null) {
            var row: Node? = section.getFirstChild()
            while (row != null) {
                if (count >= 200) {
                    truncated = true
                    break
                }
                val line = TableRow(getContext())
                var cell: Node? = row.getFirstChild()
                while (cell != null) {
                    val value: TextView = text(13)
                    value.setTextIsSelectable(true)
                    markdown!!.setParsedMarkdown(value, markdown.render(cell))
                    if (cell is TableCell && (cell as TableCell).isHeader()) {
                        value.setTextColor(0xffa1decf.toInt())
                        value.setTypeface(null, Typeface.BOLD)
                    }
                    if (cell is TableCell) {
                        val alignment: TableCell.Alignment = (cell as TableCell).getAlignment()
                        if (alignment === TableCell.Alignment.RIGHT) {
                            value.setGravity(Gravity.END)
                        } else if (alignment === TableCell.Alignment.CENTER) {
                            value.setGravity(Gravity.CENTER_HORIZONTAL)
                        }
                    }
                    value.setMinWidth(dp(120))
                    value.setMaxWidth(dp(300))
                    value.setPadding(dp(12), dp(10), dp(12), dp(10))
                    line.addView(value, TableRow.LayoutParams(-2, -2))
                    cell = cell.getNext()
                }
                table.addView(line)
                val divider = View(getContext())
                divider.setBackgroundColor(Pal.BORDER)
                table.addView(divider, TableLayout.LayoutParams(-1, dp(1)))
                row = row.getNext()
                count++
            }
            section = section.getNext()
        }
        if (truncated) {
            val hint: TextView = text(12)
            hint.setText(if (WorkspaceLanguage.code(getContext())!!.equals("ru")) "Показаны первые 200 строк таблицы." else "Showing the first 200 table rows.")
            table.addView(hint)
        }
        scroll.addView(table, FrameLayout.LayoutParams(-2, -2))
        return scroll
    }

    private inner class CodeBlock : LinearLayout {
        private val language: TextView
        private val copy: TextView
        private val body: TextView
        private val numbers: TextView
        private val expand: TextView
        private val viewport: HorizontalScrollView
        private var literal: String = ""
        private var expanded: Boolean = false

        constructor() : super(this@AgentMarkdownView.getContext()) {
            setOrientation(LinearLayout.VERTICAL)
            setBackground(background())
            val header = LinearLayout(getContext())
            header.setGravity(Gravity.CENTER_VERTICAL)
            language = text(11)
            language.setTextColor(0xff88a8b8.toInt())
            language.setTypeface(Typeface.MONOSPACE)
            copy = text(11)
            copy.setTextColor(0xff9de4d6.toInt())
            copy.setText(if (WorkspaceLanguage.code(getContext())!!.equals("ru")) "Копировать" else "Copy")
            copy.setContentDescription(copy.getText())
            copy.setTag("markdown-copy")
            copy.setGravity(Gravity.CENTER)
            copy.setFocusable(true)
            copy.setBackground(WorkspaceDialogs.background(getContext(), false))
            copy.setOnClickListener(View.OnClickListener { v ->
                val c: ClipboardManager? = getContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                c!!.setPrimaryClip(ClipData.newPlainText("code", literal))
            })
            header.addView(language, LinearLayout.LayoutParams(0, -2, 1.0f))
            val copyParams = LinearLayout.LayoutParams(-2, dp(36))
            copyParams.setMargins(0, dp(4), dp(8), dp(4))
            header.addView(copy, copyParams)
            addView(header)
            viewport = HorizontalScrollView(getContext())
            val line = LinearLayout(getContext())
            numbers = text(12)
            numbers.setTextColor(0xff67828d.toInt())
            numbers.setTypeface(Typeface.MONOSPACE)
            body = text(12)
            body.setTypeface(Typeface.MONOSPACE)
            body.setTextIsSelectable(true)
            body.setTag("markdown-code")
            body.setHorizontallyScrolling(true)
            line.addView(numbers)
            line.addView(body)
            viewport.addView(line)
            addView(viewport)
            expand = text(11)
            expand.setTextColor(Pal.ACCENT)
            expand.setTag("markdown-expand")
            expand.setOnClickListener(View.OnClickListener { v ->
                expanded = !expanded
                applyLines()
            })
            addView(expand)
        }

        fun update(info: String, code: String) {
            literal = code
            var lang: String = (info as java.lang.String).trim()
            if (lang.length > 80) {
                lang = lang.substring(0, 80)
            }
            language.setText(if (lang.isEmpty()) "code" else lang)
            val grammar: String = (lang as java.lang.String).split("\\s+", 2)[0]
            body.setText(AgentMarkdown.highlight(code, grammar))
            val lines = StringBuilder()
            val total: Int = (code as java.lang.String).split("\n", -1).size - (if (code.endsWith("\n")) 1 else 0)
            var i = 1
            while (i <= Math.max(1, total)) {
                if (i > 1) {
                    lines.append('\n')
                }
                lines.append(i)
                i++
            }
            numbers.setText(lines)
            applyLines()
        }

        private fun applyLines() {
            val lines: Int = (literal as java.lang.String).split("\n", -1).size
            val longCode: Boolean = lines > 14
            body.setMaxLines(if (longCode && !expanded) 12 else Integer.MAX_VALUE)
            numbers.setMaxLines(if (longCode && !expanded) 12 else Integer.MAX_VALUE)
            expand.setVisibility(if (longCode) View.VISIBLE else View.GONE)
            expand.setText(if (WorkspaceLanguage.code(getContext())!!.equals("ru")) if (expanded) "Свернуть ↑" else "Показать весь код ↓" else if (expanded) "Collapse ↑" else "Show all code ↓")
        }
    }

    companion object {
        @JvmStatic
        private fun signature(n: Node): String {
            val b = StringBuilder()
            signature(n, b)
            return b.toString()
        }

        @JvmStatic
        private fun signature(n: Node, b: StringBuilder) {
            b.append(n.javaClass.getName()).append(':')
            if (n is Text) {
                b.append((n as Text).getLiteral())
            }
            if (n is Code) {
                b.append((n as Code).getLiteral())
            }
            if (n is FencedCodeBlock) {
                b.append((n as FencedCodeBlock).getInfo()).append('\u0000').append((n as FencedCodeBlock).getLiteral())
            }
            if (n is IndentedCodeBlock) {
                b.append((n as IndentedCodeBlock).getLiteral())
            }
            if (n is Heading) {
                b.append((n as Heading).getLevel())
            }
            if (n is TableCell) {
                b.append((n as TableCell).isHeader()).append((n as TableCell).getAlignment())
            }
            if (n is Link) {
                b.append((n as Link).getDestination())
            }
            if (n is OrderedList) {
                b.append((n as OrderedList).getStartNumber())
            }
            var c: Node? = n.getFirstChild()
            while (c != null) {
                signature(c, b)
                c = c.getNext()
            }
            b.append(';')
        }
    }
}
