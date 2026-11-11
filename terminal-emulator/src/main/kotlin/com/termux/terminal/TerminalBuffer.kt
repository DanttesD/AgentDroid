package com.termux.terminal

import java.util.Arrays

/**
 * A circular buffer of [TerminalRow]:s which keeps notes about what is visible on a logical screen and the scroll
 * history.
 *
 * See [externalToInternalRow] for how to map from logical screen rows to array indices.
 */
class TerminalBuffer(columns: Int, totalRows: Int, screenRows: Int) {

    @JvmField
    var mLines: Array<TerminalRow?> = arrayOfNulls(totalRows)

    /** The length of [mLines]. */
    @JvmField
    var mTotalRows: Int = totalRows

    /** The number of rows and columns visible on the screen. */
    @JvmField
    var mScreenRows: Int = screenRows

    @JvmField
    var mColumns: Int = columns

    /** The number of rows kept in history. */
    private var mActiveTranscriptRows = 0

    /** The index in the circular buffer where the visible screen starts. */
    private var mScreenFirstRow = 0

    /**
     * Create a transcript screen.
     *
     * @param columns    the width of the screen in characters.
     * @param totalRows  the height of the entire text area, in rows of text.
     * @param screenRows the height of just the screen, not including the transcript that holds lines that have scrolled off
     *                   the top of the screen.
     */
    init {
        blockSet(0, 0, columns, screenRows, ' '.code, TextStyle.NORMAL)
    }

    fun getTranscriptText(): String {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows).jtrim()
    }

    fun getTranscriptTextWithoutJoinedLines(): String {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, false).jtrim()
    }

    fun getTranscriptTextWithFullLinesJoined(): String {
        return getSelectedText(0, -getActiveTranscriptRows(), mColumns, mScreenRows, true, true).jtrim()
    }

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int): String {
        return getSelectedText(selX1, selY1, selX2, selY2, true)
    }

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int, joinBackLines: Boolean): String {
        return getSelectedText(selX1, selY1, selX2, selY2, joinBackLines, false)
    }

    fun getSelectedText(selX1: Int, selY1: Int, selX2: Int, selY2: Int, joinBackLines: Boolean, joinFullLines: Boolean): String {
        var selY1 = selY1
        var selY2 = selY2
        val builder = StringBuilder()
        val columns = mColumns

        if (selY1 < -getActiveTranscriptRows()) selY1 = -getActiveTranscriptRows()
        if (selY2 >= mScreenRows) selY2 = mScreenRows - 1

        for (row in selY1..selY2) {
            val x1 = if (row == selY1) selX1 else 0
            var x2: Int
            if (row == selY2) {
                x2 = selX2 + 1
                if (x2 > columns) x2 = columns
            } else {
                x2 = columns
            }
            val lineObject = mLines[externalToInternalRow(row)]!!
            val x1Index = lineObject.findStartOfColumn(x1)
            var x2Index = if (x2 < mColumns) lineObject.findStartOfColumn(x2) else lineObject.getSpaceUsed()
            if (x2Index == x1Index) {
                // Selected the start of a wide character.
                x2Index = lineObject.findStartOfColumn(x2 + 1)
            }
            val line = lineObject.mText
            var lastPrintingCharIndex = -1
            val rowLineWrap = getLineWrap(row)
            if (rowLineWrap && x2 == columns) {
                // If the line was wrapped, we shouldn't lose trailing space:
                lastPrintingCharIndex = x2Index - 1
            } else {
                var i = x1Index
                while (i < x2Index) {
                    val c = line[i]
                    if (c != ' ') lastPrintingCharIndex = i
                    ++i
                }
            }

            val len = lastPrintingCharIndex - x1Index + 1
            if (lastPrintingCharIndex != -1 && len > 0)
                builder.append(line, x1Index, len)

            val lineFillsWidth = lastPrintingCharIndex == x2Index - 1
            if ((!joinBackLines || !rowLineWrap) && (!joinFullLines || !lineFillsWidth)
                && row < selY2 && row < mScreenRows - 1) builder.append('\n')
        }
        return builder.toString()
    }

    fun getWordAtLocation(x: Int, y: Int): String {
        // Set y1 and y2 to the lines where the wrapped line starts and ends.
        // I.e. if a line that is wrapped to 3 lines starts at line 4, and this
        // is called with y=5, then y1 would be set to 4 and y2 would be set to 6.
        var y1 = y
        var y2 = y
        while (y1 > 0 && !getSelectedText(0, y1 - 1, mColumns, y, true, true).contains("\n")) {
            y1--
        }
        while (y2 < mScreenRows && !getSelectedText(0, y, mColumns, y2 + 1, true, true).contains("\n")) {
            y2++
        }

        // Get the text for the whole wrapped line
        val text = getSelectedText(0, y1, mColumns, y2, true, true)
        // The index of x in text
        val textOffset = (y - y1) * mColumns + x

        if (textOffset >= text.length) {
            // The click was to the right of the last word on the line, so
            // there's no word to return
            return ""
        }

        // Set x1 and x2 to the indices of the last space before x and the
        // first space after x in text respectively
        val x1 = text.lastIndexOf(' ', textOffset)
        var x2 = text.indexOf(' ', textOffset)
        if (x2 == -1) {
            x2 = text.length
        }

        if (x1 == x2) {
            // The click was on a space, so there's no word to return
            return ""
        }
        return text.substring(x1 + 1, x2)
    }

    fun getActiveTranscriptRows(): Int {
        return mActiveTranscriptRows
    }

    fun getActiveRows(): Int {
        return mActiveTranscriptRows + mScreenRows
    }

    /**
     * Convert a row value from the public external coordinate system to our internal private coordinate system.
     *
     * ```
     * - External coordinate system: -mActiveTranscriptRows to mScreenRows-1, with the screen being 0..mScreenRows-1.
     * - Internal coordinate system: the mScreenRows lines starting at mScreenFirstRow comprise the screen, while the
     *   mActiveTranscriptRows lines ending at mScreenFirstRow-1 form the transcript (as a circular buffer).
     *
     * External ↔ Internal:
     *
     * [ ...                            ]     [ ...                                     ]
     * [ -mActiveTranscriptRows         ]     [ mScreenFirstRow - mActiveTranscriptRows ]
     * [ ...                            ]     [ ...                                     ]
     * [ 0 (visible screen starts here) ]  ↔  [ mScreenFirstRow                         ]
     * [ ...                            ]     [ ...                                     ]
     * [ mScreenRows-1                  ]     [ mScreenFirstRow + mScreenRows-1         ]
     * ```
     *
     * @param externalRow a row in the external coordinate system.
     * @return The row corresponding to the input argument in the private coordinate system.
     */
    fun externalToInternalRow(externalRow: Int): Int {
        if (externalRow < -mActiveTranscriptRows || externalRow > mScreenRows)
            throw IllegalArgumentException("extRow=" + externalRow + ", mScreenRows=" + mScreenRows + ", mActiveTranscriptRows=" + mActiveTranscriptRows)
        val internalRow = mScreenFirstRow + externalRow
        return if (internalRow < 0) (mTotalRows + internalRow) else (internalRow % mTotalRows)
    }

    fun setLineWrap(row: Int) {
        mLines[externalToInternalRow(row)]!!.mLineWrap = true
    }

    fun getLineWrap(row: Int): Boolean {
        return mLines[externalToInternalRow(row)]!!.mLineWrap
    }

    fun clearLineWrap(row: Int) {
        mLines[externalToInternalRow(row)]!!.mLineWrap = false
    }

    /**
     * Resize the screen which this transcript backs. Currently, this only works if the number of columns does not
     * change or the rows expand (that is, it only works when shrinking the number of rows).
     *
     * @param newColumns The number of columns the screen should have.
     * @param newRows    The number of rows the screen should have.
     * @param cursor     An int[2] containing the (column, row) cursor location.
     */
    fun resize(newColumns: Int, newRows: Int, newTotalRows: Int, cursor: IntArray, currentStyle: Long, altScreen: Boolean) {
        // newRows > mTotalRows should not normally happen since mTotalRows is TRANSCRIPT_ROWS (10000):
        if (newColumns == mColumns && newRows <= mTotalRows) {
            // Fast resize where just the rows changed.
            var shiftDownOfTopRow = mScreenRows - newRows
            if (shiftDownOfTopRow > 0 && shiftDownOfTopRow < mScreenRows) {
                // Shrinking. Check if we can skip blank rows at bottom below cursor.
                var i = mScreenRows - 1
                while (i > 0) {
                    if (cursor[1] >= i) break
                    val r = externalToInternalRow(i)
                    val rowLine = mLines[r]
                    if (rowLine == null || rowLine.isBlank()) {
                        if (--shiftDownOfTopRow == 0) break
                    }
                    i--
                }
            } else if (shiftDownOfTopRow < 0) {
                // Negative shift down = expanding. Only move screen up if there is transcript to show:
                val actualShift = Math.max(shiftDownOfTopRow, -mActiveTranscriptRows)
                if (shiftDownOfTopRow != actualShift) {
                    // The new lines revealed by the resizing are not all from the transcript. Blank the below ones.
                    for (i in 0 until actualShift - shiftDownOfTopRow)
                        allocateFullLineIfNecessary((mScreenFirstRow + mScreenRows + i) % mTotalRows).clear(currentStyle)
                    shiftDownOfTopRow = actualShift
                }
            }
            mScreenFirstRow += shiftDownOfTopRow
            mScreenFirstRow = if (mScreenFirstRow < 0) (mScreenFirstRow + mTotalRows) else (mScreenFirstRow % mTotalRows)
            mTotalRows = newTotalRows
            mActiveTranscriptRows = if (altScreen) 0 else Math.max(0, mActiveTranscriptRows + shiftDownOfTopRow)
            cursor[1] -= shiftDownOfTopRow
            mScreenRows = newRows
        } else {
            // Copy away old state and update new:
            val oldLines = mLines
            mLines = arrayOfNulls(newTotalRows)
            for (i in 0 until newTotalRows)
                mLines[i] = TerminalRow(newColumns, currentStyle)

            val oldActiveTranscriptRows = mActiveTranscriptRows
            val oldScreenFirstRow = mScreenFirstRow
            val oldScreenRows = mScreenRows
            val oldTotalRows = mTotalRows
            mTotalRows = newTotalRows
            mScreenRows = newRows
            mScreenFirstRow = 0
            mActiveTranscriptRows = mScreenFirstRow
            mColumns = newColumns

            var newCursorRow = -1
            var newCursorColumn = -1
            val oldCursorRow = cursor[1]
            val oldCursorColumn = cursor[0]
            var newCursorPlaced = false

            var currentOutputExternalRow = 0
            var currentOutputExternalColumn = 0

            // Loop over every character in the initial state.
            // Blank lines should be skipped only if at end of transcript (just as is done in the "fast" resize), so we
            // keep track how many blank lines we have skipped if we later on find a non-blank line.
            var skippedBlankLines = 0
            for (externalOldRow in -oldActiveTranscriptRows until oldScreenRows) {
                // Do what externalToInternalRow() does but for the old state:
                var internalOldRow = oldScreenFirstRow + externalOldRow
                internalOldRow = if (internalOldRow < 0) (oldTotalRows + internalOldRow) else (internalOldRow % oldTotalRows)

                val oldLine = oldLines[internalOldRow]
                val cursorAtThisRow = externalOldRow == oldCursorRow
                // The cursor may only be on a non-null line, which we should not skip:
                if (oldLine == null || (!(!newCursorPlaced && cursorAtThisRow)) && oldLine.isBlank()) {
                    skippedBlankLines++
                    continue
                } else if (skippedBlankLines > 0) {
                    // After skipping some blank lines we encounter a non-blank line. Insert the skipped blank lines.
                    for (i in 0 until skippedBlankLines) {
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            scrollDownOneLine(0, mScreenRows, currentStyle)
                        } else {
                            currentOutputExternalRow++
                        }
                        currentOutputExternalColumn = 0
                    }
                    skippedBlankLines = 0
                }

                var lastNonSpaceIndex = 0
                var justToCursor = false
                if (cursorAtThisRow || oldLine.mLineWrap) {
                    // Take the whole line, either because of cursor on it, or if line wrapping.
                    lastNonSpaceIndex = oldLine.getSpaceUsed()
                    if (cursorAtThisRow) justToCursor = true
                } else {
                    var i = 0
                    while (i < oldLine.getSpaceUsed()) {
                        // NEWLY INTRODUCED BUG! Should not index oldLine.mStyle with char indices
                        if (oldLine.mText[i] != ' '/* || oldLine.mStyle[i] != currentStyle */)
                            lastNonSpaceIndex = i + 1
                        i++
                    }
                }

                var currentOldCol = 0
                var styleAtCol: Long = 0
                var i = 0
                while (i < lastNonSpaceIndex) {
                    // Note that looping over java character, not cells.
                    val c = oldLine.mText[i]
                    val codePoint: Int
                    if (Character.isHighSurrogate(c)) {
                        i++
                        codePoint = Character.toCodePoint(c, oldLine.mText[i])
                    } else {
                        codePoint = c.code
                    }
                    val displayWidth = WcWidth.width(codePoint)
                    // Use the last style if this is a zero-width character:
                    if (displayWidth > 0) styleAtCol = oldLine.getStyle(currentOldCol)

                    // Line wrap as necessary:
                    if (currentOutputExternalColumn + displayWidth > mColumns) {
                        setLineWrap(currentOutputExternalRow)
                        if (currentOutputExternalRow == mScreenRows - 1) {
                            if (newCursorPlaced) newCursorRow--
                            scrollDownOneLine(0, mScreenRows, currentStyle)
                        } else {
                            currentOutputExternalRow++
                        }
                        currentOutputExternalColumn = 0
                    }

                    val offsetDueToCombiningChar = (if (displayWidth <= 0 && currentOutputExternalColumn > 0) 1 else 0)
                    val outputColumn = currentOutputExternalColumn - offsetDueToCombiningChar
                    setChar(outputColumn, currentOutputExternalRow, codePoint, styleAtCol)

                    if (displayWidth > 0) {
                        if (oldCursorRow == externalOldRow && oldCursorColumn == currentOldCol) {
                            newCursorColumn = currentOutputExternalColumn
                            newCursorRow = currentOutputExternalRow
                            newCursorPlaced = true
                        }
                        currentOldCol += displayWidth
                        currentOutputExternalColumn += displayWidth
                        if (justToCursor && newCursorPlaced) break
                    }
                    i++
                }
                // Old row has been copied. Check if we need to insert newline if old line was not wrapping:
                if (externalOldRow != (oldScreenRows - 1) && !oldLine.mLineWrap) {
                    if (currentOutputExternalRow == mScreenRows - 1) {
                        if (newCursorPlaced) newCursorRow--
                        scrollDownOneLine(0, mScreenRows, currentStyle)
                    } else {
                        currentOutputExternalRow++
                    }
                    currentOutputExternalColumn = 0
                }
            }

            cursor[0] = newCursorColumn
            cursor[1] = newCursorRow
        }

        // Handle cursor scrolling off screen:
        if (cursor[0] < 0 || cursor[1] < 0) {
            cursor[1] = 0
            cursor[0] = cursor[1]
        }
    }

    /**
     * Block copy lines and associated metadata from one location to another in the circular buffer, taking wraparound
     * into account.
     *
     * @param srcInternal The first line to be copied.
     * @param len         The number of lines to be copied.
     */
    private fun blockCopyLinesDown(srcInternal: Int, len: Int) {
        if (len == 0) return
        val totalRows = mTotalRows

        val start = len - 1
        // Save away line to be overwritten:
        val lineToBeOverWritten = mLines[(srcInternal + start + 1) % totalRows]
        // Do the copy from bottom to top.
        for (i in start downTo 0)
            mLines[(srcInternal + i + 1) % totalRows] = mLines[(srcInternal + i) % totalRows]
        // Put back overwritten line, now above the block:
        mLines[(srcInternal) % totalRows] = lineToBeOverWritten
    }

    /**
     * Scroll the screen down one line. To scroll the whole screen of a 24 line screen, the arguments would be (0, 24).
     *
     * @param topMargin    First line that is scrolled.
     * @param bottomMargin One line after the last line that is scrolled.
     * @param style        the style for the newly exposed line.
     */
    fun scrollDownOneLine(topMargin: Int, bottomMargin: Int, style: Long) {
        if (topMargin > bottomMargin - 1 || topMargin < 0 || bottomMargin > mScreenRows)
            throw IllegalArgumentException("topMargin=" + topMargin + ", bottomMargin=" + bottomMargin + ", mScreenRows=" + mScreenRows)

        // Copy the fixed topMargin lines one line down so that they remain on screen in same position:
        blockCopyLinesDown(mScreenFirstRow, topMargin)
        // Copy the fixed mScreenRows-bottomMargin lines one line down so that they remain on screen in same
        // position:
        blockCopyLinesDown(externalToInternalRow(bottomMargin), mScreenRows - bottomMargin)

        // Update the screen location in the ring buffer:
        mScreenFirstRow = (mScreenFirstRow + 1) % mTotalRows
        // Note that the history has grown if not already full:
        if (mActiveTranscriptRows < mTotalRows - mScreenRows) mActiveTranscriptRows++

        // Blank the newly revealed line above the bottom margin:
        val blankRow = externalToInternalRow(bottomMargin - 1)
        val blank = mLines[blankRow]
        if (blank == null) {
            mLines[blankRow] = TerminalRow(mColumns, style)
        } else {
            blank.clear(style)
        }
    }

    /**
     * Block copy characters from one position in the screen to another. The two positions can overlap. All characters
     * of the source and destination must be within the bounds of the screen, or else an InvalidParameterException will
     * be thrown.
     *
     * @param sx source X coordinate
     * @param sy source Y coordinate
     * @param w  width
     * @param h  height
     * @param dx destination X coordinate
     * @param dy destination Y coordinate
     */
    fun blockCopy(sx: Int, sy: Int, w: Int, h: Int, dx: Int, dy: Int) {
        if (w == 0) return
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows || dx < 0 || dx + w > mColumns || dy < 0 || dy + h > mScreenRows)
            throw IllegalArgumentException()
        val copyingUp = sy > dy
        for (y in 0 until h) {
            val y2 = if (copyingUp) y else (h - (y + 1))
            val sourceRow = allocateFullLineIfNecessary(externalToInternalRow(sy + y2))
            allocateFullLineIfNecessary(externalToInternalRow(dy + y2)).copyInterval(sourceRow, sx, sx + w, dx)
        }
    }

    /**
     * Block set characters. All characters must be within the bounds of the screen, or else and
     * InvalidParemeterException will be thrown. Typically this is called with a "val" argument of 32 to clear a block
     * of characters.
     */
    fun blockSet(sx: Int, sy: Int, w: Int, h: Int, value: Int, style: Long) {
        if (sx < 0 || sx + w > mColumns || sy < 0 || sy + h > mScreenRows) {
            throw IllegalArgumentException(
                "Illegal arguments! blockSet(" + sx + ", " + sy + ", " + w + ", " + h + ", " + value + ", " + mColumns + ", " + mScreenRows + ")")
        }
        for (y in 0 until h)
            for (x in 0 until w)
                setChar(sx + x, sy + y, value, style)
    }

    fun allocateFullLineIfNecessary(row: Int): TerminalRow {
        val existing = mLines[row]
        if (existing != null) return existing
        val created = TerminalRow(mColumns, 0)
        mLines[row] = created
        return created
    }

    fun setChar(column: Int, row: Int, codePoint: Int, style: Long) {
        var row = row
        if (row < 0 || row >= mScreenRows || column < 0 || column >= mColumns)
            throw IllegalArgumentException("TerminalBuffer.setChar(): row=" + row + ", column=" + column + ", mScreenRows=" + mScreenRows + ", mColumns=" + mColumns)
        row = externalToInternalRow(row)
        allocateFullLineIfNecessary(row).setChar(column, codePoint, style)
    }

    fun getStyleAt(externalRow: Int, column: Int): Long {
        return allocateFullLineIfNecessary(externalToInternalRow(externalRow)).getStyle(column)
    }

    /** Support for http://vt100.net/docs/vt510-rm/DECCARA and http://vt100.net/docs/vt510-rm/DECCARA */
    fun setOrClearEffect(bits: Int, setOrClear: Boolean, reverse: Boolean, rectangular: Boolean, leftMargin: Int, rightMargin: Int, top: Int, left: Int,
                         bottom: Int, right: Int) {
        for (y in top until bottom) {
            val line = mLines[externalToInternalRow(y)]
            val startOfLine = if (rectangular || y == top) left else leftMargin
            val endOfLine = if (rectangular || y + 1 == bottom) right else rightMargin
            for (x in startOfLine until endOfLine) {
                val currentStyle = line!!.getStyle(x)
                val foreColor = TextStyle.decodeForeColor(currentStyle)
                val backColor = TextStyle.decodeBackColor(currentStyle)
                var effect = TextStyle.decodeEffect(currentStyle)
                if (reverse) {
                    // Clear out the bits to reverse and add them back in reversed:
                    effect = (effect and bits.inv()) or (bits and effect.inv())
                } else if (setOrClear) {
                    effect = effect or bits
                } else {
                    effect = effect and bits.inv()
                }
                line.mStyle[x] = TextStyle.encode(foreColor, backColor, effect)
            }
        }
    }

    fun clearTranscript() {
        if (mScreenFirstRow < mActiveTranscriptRows) {
            Arrays.fill(mLines, mTotalRows + mScreenFirstRow - mActiveTranscriptRows, mTotalRows, null)
            Arrays.fill(mLines, 0, mScreenFirstRow, null)
        } else {
            Arrays.fill(mLines, mScreenFirstRow - mActiveTranscriptRows, mScreenFirstRow, null)
        }
        mActiveTranscriptRows = 0
    }
}
