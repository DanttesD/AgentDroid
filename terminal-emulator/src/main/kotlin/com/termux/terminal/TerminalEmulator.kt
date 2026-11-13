package com.termux.terminal

import android.util.Base64
import java.nio.charset.StandardCharsets
import java.util.Arrays
import java.util.Locale
import java.util.Objects
import java.util.Stack

/**
 * Renders text into a screen. Contains all the terminal-specific knowledge and state. Emulates a subset of the X Window
 * System xterm terminal, which in turn is an emulator for a subset of the Digital Equipment Corporation vt100 terminal.
 *
 * References:
 *
 * - http://invisible-island.net/xterm/ctlseqs/ctlseqs.html
 * - http://en.wikipedia.org/wiki/ANSI_escape_code
 * - http://man.he.net/man4/console_codes
 * - http://bazaar.launchpad.net/~leonerd/libvterm/trunk/view/head:/src/state.c
 * - http://www.columbia.edu/~kermit/k95manual/iso2022.html
 * - http://www.vt100.net/docs/vt510-rm/chapter4
 * - http://en.wikipedia.org/wiki/ISO/IEC_2022 - for 7-bit and 8-bit GL GR explanation
 * - http://bjh21.me.uk/all-escapes/all-escapes.txt - extensive!
 * - http://woldlab.caltech.edu/~diane/kde4.10/workingdir/kubuntu/konsole/doc/developer/old-documents/VT100/techref.html - document for konsole - accessible!
 */
class TerminalEmulator(
    session: TerminalOutput,
    columns: Int,
    rows: Int,
    cellWidthPixels: Int,
    cellHeightPixels: Int,
    transcriptRows: Int?,
    client: TerminalSessionClient?
) {

    private var mTitle: String? = null
    private val mTitleStack = Stack<String?>()

    /** The cursor position. Between (0,0) and (mRows-1, mColumns-1). */
    private var mCursorRow = 0
    private var mCursorCol = 0

    /** The number of character rows and columns in the terminal screen. */
    @JvmField
    var mRows: Int = rows

    @JvmField
    var mColumns: Int = columns

    /** Size of a terminal cell in pixels. */
    private var mCellWidthPixels = cellWidthPixels
    private var mCellHeightPixels = cellHeightPixels

    /** The terminal cursor styles. */
    private var mCursorStyle = DEFAULT_TERMINAL_CURSOR_STYLE

    /** The normal screen buffer. Stores the characters that appear on the screen of the emulated terminal. */
    private val mMainBuffer: TerminalBuffer = TerminalBuffer(columns, getTerminalTranscriptRows(transcriptRows), rows)

    /**
     * The alternate screen buffer, exactly as large as the display and contains no additional saved lines (so that when
     * the alternate screen buffer is active, you cannot scroll back to view saved lines).
     *
     * See http://www.xfree86.org/current/ctlseqs.html#The%20Alternate%20Screen%20Buffer
     */
    @JvmField
    val mAltBuffer: TerminalBuffer = TerminalBuffer(columns, rows, rows)

    /** The current screen buffer, pointing at either [mMainBuffer] or [mAltBuffer]. */
    private var mScreen: TerminalBuffer = mMainBuffer

    /** The terminal session this emulator is bound to. */
    private val mSession: TerminalOutput = session

    @JvmField
    var mClient: TerminalSessionClient? = client

    /** Keeps track of the current argument of the current escape sequence. Ranges from 0 to MAX_ESCAPE_PARAMETERS-1. */
    private var mArgIndex = 0

    /** Holds the arguments of the current escape sequence. */
    private val mArgs = IntArray(MAX_ESCAPE_PARAMETERS)

    /** Holds the bit flags which arguments are sub parameters (after a colon) - bit N is set if `mArgs[N]` is a sub parameter. */
    private var mArgsSubParamsBitSet = 0

    /** Holds OSC and device control arguments, which can be strings. */
    private val mOSCOrDeviceControlArgs = StringBuilder()

    /**
     * True if the current escape sequence should continue, false if the current escape sequence should be terminated.
     * Used when parsing a single character.
     */
    private var mContinueSequence = false

    /** The current state of the escape sequence state machine. One of the ESC_* constants. */
    private var mEscapeState = 0

    private val mSavedStateMain = SavedScreenState()
    private val mSavedStateAlt = SavedScreenState()

    /** http://www.vt100.net/docs/vt102-ug/table5-15.html */
    private var mUseLineDrawingG0 = false
    private var mUseLineDrawingG1 = false
    private var mUseLineDrawingUsesG0 = true

    /**
     * @see TerminalEmulator.mapDecSetBitToInternalBit
     */
    private var mCurrentDecSetFlags = 0
    private var mSavedDecSetFlags = 0

    /**
     * If insert mode (as opposed to replace mode) is active. In insert mode new characters are inserted, pushing
     * existing text to the right. Characters moved past the right margin are lost.
     */
    private var mInsertMode = false

    /** An array of tab stops. mTabStop[i] is true if there is a tab stop set for column i. */
    private var mTabStop: BooleanArray = BooleanArray(columns)

    /**
     * Top margin of screen for scrolling ranges from 0 to mRows-2. Bottom margin ranges from mTopMargin + 2 to mRows
     * (Defines the first row after the scrolling region). Left/right margin in [0, mColumns].
     */
    private var mTopMargin = 0
    private var mBottomMargin = 0
    private var mLeftMargin = 0
    private var mRightMargin = 0

    /**
     * If the next character to be emitted will be automatically wrapped to the next line. Used to disambiguate the case
     * where the cursor is positioned on the last column (mColumns-1). When standing there, a written character will be
     * output in the last column, the cursor not moving but this flag will be set. When outputting another character
     * this will move to the next line.
     */
    private var mAboutToAutoWrap = false

    /**
     * If the cursor blinking is enabled. It requires cursor itself to be enabled, which is controlled
     * byt whether [DECSET_BIT_CURSOR_ENABLED] bit is set or not.
     */
    private var mCursorBlinkingEnabled = false

    /**
     * If currently cursor should be in a visible state or not if [mCursorBlinkingEnabled]
     * is `true`.
     */
    private var mCursorBlinkState = false

    /**
     * Current foreground, background and underline colors. Can either be a color index in [0,259] or a truecolor (24-bit) value.
     * For a 24-bit value the top byte (0xff000000) is set.
     *
     * Note that the underline color is currently parsed but not yet used during rendering.
     *
     * @see TextStyle
     */
    @JvmField
    var mForeColor = 0

    @JvmField
    var mBackColor = 0

    @JvmField
    var mUnderlineColor = 0

    /** Current [TextStyle] effect. */
    @JvmField
    var mEffect = 0

    /**
     * The number of scrolled lines since last calling [clearScrollCounter]. Used for moving selection up along
     * with the scrolling text.
     */
    private var mScrollCounter = 0

    private var mUtf8ToFollow = 0
    private var mUtf8Index = 0
    private val mUtf8InputBuffer = ByteArray(4)
    private var mLastEmittedCodePoint = -1

    @JvmField
    val mColors = TerminalColors()

    init {
        reset()
    }

    private fun isDecsetInternalBitSet(bit: Int): Boolean {
        return (mCurrentDecSetFlags and bit) != 0
    }

    private fun setDecsetinternalBit(internalBit: Int, set: Boolean) {
        if (set) {
            // The mouse modes are mutually exclusive.
            if (internalBit == DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) {
                setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT, false)
            } else if (internalBit == DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT) {
                setDecsetinternalBit(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE, false)
            }
        }
        if (set) {
            mCurrentDecSetFlags = mCurrentDecSetFlags or internalBit
        } else {
            mCurrentDecSetFlags = mCurrentDecSetFlags and internalBit.inv()
        }
    }

    fun updateTerminalSessionClient(client: TerminalSessionClient?) {
        mClient = client
        setCursorStyle()
        setCursorBlinkState(true)
    }

    fun getScreen(): TerminalBuffer {
        return mScreen
    }

    fun isAlternateBufferActive(): Boolean {
        return mScreen === mAltBuffer
    }

    /**
     * @param mouseButton one of the MOUSE_* constants of this class.
     */
    fun sendMouseEvent(mouseButton: Int, column: Int, row: Int, pressed: Boolean) {
        var mouseButton = mouseButton
        var column = column
        var row = row
        if (column < 1) column = 1
        if (column > mColumns) column = mColumns
        if (row < 1) row = 1
        if (row > mRows) row = mRows

        if (mouseButton == MOUSE_LEFT_BUTTON_MOVED && !isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)) {
            // Do not send tracking.
        } else if (isDecsetInternalBitSet(DECSET_BIT_MOUSE_PROTOCOL_SGR)) {
            mSession.write(String.format("\u001b[<%d;%d;%d" + (if (pressed) 'M' else 'm'), mouseButton, column, row))
        } else {
            mouseButton = if (pressed) mouseButton else 3 // 3 for release of all buttons.
            // Clip to screen, and clip to the limits of 8-bit data.
            val out_of_bounds = column > 255 - 32 || row > 255 - 32
            if (!out_of_bounds) {
                val data = byteArrayOf(27, '['.code.toByte(), 'M'.code.toByte(), (32 + mouseButton).toByte(), (32 + column).toByte(), (32 + row).toByte())
                mSession.write(data, 0, data.size)
            }
        }
    }

    fun resize(columns: Int, rows: Int, cellWidthPixels: Int, cellHeightPixels: Int) {
        this.mCellWidthPixels = cellWidthPixels
        this.mCellHeightPixels = cellHeightPixels

        if (mRows == rows && mColumns == columns) {
            return
        } else if (columns < 2 || rows < 2) {
            throw IllegalArgumentException("rows=" + rows + ", columns=" + columns)
        }

        if (mRows != rows) {
            mRows = rows
            mTopMargin = 0
            mBottomMargin = mRows
        }
        if (mColumns != columns) {
            val oldColumns = mColumns
            mColumns = columns
            val oldTabStop = mTabStop
            mTabStop = BooleanArray(mColumns)
            setDefaultTabStops()
            val toTransfer = Math.min(oldColumns, columns)
            System.arraycopy(oldTabStop, 0, mTabStop, 0, toTransfer)
            mLeftMargin = 0
            mRightMargin = mColumns
        }

        resizeScreen()
    }

    private fun resizeScreen() {
        val cursor = intArrayOf(mCursorCol, mCursorRow)
        val newTotalRows = if (mScreen === mAltBuffer) mRows else mMainBuffer.mTotalRows
        mScreen.resize(mColumns, mRows, newTotalRows, cursor, getStyle(), isAlternateBufferActive())
        mCursorCol = cursor[0]
        mCursorRow = cursor[1]
    }

    fun getCursorRow(): Int {
        return mCursorRow
    }

    fun getCursorCol(): Int {
        return mCursorCol
    }

    /** Get the terminal cursor style. It will be one of [TERMINAL_CURSOR_STYLES_LIST] */
    fun getCursorStyle(): Int {
        return mCursorStyle
    }

    /** Set the terminal cursor style. */
    fun setCursorStyle() {
        var cursorStyle: Int? = null

        if (mClient != null)
            cursorStyle = mClient!!.getTerminalCursorStyle()

        if (cursorStyle == null || !Arrays.asList(*TERMINAL_CURSOR_STYLES_LIST).contains(cursorStyle))
            mCursorStyle = DEFAULT_TERMINAL_CURSOR_STYLE
        else
            mCursorStyle = cursorStyle
    }

    fun isReverseVideo(): Boolean {
        return isDecsetInternalBitSet(DECSET_BIT_REVERSE_VIDEO)
    }

    fun isCursorEnabled(): Boolean {
        return isDecsetInternalBitSet(DECSET_BIT_CURSOR_ENABLED)
    }

    fun shouldCursorBeVisible(): Boolean {
        if (!isCursorEnabled())
            return false
        else
            return if (mCursorBlinkingEnabled) mCursorBlinkState else true
    }

    fun setCursorBlinkingEnabled(cursorBlinkingEnabled: Boolean) {
        this.mCursorBlinkingEnabled = cursorBlinkingEnabled
    }

    fun setCursorBlinkState(cursorBlinkState: Boolean) {
        this.mCursorBlinkState = cursorBlinkState
    }

    fun isKeypadApplicationMode(): Boolean {
        return isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD)
    }

    fun isCursorKeysApplicationMode(): Boolean {
        return isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS)
    }

    /** If mouse events are being sent as escape codes to the terminal. */
    fun isMouseTrackingActive(): Boolean {
        return isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE) || isDecsetInternalBitSet(DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT)
    }

    private fun setDefaultTabStops() {
        for (i in 0 until mColumns)
            mTabStop[i] = (i and 7) == 0 && i != 0
    }

    /**
     * Accept bytes (typically from the pseudo-teletype) and process them.
     *
     * @param buffer a byte array containing the bytes to be processed
     * @param length the number of bytes in the array to process
     */
    fun append(buffer: ByteArray, length: Int) {
        for (i in 0 until length)
            processByte(buffer[i])
    }

    private fun processByte(byteToProcess: Byte) {
        val b = byteToProcess.toInt()
        if (mUtf8ToFollow > 0) {
            if ((b and 0b11000000) == 0b10000000) {
                // 10xxxxxx, a continuation byte.
                mUtf8InputBuffer[mUtf8Index++] = byteToProcess
                if (--mUtf8ToFollow == 0) {
                    val firstByteMask = (if (mUtf8Index == 2) 0b00011111 else (if (mUtf8Index == 3) 0b00001111 else 0b00000111)).toByte().toInt()
                    var codePoint = (mUtf8InputBuffer[0].toInt() and firstByteMask)
                    for (i in 1 until mUtf8Index)
                        codePoint = ((codePoint shl 6) or (mUtf8InputBuffer[i].toInt() and 0b00111111))
                    if (((codePoint <= 0b1111111) && mUtf8Index > 1) || (codePoint < 0b11111111111 && mUtf8Index > 2)
                        || (codePoint < 0b1111111111111111 && mUtf8Index > 3)) {
                        // Overlong encoding.
                        codePoint = UNICODE_REPLACEMENT_CHAR
                    }

                    mUtf8ToFollow = 0
                    mUtf8Index = mUtf8ToFollow

                    if (codePoint >= 0x80 && codePoint <= 0x9F) {
                        // Sequence decoded to a C1 control character which we ignore. They are
                        // not used nowadays and increases the risk of messing up the terminal state
                        // on binary input. XTerm does not allow them in utf-8:
                        // "It is not possible to use a C1 control obtained from decoding the
                        // UTF-8 text" - http://invisible-island.net/xterm/ctlseqs/ctlseqs.html
                    } else {
                        when (Character.getType(codePoint)) {
                            Character.UNASSIGNED.toInt(), Character.SURROGATE.toInt() ->
                                codePoint = UNICODE_REPLACEMENT_CHAR
                        }
                        processCodePoint(codePoint)
                    }
                }
            } else {
                // Not a UTF-8 continuation byte so replace the entire sequence up to now with the replacement char:
                mUtf8ToFollow = 0
                mUtf8Index = mUtf8ToFollow
                emitCodePoint(UNICODE_REPLACEMENT_CHAR)
                // The Unicode Standard Version 6.2 – Core Specification
                // (http://www.unicode.org/versions/Unicode6.2.0/ch03.pdf):
                // "If the converter encounters an ill-formed UTF-8 code unit sequence which starts with a valid first
                // byte, but which does not continue with valid successor bytes (see Table 3-7), it must not consume the
                // successor bytes as part of the ill-formed subsequence
                // whenever those successor bytes themselves constitute part of a well-formed UTF-8 code unit
                // subsequence."
                processByte(byteToProcess)
            }
        } else {
            if ((b and 0b10000000) == 0) { // The leading bit is not set so it is a 7-bit ASCII character.
                processCodePoint(b)
                return
            } else if ((b and 0b11100000) == 0b11000000) { // 110xxxxx, a two-byte sequence.
                mUtf8ToFollow = 1
            } else if ((b and 0b11110000) == 0b11100000) { // 1110xxxx, a three-byte sequence.
                mUtf8ToFollow = 2
            } else if ((b and 0b11111000) == 0b11110000) { // 11110xxx, a four-byte sequence.
                mUtf8ToFollow = 3
            } else {
                // Not a valid UTF-8 sequence start, signal invalid data:
                processCodePoint(UNICODE_REPLACEMENT_CHAR)
                return
            }
            mUtf8InputBuffer[mUtf8Index++] = byteToProcess
        }
    }

    fun processCodePoint(b: Int) {
        // The Application Program-Control (APC) string might be arbitrary non-printable characters, so handle that early.
        if (mEscapeState == ESC_APC) {
            doApc(b)
            return
        } else if (mEscapeState == ESC_APC_ESCAPE) {
            doApcEscape(b)
            return
        }

        when (b) {
            0 -> {
                // Null character (NUL, ^@). Do nothing.
            }
            7 -> { // Bell (BEL, ^G, \a). If in an OSC sequence, BEL may terminate a string; otherwise signal bell.
                if (mEscapeState == ESC_OSC)
                    doOsc(b)
                else
                    mSession.onBell()
            }
            8 -> { // Backspace (BS, ^H).
                if (mLeftMargin == mCursorCol) {
                    // Jump to previous line if it was auto-wrapped.
                    val previousRow = mCursorRow - 1
                    if (previousRow >= 0 && mScreen.getLineWrap(previousRow)) {
                        mScreen.clearLineWrap(previousRow)
                        setCursorRowCol(previousRow, mRightMargin - 1)
                    }
                } else {
                    setCursorCol(mCursorCol - 1)
                }
            }
            9 -> { // Horizontal tab (HT, \t) - move to next tab stop, but not past edge of screen
                // XXX: Should perhaps use color if writing to new cells. Try with
                //       printf "\033[41m\tXX\033[0m\n"
                // The OSX Terminal.app colors the spaces from the tab red, but xterm does not.
                // Note that Terminal.app only colors on new cells, in e.g.
                //       printf "\033[41m\t\r\033[42m\tXX\033[0m\n"
                // the first cells are created with a red background, but when tabbing over
                // them again with a green background they are not overwritten.
                mCursorCol = nextTabStop(1)
            }
            10, 11, 12 -> { // Line feed (LF, \n). Vertical tab (VT, \v). Form feed (FF, \f).
                doLinefeed()
            }
            13 -> { // Carriage return (CR, \r).
                setCursorCol(mLeftMargin)
            }
            14 -> { // Shift Out (Ctrl-N, SO) → Switch to Alternate Character Set. This invokes the G1 character set.
                mUseLineDrawingUsesG0 = false
            }
            15 -> { // Shift In (Ctrl-O, SI) → Switch to Standard Character Set. This invokes the G0 character set.
                mUseLineDrawingUsesG0 = true
            }
            24, 26 -> { // CAN. SUB.
                if (mEscapeState != ESC_NONE) {
                    // FIXME: What is this??
                    mEscapeState = ESC_NONE
                    emitCodePoint(127)
                }
            }
            27 -> { // ESC
                // Starts an escape sequence unless we're parsing a string
                if (mEscapeState == ESC_P) {
                    // XXX: Ignore escape when reading device control sequence, since it may be part of string terminator.
                    return
                } else if (mEscapeState != ESC_OSC) {
                    startEscapeSequence()
                } else {
                    doOsc(b)
                }
            }
            else -> {
                mContinueSequence = false
                when (mEscapeState) {
                    ESC_NONE -> {
                        if (b >= 32) emitCodePoint(b)
                    }
                    ESC -> {
                        doEsc(b)
                    }
                    ESC_POUND -> {
                        doEscPound(b)
                    }
                    ESC_SELECT_LEFT_PAREN -> { // Designate G0 Character Set (ISO 2022, VT100).
                        mUseLineDrawingG0 = (b == '0'.code)
                    }
                    ESC_SELECT_RIGHT_PAREN -> { // Designate G1 Character Set (ISO 2022, VT100).
                        mUseLineDrawingG1 = (b == '0'.code)
                    }
                    ESC_CSI -> {
                        doCsi(b)
                    }
                    ESC_CSI_UNSUPPORTED_PARAMETER_BYTE, ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE -> {
                        doCsiUnsupportedParameterOrIntermediateByte(b)
                    }
                    ESC_CSI_EXCLAMATION -> {
                        if (b == 'p'.code) { // Soft terminal reset (DECSTR, http://vt100.net/docs/vt510-rm/DECSTR).
                            reset()
                        } else {
                            unknownSequence(b)
                        }
                    }
                    ESC_CSI_QUESTIONMARK -> {
                        doCsiQuestionMark(b)
                    }
                    ESC_CSI_BIGGERTHAN -> {
                        doCsiBiggerThan(b)
                    }
                    ESC_CSI_DOLLAR -> {
                        val originMode = isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)
                        val effectiveTopMargin = if (originMode) mTopMargin else 0
                        val effectiveBottomMargin = if (originMode) mBottomMargin else mRows
                        val effectiveLeftMargin = if (originMode) mLeftMargin else 0
                        val effectiveRightMargin = if (originMode) mRightMargin else mColumns
                        when (b) {
                            'v'.code -> { // ${CSI}${SRC_TOP}${SRC_LEFT}${SRC_BOTTOM}${SRC_RIGHT}${SRC_PAGE}${DST_TOP}${DST_LEFT}${DST_PAGE}$v"
                                // Copy rectangular area (DECCRA - http://vt100.net/docs/vt510-rm/DECCRA):
                                // "If Pbs is greater than Pts, or Pls is greater than Prs, the terminal ignores DECCRA.
                                // The coordinates of the rectangular area are affected by the setting of origin mode (DECOM).
                                // DECCRA is not affected by the page margins.
                                // The copied text takes on the line attributes of the destination area.
                                // If the value of Pt, Pl, Pb, or Pr exceeds the width or height of the active page, then the value
                                // is treated as the width or height of that page.
                                // If the destination area is partially off the page, then DECCRA clips the off-page data.
                                // DECCRA does not change the active cursor position."
                                val topSource = Math.min(getArg(0, 1, true) - 1 + effectiveTopMargin, mRows)
                                val leftSource = Math.min(getArg(1, 1, true) - 1 + effectiveLeftMargin, mColumns)
                                // Inclusive, so do not subtract one:
                                val bottomSource = Math.min(Math.max(getArg(2, mRows, true) + effectiveTopMargin, topSource), mRows)
                                val rightSource = Math.min(Math.max(getArg(3, mColumns, true) + effectiveLeftMargin, leftSource), mColumns)
                                // int sourcePage = getArg(4, 1, true);
                                val destionationTop = Math.min(getArg(5, 1, true) - 1 + effectiveTopMargin, mRows)
                                val destinationLeft = Math.min(getArg(6, 1, true) - 1 + effectiveLeftMargin, mColumns)
                                // int destinationPage = getArg(7, 1, true);
                                val heightToCopy = Math.min(mRows - destionationTop, bottomSource - topSource)
                                val widthToCopy = Math.min(mColumns - destinationLeft, rightSource - leftSource)
                                mScreen.blockCopy(leftSource, topSource, widthToCopy, heightToCopy, destinationLeft, destionationTop)
                            }
                            '{'.code, // ${CSI}${TOP}${LEFT}${BOTTOM}${RIGHT}${"
                            // Selective erase rectangular area (DECSERA - http://www.vt100.net/docs/vt510-rm/DECSERA).
                            'x'.code, // ${CSI}${CHAR};${TOP}${LEFT}${BOTTOM}${RIGHT}$x"
                            // Fill rectangular area (DECFRA - http://www.vt100.net/docs/vt510-rm/DECFRA).
                            'z'.code -> { // ${CSI}$${TOP}${LEFT}${BOTTOM}${RIGHT}$z"
                                // Erase rectangular area (DECERA - http://www.vt100.net/docs/vt510-rm/DECERA).
                                val erase = b != 'x'.code
                                val selective = b == '{'.code
                                // Only DECSERA keeps visual attributes, DECERA does not:
                                val keepVisualAttributes = erase && selective
                                var argIndex = 0
                                val fillChar = if (erase) ' '.code else getArg(argIndex++, -1, true)
                                // "Pch can be any value from 32 to 126 or from 160 to 255. If Pch is not in this range, then the
                                // terminal ignores the DECFRA command":
                                if ((fillChar >= 32 && fillChar <= 126) || (fillChar >= 160 && fillChar <= 255)) {
                                    // "If the value of Pt, Pl, Pb, or Pr exceeds the width or height of the active page, the value
                                    // is treated as the width or height of that page."
                                    val top = Math.min(getArg(argIndex++, 1, true) + effectiveTopMargin, effectiveBottomMargin + 1)
                                    val left = Math.min(getArg(argIndex++, 1, true) + effectiveLeftMargin, effectiveRightMargin + 1)
                                    val bottom = Math.min(getArg(argIndex++, mRows, true) + effectiveTopMargin, effectiveBottomMargin)
                                    val right = Math.min(getArg(argIndex, mColumns, true) + effectiveLeftMargin, effectiveRightMargin)
                                    val style = getStyle()
                                    for (row in top - 1 until bottom)
                                        for (col in left - 1 until right)
                                            if (!selective || (TextStyle.decodeEffect(mScreen.getStyleAt(row, col)) and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED) == 0)
                                                mScreen.setChar(col, row, fillChar, if (keepVisualAttributes) mScreen.getStyleAt(row, col) else style)
                                }
                            }
                            'r'.code, // "${CSI}${TOP}${LEFT}${BOTTOM}${RIGHT}${ATTRIBUTES}$r"
                            // Change attributes in rectangular area (DECCARA - http://vt100.net/docs/vt510-rm/DECCARA).
                            't'.code -> { // "${CSI}${TOP}${LEFT}${BOTTOM}${RIGHT}${ATTRIBUTES}$t"
                                // Reverse attributes in rectangular area (DECRARA - http://www.vt100.net/docs/vt510-rm/DECRARA).
                                val reverse = b == 't'.code
                                // FIXME: "coordinates of the rectangular area are affected by the setting of origin mode (DECOM)".
                                val top = Math.min(getArg(0, 1, true) - 1, effectiveBottomMargin) + effectiveTopMargin
                                val left = Math.min(getArg(1, 1, true) - 1, effectiveRightMargin) + effectiveLeftMargin
                                val bottom = Math.min(getArg(2, mRows, true) + 1, effectiveBottomMargin - 1) + effectiveTopMargin
                                val right = Math.min(getArg(3, mColumns, true) + 1, effectiveRightMargin - 1) + effectiveLeftMargin
                                if (mArgIndex >= 4) {
                                    if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
                                    var i = 4
                                    while (i <= mArgIndex) {
                                        var bits = 0
                                        var setOrClear = true // True if setting, false if clearing.
                                        when (getArg(i, 0, false)) {
                                            0 -> { // Attributes off (no bold, no underline, no blink, positive image).
                                                bits = (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE or TextStyle.CHARACTER_ATTRIBUTE_BLINK
                                                    or TextStyle.CHARACTER_ATTRIBUTE_INVERSE)
                                                if (!reverse) setOrClear = false
                                            }
                                            1 -> { // Bold.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD
                                            }
                                            4 -> { // Underline.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                                            }
                                            5 -> { // Blink.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BLINK
                                            }
                                            7 -> { // Negative image.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                                            }
                                            22 -> { // No bold.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BOLD
                                                setOrClear = false
                                            }
                                            24 -> { // No underline.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                                                setOrClear = false
                                            }
                                            25 -> { // No blink.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_BLINK
                                                setOrClear = false
                                            }
                                            27 -> { // Positive image.
                                                bits = TextStyle.CHARACTER_ATTRIBUTE_INVERSE
                                                setOrClear = false
                                            }
                                        }
                                        if (reverse && !setOrClear) {
                                            // Reverse attributes in rectangular area ignores non-(1,4,5,7) bits.
                                        } else {
                                            mScreen.setOrClearEffect(bits, setOrClear, reverse, isDecsetInternalBitSet(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE),
                                                effectiveLeftMargin, effectiveRightMargin, top, left, bottom, right)
                                        }
                                        i++
                                    }
                                } else {
                                    // Do nothing.
                                }
                            }
                            else -> {
                                unknownSequence(b)
                            }
                        }
                    }
                    ESC_CSI_DOUBLE_QUOTE -> {
                        if (b == 'q'.code) {
                            // http://www.vt100.net/docs/vt510-rm/DECSCA
                            val arg = getArg0(0)
                            if (arg == 0 || arg == 2) {
                                // DECSED and DECSEL can erase characters.
                                mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED.inv()
                            } else if (arg == 1) {
                                // DECSED and DECSEL cannot erase characters.
                                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_PROTECTED
                            } else {
                                unknownSequence(b)
                            }
                        } else {
                            unknownSequence(b)
                        }
                    }
                    ESC_CSI_SINGLE_QUOTE -> {
                        if (b == '}'.code) { // Insert Ps Column(s) (default = 1) (DECIC), VT420 and up.
                            val columnsAfterCursor = mRightMargin - mCursorCol
                            val columnsToInsert = Math.min(getArg0(1), columnsAfterCursor)
                            val columnsToMove = columnsAfterCursor - columnsToInsert
                            mScreen.blockCopy(mCursorCol, 0, columnsToMove, mRows, mCursorCol + columnsToInsert, 0)
                            blockClear(mCursorCol, 0, columnsToInsert, mRows)
                        } else if (b == '~'.code) { // Delete Ps Column(s) (default = 1) (DECDC), VT420 and up.
                            val columnsAfterCursor = mRightMargin - mCursorCol
                            val columnsToDelete = Math.min(getArg0(1), columnsAfterCursor)
                            val columnsToMove = columnsAfterCursor - columnsToDelete
                            mScreen.blockCopy(mCursorCol + columnsToDelete, 0, columnsToMove, mRows, mCursorCol, 0)
                        } else {
                            unknownSequence(b)
                        }
                    }
                    ESC_PERCENT -> {
                    }
                    ESC_OSC -> {
                        doOsc(b)
                    }
                    ESC_OSC_ESC -> {
                        doOscEsc(b)
                    }
                    ESC_P -> {
                        doDeviceControl(b)
                    }
                    ESC_CSI_QUESTIONMARK_ARG_DOLLAR -> {
                        if (b == 'p'.code) {
                            // Request DEC private mode (DECRQM).
                            val mode = getArg0(0)
                            val value: Int
                            if (mode == 47 || mode == 1047 || mode == 1049) {
                                // This state is carried by mScreen pointer.
                                value = if (mScreen === mAltBuffer) 1 else 2
                            } else {
                                val internalBit = mapDecSetBitToInternalBit(mode)
                                if (internalBit != -1) {
                                    value = if (isDecsetInternalBitSet(internalBit)) 1 else 2 // 1=set, 2=reset.
                                } else {
                                    Logger.logError(mClient, LOG_TAG, "Got DECRQM for unrecognized private DEC mode=" + mode)
                                    value = 0 // 0=not recognized, 3=permanently set, 4=permanently reset
                                }
                            }
                            mSession.write(String.format(Locale.US, "\u001b[?%d;%d\$y", mode, value))
                        } else {
                            unknownSequence(b)
                        }
                    }
                    ESC_CSI_ARGS_SPACE -> {
                        val arg = getArg0(0)
                        when (b) {
                            'q'.code -> { // "${CSI}${STYLE} q" - set cursor style (http://www.vt100.net/docs/vt510-rm/DECSCUSR).
                                when (arg) {
                                    0, 1, 2 -> { // Blinking block. Blinking block. Steady block.
                                        mCursorStyle = TERMINAL_CURSOR_STYLE_BLOCK
                                    }
                                    3, 4 -> { // Blinking underline. Steady underline.
                                        mCursorStyle = TERMINAL_CURSOR_STYLE_UNDERLINE
                                    }
                                    5, 6 -> { // Blinking bar (xterm addition). Steady bar (xterm addition).
                                        mCursorStyle = TERMINAL_CURSOR_STYLE_BAR
                                    }
                                }
                            }
                            't'.code, 'u'.code -> {
                                // Set margin-bell volume - ignore.
                            }
                            else -> {
                                unknownSequence(b)
                            }
                        }
                    }
                    ESC_CSI_ARGS_ASTERIX -> {
                        val attributeChangeExtent = getArg0(0)
                        if (b == 'x'.code && (attributeChangeExtent >= 0 && attributeChangeExtent <= 2)) {
                            // Select attribute change extent (DECSACE - http://www.vt100.net/docs/vt510-rm/DECSACE).
                            setDecsetinternalBit(DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE, attributeChangeExtent == 2)
                        } else {
                            unknownSequence(b)
                        }
                    }
                    else -> {
                        unknownSequence(b)
                    }
                }
                if (!mContinueSequence) mEscapeState = ESC_NONE
            }
        }
    }

    /** When in [ESC_P] ("device control") sequence. */
    private fun doDeviceControl(b: Int) {
        when (b) {
            '\\'.code -> { // End of ESC \ string Terminator
                val dcs = mOSCOrDeviceControlArgs.toString()
                // DCS $ q P t ST. Request Status String (DECRQSS)
                if (dcs.startsWith("\$q")) {
                    if (dcs == "\$q\"p") {
                        // DECSCL, conformance level, http://www.vt100.net/docs/vt510-rm/DECSCL:
                        val csiString = "64;1\"p"
                        mSession.write("\u001bP1\$r" + csiString + "\u001b\\")
                    } else {
                        finishSequenceAndLogError("Unrecognized DECRQSS string: '" + dcs + "'")
                    }
                } else if (dcs.startsWith("+q")) {
                    // Request Termcap/Terminfo String. The string following the "q" is a list of names encoded in
                    // hexadecimal (2 digits per character) separated by ; which correspond to termcap or terminfo key
                    // names.
                    // Two special features are also recognized, which are not key names: Co for termcap colors (or colors
                    // for terminfo colors), and TN for termcap name (or name for terminfo name).
                    // xterm responds with DCS 1 + r P t ST for valid requests, adding to P t an = , and the value of the
                    // corresponding string that xterm would send, or DCS 0 + r P t ST for invalid requests. The strings are
                    // encoded in hexadecimal (2 digits per character).
                    // Example:
                    // :kr=\EOC: ks=\E[?1h\E=: ku=\EOA: le=^H:mb=\E[5m:md=\E[1m:\
                    // where
                    // kd=down-arrow key
                    // kl=left-arrow key
                    // kr=right-arrow key
                    // ku=up-arrow key
                    // #2=key_shome, "shifted home"
                    // #4=key_sleft, "shift arrow left"
                    // %i=key_sright, "shift arrow right"
                    // *7=key_send, "shifted end"
                    // k1=F1 function key

                    // Example: Request for ku is "ESC P + q 6 b 7 5 ESC \", where 6b7d=ku in hexadecimal.
                    // Xterm response in normal cursor mode:
                    // "<27> P 1 + r 6 b 7 5 = 1 B 5 B 4 1" where 0x1B 0x5B 0x41 = 27 91 65 = ESC [ A
                    // Xterm response in application cursor mode:
                    // "<27> P 1 + r 6 b 7 5 = 1 B 5 B 4 1" where 0x1B 0x4F 0x41 = 27 91 65 = ESC 0 A

                    // #4 is "shift arrow left":
                    // *** Device Control (DCS) for '#4'- 'ESC P + q 23 34 ESC \'
                    // Response: <27> P 1 + r 2 3 3 4 = 1 B 5 B 3 1 3 B 3 2 4 4 <27> \
                    // where 0x1B 0x5B 0x31 0x3B 0x32 0x44 = ESC [ 1 ; 2 D
                    // which we find in: TermKeyListener.java: KEY_MAP.put(KEYMOD_SHIFT | KEYCODE_DPAD_LEFT, "\033[1;2D");

                    // See http://h30097.www3.hp.com/docs/base_doc/DOCUMENTATION/V40G_HTML/MAN/MAN4/0178____.HTM for what to
                    // respond, as well as http://www.freebsd.org/cgi/man.cgi?query=termcap&sektion=5#CAPABILITIES for
                    // the meaning of e.g. "ku", "kd", "kr", "kl"

                    for (part in dcs.substring(2).jsplit(';')) {
                        if (part.length % 2 == 0) {
                            val transBuffer = StringBuilder()
                            for (i in 0 until part.length step 2) {
                                val c: Char
                                try {
                                    c = java.lang.Long.decode("0x" + part[i] + "" + part[i + 1]).toLong().toInt().toChar()
                                } catch (e: NumberFormatException) {
                                    Logger.logStackTraceWithMessage(mClient, LOG_TAG, "Invalid device termcap/terminfo encoded name \"" + part + "\"", e)
                                    continue
                                }
                                transBuffer.append(c)
                            }

                            val trans = transBuffer.toString()
                            val responseValue: String? = when (trans) {
                                "Co", "colors" -> "256" // Number of colors.
                                "TN", "name" -> "xterm"
                                else -> KeyHandler.getCodeFromTermcap(trans, isDecsetInternalBitSet(DECSET_BIT_APPLICATION_CURSOR_KEYS),
                                    isDecsetInternalBitSet(DECSET_BIT_APPLICATION_KEYPAD))
                            }
                            if (responseValue == null) {
                                when (trans) {
                                    "%1", // Help key - ignore
                                    "&8" -> { // Undo key - ignore.
                                    }
                                    else -> {
                                        Logger.logWarn(mClient, LOG_TAG, "Unhandled termcap/terminfo name: '" + trans + "'")
                                    }
                                }
                                // Respond with invalid request:
                                mSession.write("\u001bP0+r" + part + "\u001b\\")
                            } else {
                                val hexEncoded = StringBuilder()
                                for (j in 0 until responseValue.length) {
                                    hexEncoded.append(String.format("%02X", responseValue[j].code))
                                }
                                mSession.write("\u001bP1+r" + part + "=" + hexEncoded + "\u001b\\")
                            }
                        } else {
                            Logger.logError(mClient, LOG_TAG, "Invalid device termcap/terminfo name of odd length: " + part)
                        }
                    }
                } else {
                    if (LOG_ESCAPE_SEQUENCES)
                        Logger.logError(mClient, LOG_TAG, "Unrecognized device control string: " + dcs)
                }
                finishSequence()
            }
            else -> {
                if (mOSCOrDeviceControlArgs.length > MAX_OSC_STRING_LENGTH) {
                    // Too long.
                    mOSCOrDeviceControlArgs.setLength(0)
                    finishSequence()
                } else {
                    mOSCOrDeviceControlArgs.appendCodePoint(b)
                    continueSequence(mEscapeState)
                }
            }
        }
    }

    /**
     * When in [ESC_APC] (APC, Application Program Command) sequence.
     */
    private fun doApc(b: Int) {
        if (b == 27) {
            continueSequence(ESC_APC_ESCAPE)
        }
        // Eat APC sequences silently for now.
    }

    /**
     * When in [ESC_APC] (APC, Application Program Command) sequence.
     */
    private fun doApcEscape(b: Int) {
        if (b == '\\'.code) {
            // A String Terminator (ST), ending the APC escape sequence.
            finishSequence()
        } else {
            // The Escape character was not the start of a String Terminator (ST),
            // but instead just data inside of the APC escape sequence.
            continueSequence(ESC_APC)
        }
    }

    private fun nextTabStop(numTabs: Int): Int {
        var numTabs = numTabs
        for (i in mCursorCol + 1 until mColumns)
            if (mTabStop[i] && --numTabs == 0) return Math.min(i, mRightMargin)
        return mRightMargin - 1
    }

    /**
     * Process byte while in the [ESC_CSI_UNSUPPORTED_PARAMETER_BYTE] or
     * [ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE] escape state.
     *
     * Parse unsupported parameter, intermediate and final bytes but ignore them.
     *
     * > For Control Sequence Introducer, ... the ESC [ is followed by
     * > - any number (including none) of "parameter bytes" in the range 0x30–0x3F (ASCII 0–9:;<=>?),
     * > - then by any number of "intermediate bytes" in the range 0x20–0x2F (ASCII space and !"#$%&'()*+,-./),
     * > - then finally by a single "final byte" in the range 0x40–0x7E (ASCII @A–Z[\]^_`a–z{|}~).
     *
     * - https://en.wikipedia.org/wiki/ANSI_escape_code#Control_Sequence_Introducer_commands
     * - https://invisible-island.net/xterm/ecma-48-parameter-format.html#section5.4
     */
    private fun doCsiUnsupportedParameterOrIntermediateByte(b: Int) {
        if (mEscapeState == ESC_CSI_UNSUPPORTED_PARAMETER_BYTE && b >= 0x30 && b <= 0x3F) {
            // Supported `0–9:;>?` or unsupported `<=` parameter byte after an
            // initial unsupported parameter byte in `doCsi()`, or a sequential parameter byte.
            continueSequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE)
        } else if (b >= 0x20 && b <= 0x2F) {
            // Optional intermediate byte `!"#$%&'()*+,-./` after parameter or intermediate byte.
            continueSequence(ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE)
        } else if (b >= 0x40 && b <= 0x7E) {
            // Final byte `@A–Z[\]^_`a–z{|}~` after parameter or intermediate byte.
            // Calling `unknownSequence()` would log an error with only a final byte, so ignore it for now.
            finishSequence()
        } else {
            unknownSequence(b)
        }
    }

    /** Process byte while in the [ESC_CSI_QUESTIONMARK] escape state. */
    private fun doCsiQuestionMark(b: Int) {
        when (b) {
            'J'.code, // Selective erase in display (DECSED) - http://www.vt100.net/docs/vt510-rm/DECSED.
            'K'.code -> { // Selective erase in line (DECSEL) - http://vt100.net/docs/vt510-rm/DECSEL.
                mAboutToAutoWrap = false
                val fillChar = ' '.code
                var startCol = -1
                var startRow = -1
                var endCol = -1
                var endRow = -1
                val justRow = (b == 'K'.code)
                when (getArg0(0)) {
                    0 -> { // Erase from the active position to the end, inclusive (default).
                        startCol = mCursorCol
                        startRow = mCursorRow
                        endCol = mColumns
                        endRow = if (justRow) (mCursorRow + 1) else mRows
                    }
                    1 -> { // Erase from start to the active position, inclusive.
                        startCol = 0
                        startRow = if (justRow) mCursorRow else 0
                        endCol = mCursorCol + 1
                        endRow = mCursorRow + 1
                    }
                    2 -> { // Erase all of the display/line.
                        startCol = 0
                        startRow = if (justRow) mCursorRow else 0
                        endCol = mColumns
                        endRow = if (justRow) (mCursorRow + 1) else mRows
                    }
                    else -> {
                        unknownSequence(b)
                    }
                }
                val style = getStyle()
                for (row in startRow until endRow) {
                    for (col in startCol until endCol) {
                        if ((TextStyle.decodeEffect(mScreen.getStyleAt(row, col)) and TextStyle.CHARACTER_ATTRIBUTE_PROTECTED) == 0)
                            mScreen.setChar(col, row, fillChar, style)
                    }
                }
            }
            'h'.code, 'l'.code -> {
                if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
                var i = 0
                while (i <= mArgIndex) {
                    doDecSetOrReset(b == 'h'.code, mArgs[i])
                    i++
                }
            }
            'n'.code -> { // Device Status Report (DSR, DEC-specific).
                when (getArg0(-1)) {
                    6 -> {
                        // Extended Cursor Position (DECXCPR - http://www.vt100.net/docs/vt510-rm/DECXCPR). Page=1.
                        mSession.write(String.format(Locale.US, "\u001b[?%d;%d;1R", mCursorRow + 1, mCursorCol + 1))
                    }
                    else -> {
                        finishSequence()
                        return
                    }
                }
            }
            'r'.code, 's'.code -> {
                if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
                var i = 0
                while (i <= mArgIndex) {
                    val externalBit = mArgs[i]
                    val internalBit = mapDecSetBitToInternalBit(externalBit)
                    if (internalBit == -1) {
                        Logger.logWarn(mClient, LOG_TAG, "Ignoring request to save/recall decset bit=" + externalBit)
                    } else {
                        if (b == 's'.code) {
                            mSavedDecSetFlags = mSavedDecSetFlags or internalBit
                        } else {
                            doDecSetOrReset((mSavedDecSetFlags and internalBit) != 0, externalBit)
                        }
                    }
                    i++
                }
            }
            '$'.code -> {
                continueSequence(ESC_CSI_QUESTIONMARK_ARG_DOLLAR)
                return
            }
            else -> {
                parseArg(b)
            }
        }
    }

    fun doDecSetOrReset(setting: Boolean, externalBit: Int) {
        val internalBit = mapDecSetBitToInternalBit(externalBit)
        if (internalBit != -1) {
            setDecsetinternalBit(internalBit, setting)
        }
        when (externalBit) {
            1 -> { // Application Cursor Keys (DECCKM).
            }
            3 -> { // Set: 132 column mode (. Reset: 80 column mode. ANSI name: DECCOLM.
                // We don't actually set/reset 132 cols, but we do want the side effects
                // (FIXME: Should only do this if the 95 DECSET bit (DECNCSM) is set, and if changing value?):
                // Sets the left, right, top and bottom scrolling margins to their default positions, which is important for
                // the "reset" utility to really reset the terminal:
                mTopMargin = 0
                mLeftMargin = 0
                mBottomMargin = mRows
                mRightMargin = mColumns
                // "DECCOLM resets vertical split screen mode (DECLRMM) to unavailable":
                setDecsetinternalBit(DECSET_BIT_LEFTRIGHT_MARGIN_MODE, false)
                // "Erases all data in page memory":
                blockClear(0, 0, mColumns, mRows)
                setCursorRowCol(0, 0)
            }
            4 -> { // DECSCLM-Scrolling Mode. Ignore.
            }
            5 -> { // Reverse video. No action.
            }
            6 -> { // Set: Origin Mode. Reset: Normal Cursor Mode. Ansi name: DECOM.
                if (setting) setCursorPosition(0, 0)
            }
            7, // Wrap-around bit, not specific action.
            8, // Auto-repeat Keys (DECARM). Do not implement.
            9, // X10 mouse reporting - outdated. Do not implement.
            12, // Control cursor blinking - ignore.
            25 -> { // Hide/show cursor - no action needed, renderer will check with shouldCursorBeVisible().
                val client = mClient
                if (client != null)
                    client.onTerminalCursorStateChange(setting)
            }
            40, // Allow 80 => 132 Mode, ignore.
            45, // TODO: Reverse wrap-around. Implement???
            66 -> { // Application keypad (DECNKM).
            }
            69 -> { // Left and right margin mode (DECLRMM).
                if (!setting) {
                    mLeftMargin = 0
                    mRightMargin = mColumns
                }
            }
            1000,
            1001,
            1002,
            1003,
            1004,
            1005, // UTF-8 mouse mode, ignore.
            1006, // SGR Mouse Mode
            1015,
            1034 -> { // Interpret "meta" key, sets eighth bit.
            }
            1048 -> { // Set: Save cursor as in DECSC. Reset: Restore cursor as in DECRC.
                if (setting)
                    saveCursor()
                else
                    restoreCursor()
            }
            47, 1047, 1049 -> {
                // Set: Save cursor as in DECSC and use Alternate Screen Buffer, clearing it first.
                // Reset: Use Normal Screen Buffer and restore cursor as in DECRC.
                val newScreen = if (setting) mAltBuffer else mMainBuffer
                if (newScreen !== mScreen) {
                    val resized = !(newScreen.mColumns == mColumns && newScreen.mScreenRows == mRows)
                    if (setting) saveCursor()
                    mScreen = newScreen
                    if (!setting) {
                        val col = mSavedStateMain.mSavedCursorCol
                        val row = mSavedStateMain.mSavedCursorRow
                        restoreCursor()
                        if (resized) {
                            // Restore cursor position _not_ clipped to current screen (let resizeScreen() handle that):
                            mCursorCol = col
                            mCursorRow = row
                        }
                    }
                    // Check if buffer size needs to be updated:
                    if (resized) resizeScreen()
                    // Clear new screen if alt buffer:
                    if (newScreen === mAltBuffer)
                        newScreen.blockSet(0, 0, mColumns, mRows, ' '.code, getStyle())
                }
            }
            2004 -> {
                // Bracketed paste mode - setting bit is enough.
            }
            else -> {
                unknownParameter(externalBit)
            }
        }
    }

    private fun doCsiBiggerThan(b: Int) {
        when (b) {
            'c'.code -> { // "${CSI}>c" or "${CSI}>c". Secondary Device Attributes (DA2).
                // Originally this was used for the terminal to respond with "identification code, firmware version level,
                // and hardware options" (http://vt100.net/docs/vt510-rm/DA2), with the first "41" meaning the VT420
                // terminal type. This is not used anymore, but the second version level field has been changed by xterm
                // to mean it's release number ("patch numbers" listed at http://invisible-island.net/xterm/xterm.log.html),
                // and some applications use it as a feature check:
                // * tmux used to have a "xterm won't reach version 500 for a while so set that as the upper limit" check,
                // and then check "xterm_version > 270" if rectangular area operations such as DECCRA could be used.
                // * vim checks xterm version number >140 for "Request termcap/terminfo string" functionality >276 for SGR
                // mouse report.
                // The third number is a keyboard identifier not used nowadays.
                mSession.write("\u001b[>41;320;0c")
            }
            'm'.code -> {
                // https://bugs.launchpad.net/gnome-terminal/+bug/96676/comments/25
                // Depending on the first number parameter, this can set one of the xterm resources
                // modifyKeyboard, modifyCursorKeys, modifyFunctionKeys and modifyOtherKeys.
                // http://invisible-island.net/xterm/manpage/xterm.html#RESOURCES

                // * modifyKeyboard (parameter=1):
                // Normally xterm makes a special case regarding modifiers (shift, control, etc.) to handle special keyboard
                // layouts (legacy and vt220). This is done to provide compatible keyboards for DEC VT220 and related
                // terminals that implement user-defined keys (UDK).
                // The bits of the resource value selectively enable modification of the given category when these keyboards
                // are selected. The default is "0":
                // (0) The legacy/vt220 keyboards interpret only the Control-modifier when constructing numbered
                // function-keys. Other special keys are not modified.
                // (1) allows modification of the numeric keypad
                // (2) allows modification of the editing keypad
                // (4) allows modification of function-keys, overrides use of Shift-modifier for UDK.
                // (8) allows modification of other special keys

                // * modifyCursorKeys (parameter=2):
                // Tells how to handle the special case where Control-, Shift-, Alt- or Meta-modifiers are used to add a
                // parameter to the escape sequence returned by a cursor-key. The default is "2".
                // - Set it to -1 to disable it.
                // - Set it to 0 to use the old/obsolete behavior.
                // - Set it to 1 to prefix modified sequences with CSI.
                // - Set it to 2 to force the modifier to be the second parameter if it would otherwise be the first.
                // - Set it to 3 to mark the sequence with a ">" to hint that it is private.

                // * modifyFunctionKeys (parameter=3):
                // Tells how to handle the special case where Control-, Shift-, Alt- or Meta-modifiers are used to add a
                // parameter to the escape sequence returned by a (numbered) function-
                // key. The default is "2". The resource values are similar to modifyCursorKeys:
                // Set it to -1 to permit the user to use shift- and control-modifiers to construct function-key strings
                // using the normal encoding scheme.
                // - Set it to 0 to use the old/obsolete behavior.
                // - Set it to 1 to prefix modified sequences with CSI.
                // - Set it to 2 to force the modifier to be the second parameter if it would otherwise be the first.
                // - Set it to 3 to mark the sequence with a ">" to hint that it is private.
                // If modifyFunctionKeys is zero, xterm uses Control- and Shift-modifiers to allow the user to construct
                // numbered function-keys beyond the set provided by the keyboard:
                // (Control) adds the value given by the ctrlFKeys resource.
                // (Shift) adds twice the value given by the ctrlFKeys resource.
                // (Control/Shift) adds three times the value given by the ctrlFKeys resource.
                //
                // As a special case, legacy (when oldFunctionKeys is true) or vt220 (when sunKeyboard is true)
                // keyboards interpret only the Control-modifier when constructing numbered function-keys.
                // This is done to provide compatible keyboards for DEC VT220 and related terminals that
                // implement user-defined keys (UDK).

                // * modifyOtherKeys (parameter=4):
                // Like modifyCursorKeys, tells xterm to construct an escape sequence for other keys (such as "2") when
                // modified by Control-, Alt- or Meta-modifiers. This feature does not apply to function keys and
                // well-defined keys such as ESC or the control keys. The default is "0".
                // (0) disables this feature.
                // (1) enables this feature for keys except for those with well-known behavior, e.g., Tab, Backarrow and
                // some special control character cases, e.g., Control-Space to make a NUL.
                // (2) enables this feature for keys including the exceptions listed.
                Logger.logError(mClient, LOG_TAG, "(ignored) CSI > MODIFY RESOURCE: " + getArg0(-1) + " to " + getArg1(-1))
            }
            else -> {
                parseArg(b)
            }
        }
    }

    private fun startEscapeSequence() {
        mEscapeState = ESC
        mArgIndex = 0
        Arrays.fill(mArgs, -1)
        mArgsSubParamsBitSet = 0
    }

    private fun doLinefeed() {
        val belowScrollingRegion = mCursorRow >= mBottomMargin
        var newCursorRow = mCursorRow + 1
        if (belowScrollingRegion) {
            // Move down (but not scroll) as long as we are above the last row.
            if (mCursorRow != mRows - 1) {
                setCursorRow(newCursorRow)
            }
        } else {
            if (newCursorRow == mBottomMargin) {
                scrollDownOneLine()
                newCursorRow = mBottomMargin - 1
            }
            setCursorRow(newCursorRow)
        }
    }

    private fun continueSequence(state: Int) {
        mEscapeState = state
        mContinueSequence = true
    }

    private fun doEscPound(b: Int) {
        when (b) {
            '8'.code -> { // Esc # 8 - DEC screen alignment test - fill screen with E's.
                mScreen.blockSet(0, 0, mColumns, mRows, 'E'.code, getStyle())
            }
            else -> {
                unknownSequence(b)
            }
        }
    }

    /** Encountering a character in the [ESC] state. */
    private fun doEsc(b: Int) {
        when (b) {
            '#'.code -> continueSequence(ESC_POUND)
            '('.code -> continueSequence(ESC_SELECT_LEFT_PAREN)
            ')'.code -> continueSequence(ESC_SELECT_RIGHT_PAREN)
            '6'.code -> { // Back index (http://www.vt100.net/docs/vt510-rm/DECBI). Move left, insert blank column if start.
                if (mCursorCol > mLeftMargin) {
                    mCursorCol--
                } else {
                    val rows = mBottomMargin - mTopMargin
                    mScreen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin + 1, mTopMargin)
                    mScreen.blockSet(mLeftMargin, mTopMargin, 1, rows, ' '.code, TextStyle.encode(mForeColor, mBackColor, 0))
                }
            }
            '7'.code -> { // DECSC save cursor - http://www.vt100.net/docs/vt510-rm/DECSC
                saveCursor()
            }
            '8'.code -> { // DECRC restore cursor - http://www.vt100.net/docs/vt510-rm/DECRC
                restoreCursor()
            }
            '9'.code -> { // Forward Index (http://www.vt100.net/docs/vt510-rm/DECFI). Move right, insert blank column if end.
                if (mCursorCol < mRightMargin - 1) {
                    mCursorCol++
                } else {
                    val rows = mBottomMargin - mTopMargin
                    mScreen.blockCopy(mLeftMargin + 1, mTopMargin, mRightMargin - mLeftMargin - 1, rows, mLeftMargin, mTopMargin)
                    mScreen.blockSet(mRightMargin - 1, mTopMargin, 1, rows, ' '.code, TextStyle.encode(mForeColor, mBackColor, 0))
                }
            }
            'c'.code -> { // RIS - Reset to Initial State (http://vt100.net/docs/vt510-rm/RIS).
                reset()
                mMainBuffer.clearTranscript()
                blockClear(0, 0, mColumns, mRows)
                setCursorPosition(0, 0)
            }
            'D'.code -> { // INDEX
                doLinefeed()
            }
            'E'.code -> { // Next line (http://www.vt100.net/docs/vt510-rm/NEL).
                setCursorCol(if (isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)) mLeftMargin else 0)
                doLinefeed()
            }
            'F'.code -> { // Cursor to lower-left corner of screen
                setCursorRowCol(0, mBottomMargin - 1)
            }
            'H'.code -> { // Tab set
                mTabStop[mCursorCol] = true
            }
            'M'.code -> { // "${ESC}M" - reverse index (RI).
                // http://www.vt100.net/docs/vt100-ug/chapter3.html: "Move the active position to the same horizontal
                // position on the preceding line. If the active position is at the top margin, a scroll down is performed".
                if (mCursorRow <= mTopMargin) {
                    mScreen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, mBottomMargin - (mTopMargin + 1), mLeftMargin, mTopMargin + 1)
                    blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin)
                } else {
                    mCursorRow--
                }
            }
            'N'.code, // SS2, ignore.
            '0'.code -> { // SS3, ignore.
            }
            'P'.code -> { // Device control string
                mOSCOrDeviceControlArgs.setLength(0)
                continueSequence(ESC_P)
            }
            '['.code -> continueSequence(ESC_CSI)
            '='.code -> { // DECKPAM
                setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, true)
            }
            ']'.code -> { // OSC
                mOSCOrDeviceControlArgs.setLength(0)
                continueSequence(ESC_OSC)
            }
            '>'.code -> { // DECKPNM
                setDecsetinternalBit(DECSET_BIT_APPLICATION_KEYPAD, false)
            }
            '_'.code -> { // APC - Application Program Command.
                continueSequence(ESC_APC)
            }
            else -> {
                unknownSequence(b)
            }
        }
    }

    /** DECSC save cursor - http://www.vt100.net/docs/vt510-rm/DECSC . See [restoreCursor]. */
    private fun saveCursor() {
        val state = if (mScreen === mMainBuffer) mSavedStateMain else mSavedStateAlt
        state.mSavedCursorRow = mCursorRow
        state.mSavedCursorCol = mCursorCol
        state.mSavedEffect = mEffect
        state.mSavedForeColor = mForeColor
        state.mSavedBackColor = mBackColor
        state.mSavedDecFlags = mCurrentDecSetFlags
        state.mUseLineDrawingG0 = mUseLineDrawingG0
        state.mUseLineDrawingG1 = mUseLineDrawingG1
        state.mUseLineDrawingUsesG0 = mUseLineDrawingUsesG0
    }

    /** DECRS restore cursor - http://www.vt100.net/docs/vt510-rm/DECRC. See [saveCursor]. */
    private fun restoreCursor() {
        val state = if (mScreen === mMainBuffer) mSavedStateMain else mSavedStateAlt
        setCursorRowCol(state.mSavedCursorRow, state.mSavedCursorCol)
        mEffect = state.mSavedEffect
        mForeColor = state.mSavedForeColor
        mBackColor = state.mSavedBackColor
        val mask = (DECSET_BIT_AUTOWRAP or DECSET_BIT_ORIGIN_MODE)
        mCurrentDecSetFlags = (mCurrentDecSetFlags and mask.inv()) or (state.mSavedDecFlags and mask)
        mUseLineDrawingG0 = state.mUseLineDrawingG0
        mUseLineDrawingG1 = state.mUseLineDrawingG1
        mUseLineDrawingUsesG0 = state.mUseLineDrawingUsesG0
    }

    /** Following a CSI - Control Sequence Introducer, "\033[". [ESC_CSI]. */
    private fun doCsi(b: Int) {
        when (b) {
            '!'.code -> continueSequence(ESC_CSI_EXCLAMATION)
            '"'.code -> continueSequence(ESC_CSI_DOUBLE_QUOTE)
            '\''.code -> continueSequence(ESC_CSI_SINGLE_QUOTE)
            '$'.code -> continueSequence(ESC_CSI_DOLLAR)
            '*'.code -> continueSequence(ESC_CSI_ARGS_ASTERIX)
            '@'.code -> {
                // "CSI{n}@" - Insert ${n} space characters (ICH) - http://www.vt100.net/docs/vt510-rm/ICH.
                mAboutToAutoWrap = false
                val columnsAfterCursor = mColumns - mCursorCol
                val spacesToInsert = Math.min(getArg0(1), columnsAfterCursor)
                val charsToMove = columnsAfterCursor - spacesToInsert
                mScreen.blockCopy(mCursorCol, mCursorRow, charsToMove, 1, mCursorCol + spacesToInsert, mCursorRow)
                blockClear(mCursorCol, mCursorRow, spacesToInsert)
            }
            'A'.code -> { // "CSI${n}A" - Cursor up (CUU) ${n} rows.
                setCursorRow(Math.max(0, mCursorRow - getArg0(1)))
            }
            'B'.code -> { // "CSI${n}B" - Cursor down (CUD) ${n} rows.
                setCursorRow(Math.min(mRows - 1, mCursorRow + getArg0(1)))
            }
            'C'.code, // "CSI${n}C" - Cursor forward (CUF).
            'a'.code -> { // "CSI${n}a" - Horizontal position relative (HPR). From ISO-6428/ECMA-48.
                setCursorCol(Math.min(mRightMargin - 1, mCursorCol + getArg0(1)))
            }
            'D'.code -> { // "CSI${n}D" - Cursor backward (CUB) ${n} columns.
                setCursorCol(Math.max(mLeftMargin, mCursorCol - getArg0(1)))
            }
            'E'.code -> { // "CSI{n}E - Cursor Next Line (CNL). From ISO-6428/ECMA-48.
                setCursorPosition(0, mCursorRow + getArg0(1))
            }
            'F'.code -> { // "CSI{n}F - Cursor Previous Line (CPL). From ISO-6428/ECMA-48.
                setCursorPosition(0, mCursorRow - getArg0(1))
            }
            'G'.code -> { // "CSI${n}G" - Cursor horizontal absolute (CHA) to column ${n}.
                setCursorCol(Math.min(Math.max(1, getArg0(1)), mColumns) - 1)
            }
            'H'.code, // "${CSI}${ROW};${COLUMN}H" - Cursor position (CUP).
            'f'.code -> { // "${CSI}${ROW};${COLUMN}f" - Horizontal and Vertical Position (HVP).
                setCursorPosition(getArg1(1) - 1, getArg0(1) - 1)
            }
            'I'.code -> { // Cursor Horizontal Forward Tabulation (CHT). Move the active position n tabs forward.
                setCursorCol(nextTabStop(getArg0(1)))
            }
            'J'.code -> { // "${CSI}${0,1,2,3}J" - Erase in Display (ED)
                // ED ignores the scrolling margins.
                when (getArg0(0)) {
                    0 -> { // Erase from the active position to the end of the screen, inclusive (default).
                        blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol)
                        blockClear(0, mCursorRow + 1, mColumns, mRows - (mCursorRow + 1))
                    }
                    1 -> { // Erase from start of the screen to the active position, inclusive.
                        blockClear(0, 0, mColumns, mCursorRow)
                        blockClear(0, mCursorRow, mCursorCol + 1)
                    }
                    2 -> { // Erase all of the display - all lines are erased, changed to single-width, and the cursor does not
                        // move..
                        blockClear(0, 0, mColumns, mRows)
                    }
                    3 -> { // Delete all lines saved in the scrollback buffer (xterm etc)
                        mMainBuffer.clearTranscript()
                    }
                    else -> {
                        unknownSequence(b)
                        return
                    }
                }
                mAboutToAutoWrap = false
            }
            'K'.code -> { // "CSI{n}K" - Erase in line (EL).
                when (getArg0(0)) {
                    0 -> { // Erase from the cursor to the end of the line, inclusive (default)
                        blockClear(mCursorCol, mCursorRow, mColumns - mCursorCol)
                    }
                    1 -> { // Erase from the start of the screen to the cursor, inclusive.
                        blockClear(0, mCursorRow, mCursorCol + 1)
                    }
                    2 -> { // Erase all of the line.
                        blockClear(0, mCursorRow, mColumns)
                    }
                    else -> {
                        unknownSequence(b)
                        return
                    }
                }
                mAboutToAutoWrap = false
            }
            'L'.code -> { // "${CSI}{N}L" - insert ${N} lines (IL).
                val linesAfterCursor = mBottomMargin - mCursorRow
                val linesToInsert = Math.min(getArg0(1), linesAfterCursor)
                val linesToMove = linesAfterCursor - linesToInsert
                mScreen.blockCopy(0, mCursorRow, mColumns, linesToMove, 0, mCursorRow + linesToInsert)
                blockClear(0, mCursorRow, mColumns, linesToInsert)
            }
            'M'.code -> { // "${CSI}${N}M" - delete N lines (DL).
                mAboutToAutoWrap = false
                val linesAfterCursor = mBottomMargin - mCursorRow
                val linesToDelete = Math.min(getArg0(1), linesAfterCursor)
                val linesToMove = linesAfterCursor - linesToDelete
                mScreen.blockCopy(0, mCursorRow + linesToDelete, mColumns, linesToMove, 0, mCursorRow)
                blockClear(0, mCursorRow + linesToMove, mColumns, linesToDelete)
            }
            'P'.code -> { // "${CSI}{N}P" - delete ${N} characters (DCH).
                // http://www.vt100.net/docs/vt510-rm/DCH: "If ${N} is greater than the number of characters between the
                // cursor and the right margin, then DCH only deletes the remaining characters.
                // As characters are deleted, the remaining characters between the cursor and right margin move to the left.
                // Character attributes move with the characters. The terminal adds blank spaces with no visual character
                // attributes at the right margin. DCH has no effect outside the scrolling margins."
                mAboutToAutoWrap = false
                val cellsAfterCursor = mColumns - mCursorCol
                val cellsToDelete = Math.min(getArg0(1), cellsAfterCursor)
                val cellsToMove = cellsAfterCursor - cellsToDelete
                mScreen.blockCopy(mCursorCol + cellsToDelete, mCursorRow, cellsToMove, 1, mCursorCol, mCursorRow)
                blockClear(mCursorCol + cellsToMove, mCursorRow, cellsToDelete)
            }
            'S'.code -> { // "${CSI}${N}S" - scroll up ${N} lines (default = 1) (SU).
                val linesToScroll = getArg0(1)
                var i = 0
                while (i < linesToScroll) {
                    scrollDownOneLine()
                    i++
                }
            }
            'T'.code -> {
                if (mArgIndex == 0) {
                    // "${CSI}${N}T" - Scroll down N lines (default = 1) (SD).
                    // http://vt100.net/docs/vt510-rm/SD: "N is the number of lines to move the user window up in page
                    // memory. N new lines appear at the top of the display. N old lines disappear at the bottom of the
                    // display. You cannot pan past the top margin of the current page".
                    val linesToScrollArg = getArg0(1)
                    val linesBetweenTopAndBottomMargins = mBottomMargin - mTopMargin
                    val linesToScroll = Math.min(linesBetweenTopAndBottomMargins, linesToScrollArg)
                    mScreen.blockCopy(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, linesBetweenTopAndBottomMargins - linesToScroll, mLeftMargin, mTopMargin + linesToScroll)
                    blockClear(mLeftMargin, mTopMargin, mRightMargin - mLeftMargin, linesToScroll)
                } else {
                    // "${CSI}${func};${startx};${starty};${firstrow};${lastrow}T" - initiate highlight mouse tracking.
                    unimplementedSequence(b)
                }
            }
            'X'.code -> { // "${CSI}${N}X" - Erase ${N:=1} character(s) (ECH). FIXME: Clears character attributes?
                mAboutToAutoWrap = false
                mScreen.blockSet(mCursorCol, mCursorRow, Math.min(getArg0(1), mColumns - mCursorCol), 1, ' '.code, getStyle())
            }
            'Z'.code -> { // Cursor Backward Tabulation (CBT). Move the active position n tabs backward.
                var numberOfTabs = getArg0(1)
                var newCol = mLeftMargin
                var i = mCursorCol - 1
                while (i >= 0) {
                    if (mTabStop[i]) {
                        if (--numberOfTabs == 0) {
                            newCol = Math.max(i, mLeftMargin)
                            break
                        }
                    }
                    i--
                }
                mCursorCol = newCol
            }
            '?'.code -> { // Esc [ ? -- start of a private parameter byte
                continueSequence(ESC_CSI_QUESTIONMARK)
            }
            '>'.code -> { // "Esc [ >" -- start of a private parameter byte
                continueSequence(ESC_CSI_BIGGERTHAN)
            }
            '<'.code, // "Esc [ <" -- start of a private parameter byte
            '='.code -> { // "Esc [ =" -- start of a private parameter byte
                continueSequence(ESC_CSI_UNSUPPORTED_PARAMETER_BYTE)
            }
            '`'.code -> { // Horizontal position absolute (HPA - http://www.vt100.net/docs/vt510-rm/HPA).
                setCursorColRespectingOriginMode(getArg0(1) - 1)
            }
            'b'.code -> { // Repeat the preceding graphic character Ps times (REP).
                if (mLastEmittedCodePoint != -1) {
                    val numRepeat = getArg0(1)
                    var i = 0
                    while (i < numRepeat) {
                        emitCodePoint(mLastEmittedCodePoint)
                        i++
                    }
                }
            }
            'c'.code -> { // Primary Device Attributes (http://www.vt100.net/docs/vt510-rm/DA1) if argument is missing or zero.
                // The important part that may still be used by some (tmux stores this value but does not currently use it)
                // is the first response parameter identifying the terminal service class, where we send 64 for "vt420".
                // This is followed by a list of attributes which is probably unused by applications. Send like xterm.
                if (getArg0(0) == 0) mSession.write("\u001b[?64;1;2;6;9;15;18;21;22c")
            }
            'd'.code -> { // ESC [ Pn d - Vert Position Absolute
                setCursorRow(Math.min(Math.max(1, getArg0(1)), mRows) - 1)
            }
            'e'.code -> { // Vertical Position Relative (VPR). From ISO-6429 (ECMA-48).
                setCursorPosition(mCursorCol, mCursorRow + getArg0(1))
            }
            // case 'f': "${CSI}${ROW};${COLUMN}f" - Horizontal and Vertical Position (HVP). Grouped with case 'H'.
            'g'.code -> { // Clear tab stop
                when (getArg0(0)) {
                    0 -> {
                        mTabStop[mCursorCol] = false
                    }
                    3 -> {
                        var i = 0
                        while (i < mColumns) {
                            mTabStop[i] = false
                            i++
                        }
                    }
                    else -> {
                        // Specified to have no effect.
                    }
                }
            }
            'h'.code -> { // Set Mode
                doSetMode(true)
            }
            'l'.code -> { // Reset Mode
                doSetMode(false)
            }
            'm'.code -> { // Esc [ Pn m - character attributes. (can have up to 16 numerical arguments)
                selectGraphicRendition()
            }
            'n'.code -> { // Esc [ Pn n - ECMA-48 Status Report Commands
                // sendDeviceAttributes()
                when (getArg0(0)) {
                    5 -> { // Device status report (DSR):
                        // Answer is ESC [ 0 n (Terminal OK).
                        val dsr = byteArrayOf(27.toByte(), '['.code.toByte(), '0'.code.toByte(), 'n'.code.toByte())
                        mSession.write(dsr, 0, dsr.size)
                    }
                    6 -> { // Cursor position report (CPR):
                        // Answer is ESC [ y ; x R, where x,y is
                        // the cursor location.
                        mSession.write(String.format(Locale.US, "\u001b[%d;%dR", mCursorRow + 1, mCursorCol + 1))
                    }
                    else -> {
                    }
                }
            }
            'r'.code -> { // "CSI${top};${bottom}r" - set top and bottom Margins (DECSTBM).
                // https://vt100.net/docs/vt510-rm/DECSTBM.html
                // The top margin defaults to 1, the bottom margin defaults to mRows.
                // The escape sequence numbers top 1..23, but we number top 0..22.
                // The escape sequence numbers bottom 2..24, and so do we (because we use a zero based numbering
                // scheme, but we store the first line below the bottom-most scrolling line.
                // As a result, we adjust the top line by -1, but we leave the bottom line alone.
                // Also require that top + 2 <= bottom.
                mTopMargin = Math.max(0, Math.min(getArg0(1) - 1, mRows - 2))
                mBottomMargin = Math.max(mTopMargin + 2, Math.min(getArg1(mRows), mRows))

                // DECSTBM moves the cursor to column 1, line 1 of the page respecting origin mode.
                setCursorPosition(0, 0)
            }
            's'.code -> {
                if (isDecsetInternalBitSet(DECSET_BIT_LEFTRIGHT_MARGIN_MODE)) {
                    // Set left and right margins (DECSLRM - http://www.vt100.net/docs/vt510-rm/DECSLRM).
                    mLeftMargin = Math.min(getArg0(1) - 1, mColumns - 2)
                    mRightMargin = Math.max(mLeftMargin + 1, Math.min(getArg1(mColumns), mColumns))
                    // DECSLRM moves the cursor to column 1, line 1 of the page.
                    setCursorPosition(0, 0)
                } else {
                    // Save cursor (ANSI.SYS), available only when DECLRMM is disabled.
                    saveCursor()
                }
            }
            't'.code -> { // Window manipulation (from dtterm, as well as extensions)
                when (getArg0(0)) {
                    11 -> { // Report xterm window state. If the xterm window is open (non-iconified), it returns CSI 1 t .
                        mSession.write("\u001b[1t")
                    }
                    13 -> { // Report xterm window position. Result is CSI 3 ; x ; y t
                        mSession.write("\u001b[3;0;0t")
                    }
                    14 -> { // Report xterm window in pixels. Result is CSI 4 ; height ; width t
                        mSession.write(String.format(Locale.US, "\u001b[4;%d;%dt", mRows * mCellHeightPixels, mColumns * mCellWidthPixels))
                    }
                    16 -> { // Report xterm character cell size in pixels. Result is CSI 6 ; height ; width t
                        mSession.write(String.format(Locale.US, "\u001b[6;%d;%dt", mCellHeightPixels, mCellWidthPixels))
                    }
                    18 -> { // Report the size of the text area in characters. Result is CSI 8 ; height ; width t
                        mSession.write(String.format(Locale.US, "\u001b[8;%d;%dt", mRows, mColumns))
                    }
                    19 -> { // Report the size of the screen in characters. Result is CSI 9 ; height ; width t
                        // We report the same size as the view, since it's the view really isn't resizable from the shell.
                        mSession.write(String.format(Locale.US, "\u001b[9;%d;%dt", mRows, mColumns))
                    }
                    20 -> { // Report xterm windows icon label. Result is OSC L label ST. Disabled due to security concerns:
                        mSession.write("\u001b]LIconLabel\u001b\\")
                    }
                    21 -> { // Report xterm windows title. Result is OSC l label ST. Disabled due to security concerns:
                        mSession.write("\u001b]l\u001b\\")
                    }
                    22 -> {
                        // 22;0 -> Save xterm icon and window title on stack.
                        // 22;1 -> Save xterm icon title on stack.
                        // 22;2 -> Save xterm window title on stack.
                        mTitleStack.push(mTitle)
                        if (mTitleStack.size > 20) {
                            // Limit size
                            mTitleStack.removeAt(0)
                        }
                    }
                    23 -> { // Like 22 above but restore from stack.
                        if (!mTitleStack.isEmpty()) setTitle(mTitleStack.pop())
                    }
                    else -> {
                        // Ignore window manipulation.
                    }
                }
            }
            'u'.code -> { // Restore cursor (ANSI.SYS).
                restoreCursor()
            }
            ' '.code -> {
                continueSequence(ESC_CSI_ARGS_SPACE)
            }
            else -> {
                parseArg(b)
            }
        }
    }

    /** Select Graphic Rendition (SGR) - see http://en.wikipedia.org/wiki/ANSI_escape_code#graphics. */
    private fun selectGraphicRendition() {
        if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
        var i = 0
        while (i <= mArgIndex) {
            i = selectGraphicRenditionAt(i) + 1
        }
    }

    /**
     * Applies the SGR argument at [index] (the body of the Java `for` loop over the arguments).
     *
     * Returns the index of the last argument that was consumed: [index] itself, unless the code takes sub parameters or
     * the arguments of an extended color.
     */
    private fun selectGraphicRenditionAt(index: Int): Int {
        var i = index
        // Skip leading sub parameters:
        if ((mArgsSubParamsBitSet and (1 shl i)) != 0) {
            return i
        }

        var code = getArg(i, 0, false)
        if (code < 0) {
            if (mArgIndex > 0) {
                return i
            } else {
                code = 0
            }
        }
        if (code == 0) { // reset
            mForeColor = TextStyle.COLOR_INDEX_FOREGROUND
            mBackColor = TextStyle.COLOR_INDEX_BACKGROUND
            mEffect = 0
        } else if (code == 1) {
            mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_BOLD
        } else if (code == 2) {
            mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_DIM
        } else if (code == 3) {
            mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_ITALIC
        } else if (code == 4) {
            if (i + 1 <= mArgIndex && ((mArgsSubParamsBitSet and (1 shl (i + 1))) != 0)) {
                // Sub parameter, see https://sw.kovidgoyal.net/kitty/underlines/
                i++
                if (mArgs[i] == 0) {
                    // No underline.
                    mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.inv()
                } else {
                    // Different variations of underlines: https://sw.kovidgoyal.net/kitty/underlines/
                    mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
                }
            } else {
                mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE
            }
        } else if (code == 5) {
            mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_BLINK
        } else if (code == 7) {
            mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_INVERSE
        } else if (code == 8) {
            mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE
        } else if (code == 9) {
            mEffect = mEffect or TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH
        } else if (code == 10) {
            // Exit alt charset (TERM=linux) - ignore.
        } else if (code == 11) {
            // Enter alt charset (TERM=linux) - ignore.
        } else if (code == 22) { // Normal color or intensity, neither bright, bold nor faint.
            mEffect = mEffect and (TextStyle.CHARACTER_ATTRIBUTE_BOLD or TextStyle.CHARACTER_ATTRIBUTE_DIM).inv()
        } else if (code == 23) { // not italic, but rarely used as such; clears standout with TERM=screen
            mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_ITALIC.inv()
        } else if (code == 24) { // underline: none
            mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE.inv()
        } else if (code == 25) { // blink: none
            mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_BLINK.inv()
        } else if (code == 27) { // image: positive
            mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_INVERSE.inv()
        } else if (code == 28) {
            mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE.inv()
        } else if (code == 29) {
            mEffect = mEffect and TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH.inv()
        } else if (code >= 30 && code <= 37) {
            mForeColor = code - 30
        } else if (code == 38 || code == 48 || code == 58) {
            // Extended set foreground(38)/background(48)/underline(58) color.
            // This is followed by either "2;$R;$G;$B" to set a 24-bit color or
            // "5;$INDEX" to set an indexed color.
            if (i + 2 > mArgIndex) return i
            val firstArg = mArgs[i + 1]
            if (firstArg == 2) {
                if (i + 4 > mArgIndex) {
                    Logger.logWarn(mClient, LOG_TAG, "Too few CSI" + code + ";2 RGB arguments")
                } else {
                    val red = getArg(i + 2, 0, false)
                    val green = getArg(i + 3, 0, false)
                    val blue = getArg(i + 4, 0, false)

                    if (red < 0 || green < 0 || blue < 0 || red > 255 || green > 255 || blue > 255) {
                        finishSequenceAndLogError("Invalid RGB: " + red + "," + green + "," + blue)
                    } else {
                        val argbColor = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
                        when (code) {
                            38 -> mForeColor = argbColor
                            48 -> mBackColor = argbColor
                            58 -> mUnderlineColor = argbColor
                        }
                    }
                    i += 4 // "2;P_r;P_g;P_r"
                }
            } else if (firstArg == 5) {
                val color = getArg(i + 2, 0, false)
                i += 2 // "5;P_s"
                if (color >= 0 && color < TextStyle.NUM_INDEXED_COLORS) {
                    when (code) {
                        38 -> mForeColor = color
                        48 -> mBackColor = color
                        58 -> mUnderlineColor = color
                    }
                } else {
                    if (LOG_ESCAPE_SEQUENCES) Logger.logWarn(mClient, LOG_TAG, "Invalid color index: " + color)
                }
            } else {
                finishSequenceAndLogError("Invalid ISO-8613-3 SGR first argument: " + firstArg)
            }
        } else if (code == 39) { // Set default foreground color.
            mForeColor = TextStyle.COLOR_INDEX_FOREGROUND
        } else if (code >= 40 && code <= 47) { // Set background color.
            mBackColor = code - 40
        } else if (code == 49) { // Set default background color.
            mBackColor = TextStyle.COLOR_INDEX_BACKGROUND
        } else if (code == 59) { // Set default underline color.
            mUnderlineColor = TextStyle.COLOR_INDEX_FOREGROUND
        } else if (code >= 90 && code <= 97) { // Bright foreground colors (aixterm codes).
            mForeColor = code - 90 + 8
        } else if (code >= 100 && code <= 107) { // Bright background color (aixterm codes).
            mBackColor = code - 100 + 8
        } else {
            if (LOG_ESCAPE_SEQUENCES)
                Logger.logWarn(mClient, LOG_TAG, String.format(Locale.ROOT, "SGR unknown code %d", code))
        }
        return i
    }

    private fun doOsc(b: Int) {
        when (b) {
            7 -> { // Bell.
                doOscSetTextParameters("\u0007")
            }
            27 -> { // Escape.
                continueSequence(ESC_OSC_ESC)
            }
            else -> {
                collectOSCArgs(b)
            }
        }
    }

    private fun doOscEsc(b: Int) {
        when (b) {
            '\\'.code -> {
                doOscSetTextParameters("\u001b\\")
            }
            else -> {
                // The ESC character was not followed by a \, so insert the ESC and
                // the current character in arg buffer.
                collectOSCArgs(27)
                collectOSCArgs(b)
                continueSequence(ESC_OSC)
            }
        }
    }

    /** An Operating System Controls (OSC) Set Text Parameters. May come here from BEL or ST. */
    private fun doOscSetTextParameters(bellOrStringTerminator: String) {
        var value = -1
        var textParameter = ""
        // Extract initial $value from initial "$value;..." string.
        var mOSCArgTokenizerIndex = 0
        while (mOSCArgTokenizerIndex < mOSCOrDeviceControlArgs.length) {
            val b = mOSCOrDeviceControlArgs[mOSCArgTokenizerIndex]
            if (b == ';') {
                textParameter = mOSCOrDeviceControlArgs.substring(mOSCArgTokenizerIndex + 1)
                break
            } else if (b >= '0' && b <= '9') {
                value = (if (value < 0) 0 else value * 10) + (b - '0')
            } else {
                unknownSequence(b.code)
                return
            }
            mOSCArgTokenizerIndex++
        }

        when (value) {
            0, // Change icon name and window title to T.
            1, // Change icon name to T.
            2 -> { // Change window title to T.
                setTitle(textParameter)
            }
            4 -> {
                // P s = 4 ; c ; spec → Change Color Number c to the color specified by spec. This can be a name or RGB
                // specification as per XParseColor. Any number of c name pairs may be given. The color numbers correspond
                // to the ANSI colors 0-7, their bright versions 8-15, and if supported, the remainder of the 88-color or
                // 256-color table.
                // If a "?" is given rather than a name or RGB specification, xterm replies with a control sequence of the
                // same form which can be used to set the corresponding color. Because more than one pair of color number
                // and specification can be given in one control sequence, xterm can make more than one reply.
                var colorIndex = -1
                var parsingPairStart = -1
                var i = 0
                while (true) {
                    val endOfInput = i == textParameter.length
                    val b = if (endOfInput) ';' else textParameter[i]
                    if (b == ';') {
                        if (parsingPairStart < 0) {
                            parsingPairStart = i + 1
                        } else {
                            if (colorIndex < 0 || colorIndex > 255) {
                                unknownSequence(b.code)
                                return
                            } else {
                                mColors.tryParseColor(colorIndex, textParameter.substring(parsingPairStart, i))
                                mSession.onColorsChanged()
                                colorIndex = -1
                                parsingPairStart = -1
                            }
                        }
                    } else if (parsingPairStart >= 0) {
                        // We have passed a color index and are now going through color spec.
                    } else if (parsingPairStart < 0 && (b >= '0' && b <= '9')) {
                        colorIndex = (if (colorIndex < 0) 0 else colorIndex * 10) + (b - '0')
                    } else {
                        unknownSequence(b.code)
                        return
                    }
                    if (endOfInput) break
                    i++
                }
            }
            10, // Set foreground color.
            11, // Set background color.
            12 -> { // Set cursor color.
                var specialIndex = TextStyle.COLOR_INDEX_FOREGROUND + (value - 10)
                var lastSemiIndex = 0
                var charIndex = 0
                while (true) {
                    val endOfInput = charIndex == textParameter.length
                    if (endOfInput || textParameter[charIndex] == ';') {
                        try {
                            val colorSpec = textParameter.substring(lastSemiIndex, charIndex)
                            if ("?" == colorSpec) {
                                // Report current color in the same format xterm and gnome-terminal does.
                                val rgb = mColors.mCurrentColors[specialIndex]
                                val r = (65535 * ((rgb and 0x00FF0000) shr 16)) / 255
                                val g = (65535 * ((rgb and 0x0000FF00) shr 8)) / 255
                                val b = (65535 * ((rgb and 0x000000FF))) / 255
                                mSession.write("\u001b]" + value + ";rgb:" + String.format(Locale.US, "%04x", r) + "/" + String.format(Locale.US, "%04x", g) + "/"
                                    + String.format(Locale.US, "%04x", b) + bellOrStringTerminator)
                            } else {
                                mColors.tryParseColor(specialIndex, colorSpec)
                                mSession.onColorsChanged()
                            }
                            specialIndex++
                            if (endOfInput || (specialIndex > TextStyle.COLOR_INDEX_CURSOR) || ++charIndex >= textParameter.length)
                                break
                            lastSemiIndex = charIndex
                        } catch (e: NumberFormatException) {
                            // Ignore.
                        }
                    }
                    charIndex++
                }
            }
            52 -> { // Manipulate Selection Data. Skip the optional first selection parameter(s).
                val startIndex = textParameter.indexOf(";") + 1
                try {
                    val clipboardText = String(Base64.decode(textParameter.substring(startIndex), 0), StandardCharsets.UTF_8)
                    mSession.onCopyTextToClipboard(clipboardText)
                } catch (e: Exception) {
                    Logger.logError(mClient, LOG_TAG, "OSC Manipulate selection, invalid string '" + textParameter + "")
                }
            }
            104 -> {
                // "104;$c" → Reset Color Number $c. It is reset to the color specified by the corresponding X
                // resource. Any number of c parameters may be given. These parameters correspond to the ANSI colors 0-7,
                // their bright versions 8-15, and if supported, the remainder of the 88-color or 256-color table. If no
                // parameters are given, the entire table will be reset.
                if (textParameter.isEmpty()) {
                    mColors.reset()
                    mSession.onColorsChanged()
                } else {
                    var lastIndex = 0
                    var charIndex = 0
                    while (true) {
                        val endOfInput = charIndex == textParameter.length
                        if (endOfInput || textParameter[charIndex] == ';') {
                            try {
                                val colorToReset = textParameter.substring(lastIndex, charIndex).toInt()
                                mColors.reset(colorToReset)
                                mSession.onColorsChanged()
                                if (endOfInput) break
                                charIndex++
                                lastIndex = charIndex
                            } catch (e: NumberFormatException) {
                                // Ignore.
                            }
                        }
                        charIndex++
                    }
                }
            }
            110, // Reset foreground color.
            111, // Reset background color.
            112 -> { // Reset cursor color.
                mColors.reset(TextStyle.COLOR_INDEX_FOREGROUND + (value - 110))
                mSession.onColorsChanged()
            }
            119 -> { // Reset highlight color.
            }
            else -> {
                unknownParameter(value)
            }
        }
        finishSequence()
    }

    private fun blockClear(sx: Int, sy: Int, w: Int) {
        blockClear(sx, sy, w, 1)
    }

    private fun blockClear(sx: Int, sy: Int, w: Int, h: Int) {
        mScreen.blockSet(sx, sy, w, h, ' '.code, getStyle())
    }

    private fun getStyle(): Long {
        return TextStyle.encode(mForeColor, mBackColor, mEffect)
    }

    /** "CSI P_m h" for set or "CSI P_m l" for reset ANSI mode. */
    private fun doSetMode(newValue: Boolean) {
        val modeBit = getArg0(0)
        when (modeBit) {
            4 -> { // Set="Insert Mode". Reset="Replace Mode". (IRM).
                mInsertMode = newValue
            }
            20 -> { // Normal Linefeed (LNM).
                unknownParameter(modeBit)
                // http://www.vt100.net/docs/vt510-rm/LNM
            }
            34 -> {
                // Normal cursor visibility - when using TERM=screen, see
                // http://www.gnu.org/software/screen/manual/html_node/Control-Sequences.html
            }
            else -> {
                unknownParameter(modeBit)
            }
        }
    }

    /**
     * NOTE: The parameters of this function respect the [DECSET_BIT_ORIGIN_MODE]. Use
     * [setCursorRowCol] for absolute pos.
     */
    private fun setCursorPosition(x: Int, y: Int) {
        val originMode = isDecsetInternalBitSet(DECSET_BIT_ORIGIN_MODE)
        val effectiveTopMargin = if (originMode) mTopMargin else 0
        val effectiveBottomMargin = if (originMode) mBottomMargin else mRows
        val effectiveLeftMargin = if (originMode) mLeftMargin else 0
        val effectiveRightMargin = if (originMode) mRightMargin else mColumns
        val newRow = Math.max(effectiveTopMargin, Math.min(effectiveTopMargin + y, effectiveBottomMargin - 1))
        val newCol = Math.max(effectiveLeftMargin, Math.min(effectiveLeftMargin + x, effectiveRightMargin - 1))
        setCursorRowCol(newRow, newCol)
    }

    private fun scrollDownOneLine() {
        mScrollCounter++
        val currentStyle = getStyle()
        if (mLeftMargin != 0 || mRightMargin != mColumns) {
            // Horizontal margin: Do not put anything into scroll history, just non-margin part of screen up.
            mScreen.blockCopy(mLeftMargin, mTopMargin + 1, mRightMargin - mLeftMargin, mBottomMargin - mTopMargin - 1, mLeftMargin, mTopMargin)
            // .. and blank bottom row between margins:
            mScreen.blockSet(mLeftMargin, mBottomMargin - 1, mRightMargin - mLeftMargin, 1, ' '.code, currentStyle)
        } else {
            mScreen.scrollDownOneLine(mTopMargin, mBottomMargin, currentStyle)
        }
    }

    /**
     * Process the next ASCII character of a parameter.
     *
     * You must use the ; character to separate parameters and : to separate sub-parameters.
     *
     * Parameter characters modify the action or interpretation of the sequence. Originally
     * you can use up to 16 parameters per sequence, but following at least xterm and alacritty
     * we use a common space for parameters and sub-parameters, allowing 32 in total.
     *
     * All parameters are unsigned, positive decimal integers, with the most significant
     * digit sent first. Any parameter greater than 9999 (decimal) is set to 9999
     * (decimal). If you do not specify a value, a 0 value is assumed. A 0 value
     * or omitted parameter indicates a default value for the sequence. For most
     * sequences, the default value is 1.
     *
     * References:
     * [VT510 Video Terminal Programmer Information: Control Sequences](https://vt100.net/docs/vt510-rm/chapter4.html#S4.3.3)
     * [alacritty/vte: Implement colon separated CSI parameters](https://github.com/alacritty/vte/issues/22)
     */
    private fun parseArg(b: Int) {
        if (b >= '0'.code && b <= '9'.code) {
            if (mArgIndex < mArgs.size) {
                val oldValue = mArgs[mArgIndex]
                val thisDigit = b - '0'.code
                var value: Int
                if (oldValue >= 0) {
                    value = oldValue * 10 + thisDigit
                } else {
                    value = thisDigit
                }
                if (value > 9999)
                    value = 9999
                mArgs[mArgIndex] = value
            }
            continueSequence(mEscapeState)
        } else if (b == ';'.code || b == ':'.code) {
            if (mArgIndex + 1 < mArgs.size) {
                mArgIndex++
                if (b == ':'.code) {
                    mArgsSubParamsBitSet = mArgsSubParamsBitSet or (1 shl mArgIndex)
                }
            } else {
                logError("Too many parameters when in state: " + mEscapeState)
            }
            continueSequence(mEscapeState)
        } else {
            unknownSequence(b)
        }
    }

    private fun getArg0(defaultValue: Int): Int {
        return getArg(0, defaultValue, true)
    }

    private fun getArg1(defaultValue: Int): Int {
        return getArg(1, defaultValue, true)
    }

    private fun getArg(index: Int, defaultValue: Int, treatZeroAsDefault: Boolean): Int {
        var result = mArgs[index]
        if (result < 0 || (result == 0 && treatZeroAsDefault)) {
            result = defaultValue
        }
        return result
    }

    private fun collectOSCArgs(b: Int) {
        if (mOSCOrDeviceControlArgs.length < MAX_OSC_STRING_LENGTH) {
            mOSCOrDeviceControlArgs.appendCodePoint(b)
            continueSequence(mEscapeState)
        } else {
            unknownSequence(b)
        }
    }

    private fun unimplementedSequence(b: Int) {
        logError("Unimplemented sequence char '" + b.toChar() + "' (U+" + String.format("%04x", b) + ")")
        finishSequence()
    }

    private fun unknownSequence(b: Int) {
        logError("Unknown sequence char '" + b.toChar() + "' (numeric value=" + b + ")")
        finishSequence()
    }

    private fun unknownParameter(parameter: Int) {
        logError("Unknown parameter: " + parameter)
        finishSequence()
    }

    private fun logError(errorType: String) {
        if (LOG_ESCAPE_SEQUENCES) {
            val buf = StringBuilder()
            buf.append(errorType)
            buf.append(", escapeState=")
            buf.append(mEscapeState)
            var firstArg = true
            if (mArgIndex >= mArgs.size) mArgIndex = mArgs.size - 1
            var i = 0
            while (i <= mArgIndex) {
                val value = mArgs[i]
                if (value >= 0) {
                    if (firstArg) {
                        firstArg = false
                        buf.append(", args={")
                    } else {
                        buf.append(',')
                    }
                    buf.append(value)
                }
                i++
            }
            if (!firstArg) buf.append('}')
            finishSequenceAndLogError(buf.toString())
        }
    }

    private fun finishSequenceAndLogError(error: String) {
        if (LOG_ESCAPE_SEQUENCES) Logger.logWarn(mClient, LOG_TAG, error)
        finishSequence()
    }

    private fun finishSequence() {
        mEscapeState = ESC_NONE
    }

    /**
     * Send a Unicode code point to the screen.
     *
     * @param codePoint The code point of the character to display
     */
    private fun emitCodePoint(codePoint: Int) {
        var codePoint = codePoint
        mLastEmittedCodePoint = codePoint
        if (if (mUseLineDrawingUsesG0) mUseLineDrawingG0 else mUseLineDrawingG1) {
            // http://www.vt100.net/docs/vt102-ug/table5-15.html.
            when (codePoint) {
                '_'.code -> codePoint = 0x20 // Blank.
                '`'.code -> codePoint = 0x25C6 // Diamond.
                '0'.code -> codePoint = 0x2588 // Solid block;
                'a'.code -> codePoint = 0x2592 // Checker board.
                'b'.code -> codePoint = 0x2409 // Horizontal tab.
                'c'.code -> codePoint = 0x240C // Form feed.
                'd'.code -> codePoint = 0x0d // Carriage return.
                'e'.code -> codePoint = 0x240A // Linefeed.
                'f'.code -> codePoint = 0x00B0 // Degree.
                'g'.code -> codePoint = 0x00B1 // Plus-minus.
                'h'.code -> codePoint = 0x0a // Newline.
                'i'.code -> codePoint = 0x240B // Vertical tab.
                'j'.code -> codePoint = 0x2518 // Lower right corner.
                'k'.code -> codePoint = 0x2510 // Upper right corner.
                'l'.code -> codePoint = 0x250C // Upper left corner.
                'm'.code -> codePoint = 0x2514 // Left left corner.
                'n'.code -> codePoint = 0x253C // Crossing lines.
                'o'.code -> codePoint = 0x23BA // Horizontal line - scan 1.
                'p'.code -> codePoint = 0x23BB // Horizontal line - scan 3.
                'q'.code -> codePoint = 0x2500 // Horizontal line - scan 5.
                'r'.code -> codePoint = 0x23BC // Horizontal line - scan 7.
                's'.code -> codePoint = 0x23BD // Horizontal line - scan 9.
                't'.code -> codePoint = 0x251C // T facing rightwards.
                'u'.code -> codePoint = 0x2524 // T facing leftwards.
                'v'.code -> codePoint = 0x2534 // T facing upwards.
                'w'.code -> codePoint = 0x252C // T facing downwards.
                'x'.code -> codePoint = 0x2502 // Vertical line.
                'y'.code -> codePoint = 0x2264 // Less than or equal to.
                'z'.code -> codePoint = 0x2265 // Greater than or equal to.
                '{'.code -> codePoint = 0x03C0 // Pi.
                '|'.code -> codePoint = 0x2260 // Not equal to.
                '}'.code -> codePoint = 0x00A3 // UK pound.
                '~'.code -> codePoint = 0x00B7 // Centered dot.
            }
        }

        val autoWrap = isDecsetInternalBitSet(DECSET_BIT_AUTOWRAP)
        val displayWidth = WcWidth.width(codePoint)
        val cursorInLastColumn = mCursorCol == mRightMargin - 1

        if (autoWrap) {
            if (cursorInLastColumn && ((mAboutToAutoWrap && displayWidth == 1) || displayWidth == 2)) {
                mScreen.setLineWrap(mCursorRow)
                mCursorCol = mLeftMargin
                if (mCursorRow + 1 < mBottomMargin) {
                    mCursorRow++
                } else {
                    scrollDownOneLine()
                }
            }
        } else if (cursorInLastColumn && displayWidth == 2) {
            // The behaviour when a wide character is output with cursor in the last column when
            // autowrap is disabled is not obvious - it's ignored here.
            return
        }

        if (mInsertMode && displayWidth > 0) {
            // Move character to right one space.
            val destCol = mCursorCol + displayWidth
            if (destCol < mRightMargin)
                mScreen.blockCopy(mCursorCol, mCursorRow, mRightMargin - destCol, 1, destCol, mCursorRow)
        }

        val offsetDueToCombiningChar = (if (displayWidth <= 0 && mCursorCol > 0 && !mAboutToAutoWrap) 1 else 0)
        var column = mCursorCol - offsetDueToCombiningChar

        // Fix TerminalRow.setChar() ArrayIndexOutOfBoundsException index=-1 exception reported
        // The offsetDueToCombiningChar would never be 1 if mCursorCol was 0 to get column/index=-1,
        // so was mCursorCol changed after the offsetDueToCombiningChar conditional by another thread?
        // TODO: Check if there are thread synchronization issues with mCursorCol and mCursorRow, possibly causing others bugs too.
        if (column < 0) column = 0
        mScreen.setChar(column, mCursorRow, codePoint, getStyle())

        if (autoWrap && displayWidth > 0)
            mAboutToAutoWrap = (mCursorCol == mRightMargin - displayWidth)

        mCursorCol = Math.min(mCursorCol + displayWidth, mRightMargin - 1)
    }

    private fun setCursorRow(row: Int) {
        mCursorRow = row
        mAboutToAutoWrap = false
    }

    private fun setCursorCol(col: Int) {
        mCursorCol = col
        mAboutToAutoWrap = false
    }

    /** Set the cursor mode, but limit it to margins if [DECSET_BIT_ORIGIN_MODE] is enabled. */
    private fun setCursorColRespectingOriginMode(col: Int) {
        setCursorPosition(col, mCursorRow)
    }

    /** TODO: Better name, distinguished from [setCursorPosition] by not regarding origin mode. */
    private fun setCursorRowCol(row: Int, col: Int) {
        mCursorRow = Math.max(0, Math.min(row, mRows - 1))
        mCursorCol = Math.max(0, Math.min(col, mColumns - 1))
        mAboutToAutoWrap = false
    }

    fun getScrollCounter(): Int {
        return mScrollCounter
    }

    fun clearScrollCounter() {
        mScrollCounter = 0
    }

    /** Reset terminal state so user can interact with it regardless of present state. */
    fun reset() {
        setCursorStyle()
        mArgIndex = 0
        mContinueSequence = false
        mEscapeState = ESC_NONE
        mInsertMode = false
        mTopMargin = 0
        mLeftMargin = 0
        mBottomMargin = mRows
        mRightMargin = mColumns
        mAboutToAutoWrap = false
        mForeColor = TextStyle.COLOR_INDEX_FOREGROUND
        mSavedStateMain.mSavedForeColor = TextStyle.COLOR_INDEX_FOREGROUND
        mSavedStateAlt.mSavedForeColor = TextStyle.COLOR_INDEX_FOREGROUND
        mBackColor = TextStyle.COLOR_INDEX_BACKGROUND
        mSavedStateMain.mSavedBackColor = TextStyle.COLOR_INDEX_BACKGROUND
        mSavedStateAlt.mSavedBackColor = TextStyle.COLOR_INDEX_BACKGROUND
        setDefaultTabStops()

        mUseLineDrawingG0 = false
        mUseLineDrawingG1 = false
        mUseLineDrawingUsesG0 = true

        mSavedStateMain.mSavedCursorRow = 0
        mSavedStateMain.mSavedCursorCol = 0
        mSavedStateMain.mSavedEffect = 0
        mSavedStateMain.mSavedDecFlags = 0
        mSavedStateAlt.mSavedCursorRow = 0
        mSavedStateAlt.mSavedCursorCol = 0
        mSavedStateAlt.mSavedEffect = 0
        mSavedStateAlt.mSavedDecFlags = 0
        mCurrentDecSetFlags = 0
        // Initial wrap-around is not accurate but makes terminal more useful, especially on a small screen:
        setDecsetinternalBit(DECSET_BIT_AUTOWRAP, true)
        setDecsetinternalBit(DECSET_BIT_CURSOR_ENABLED, true)
        mSavedDecSetFlags = mCurrentDecSetFlags
        mSavedStateMain.mSavedDecFlags = mCurrentDecSetFlags
        mSavedStateAlt.mSavedDecFlags = mCurrentDecSetFlags

        // XXX: Should we set terminal driver back to IUTF8 with termios?
        mUtf8ToFollow = 0
        mUtf8Index = 0

        mColors.reset()
        mSession.onColorsChanged()
    }

    fun getSelectedText(x1: Int, y1: Int, x2: Int, y2: Int): String {
        return mScreen.getSelectedText(x1, y1, x2, y2)
    }

    /** Get the terminal session's title (null if not set). */
    fun getTitle(): String? {
        return mTitle
    }

    /** Change the terminal session's title. */
    private fun setTitle(newTitle: String?) {
        val oldTitle = mTitle
        mTitle = newTitle
        if (!Objects.equals(oldTitle, newTitle)) {
            mSession.titleChanged(oldTitle, newTitle)
        }
    }

    /** If DECSET 2004 is set, prefix paste with "\033[200~" and suffix with "\033[201~". */
    fun paste(text: String) {
        var text = text
        // First: Always remove escape key and C1 control characters [0x80,0x9F]:
        text = Regex("(\u001B|[\u0080-\u009F])").replace(text, "")
        // Second: Replace all newlines (\n) or CRLF (\r\n) with carriage returns (\r).
        text = Regex("\r?\n").replace(text, "\r")

        // Then: Implement bracketed paste mode if enabled:
        val bracketed = isDecsetInternalBitSet(DECSET_BIT_BRACKETED_PASTE_MODE)
        if (bracketed) mSession.write("\u001b[200~")
        mSession.write(text)
        if (bracketed) mSession.write("\u001b[201~")
    }

    private fun getTerminalTranscriptRows(transcriptRows: Int?): Int {
        return if (transcriptRows == null || transcriptRows < TERMINAL_TRANSCRIPT_ROWS_MIN || transcriptRows > TERMINAL_TRANSCRIPT_ROWS_MAX)
            DEFAULT_TERMINAL_TRANSCRIPT_ROWS
        else
            transcriptRows
    }

    /** http://www.vt100.net/docs/vt510-rm/DECSC */
    private class SavedScreenState {
        /** Saved state of the cursor position, Used to implement the save/restore cursor position escape sequences. */
        @JvmField var mSavedCursorRow = 0
        @JvmField var mSavedCursorCol = 0
        @JvmField var mSavedEffect = 0
        @JvmField var mSavedForeColor = 0
        @JvmField var mSavedBackColor = 0
        @JvmField var mSavedDecFlags = 0
        @JvmField var mUseLineDrawingG0 = false
        @JvmField var mUseLineDrawingG1 = false
        @JvmField var mUseLineDrawingUsesG0 = true
    }

    override fun toString(): String {
        return "TerminalEmulator[size=" + mScreen.mColumns + "x" + mScreen.mScreenRows + ", margins={" + mTopMargin + "," + mRightMargin + "," + mBottomMargin +
            "," + mLeftMargin + "}]"
    }

    companion object {
        /** Log unknown or unimplemented escape sequences received from the shell process. */
        private const val LOG_ESCAPE_SEQUENCES = false

        const val MOUSE_LEFT_BUTTON = 0

        /** Mouse moving while having left mouse button pressed. */
        const val MOUSE_LEFT_BUTTON_MOVED = 32
        const val MOUSE_WHEELUP_BUTTON = 64
        const val MOUSE_WHEELDOWN_BUTTON = 65

        /** Used for invalid data - http://en.wikipedia.org/wiki/Replacement_character#Replacement_character */
        const val UNICODE_REPLACEMENT_CHAR = 0xFFFD

        /** Escape processing: Not currently in an escape sequence. */
        private const val ESC_NONE = 0

        /** Escape processing: Have seen an ESC character - proceed to [doEsc] */
        private const val ESC = 1

        /** Escape processing: Have seen ESC POUND */
        private const val ESC_POUND = 2

        /** Escape processing: Have seen ESC and a character-set-select ( char */
        private const val ESC_SELECT_LEFT_PAREN = 3

        /** Escape processing: Have seen ESC and a character-set-select ) char */
        private const val ESC_SELECT_RIGHT_PAREN = 4

        /** Escape processing: "ESC [" or CSI (Control Sequence Introducer). */
        private const val ESC_CSI = 6

        /** Escape processing: ESC [ ? */
        private const val ESC_CSI_QUESTIONMARK = 7

        /** Escape processing: ESC [ $ */
        private const val ESC_CSI_DOLLAR = 8

        /** Escape processing: ESC % */
        private const val ESC_PERCENT = 9

        /** Escape processing: ESC ] (AKA OSC - Operating System Controls) */
        private const val ESC_OSC = 10

        /** Escape processing: ESC ] (AKA OSC - Operating System Controls) ESC */
        private const val ESC_OSC_ESC = 11

        /** Escape processing: ESC [ > */
        private const val ESC_CSI_BIGGERTHAN = 12

        /** Escape procession: "ESC P" or Device Control String (DCS) */
        private const val ESC_P = 13

        /** Escape processing: CSI > */
        private const val ESC_CSI_QUESTIONMARK_ARG_DOLLAR = 14

        /** Escape processing: CSI $ARGS ' ' */
        private const val ESC_CSI_ARGS_SPACE = 15

        /** Escape processing: CSI $ARGS '*' */
        private const val ESC_CSI_ARGS_ASTERIX = 16

        /** Escape processing: CSI " */
        private const val ESC_CSI_DOUBLE_QUOTE = 17

        /** Escape processing: CSI ' */
        private const val ESC_CSI_SINGLE_QUOTE = 18

        /** Escape processing: CSI ! */
        private const val ESC_CSI_EXCLAMATION = 19

        /** Escape processing: "ESC _" or Application Program Command (APC). */
        private const val ESC_APC = 20

        /** Escape processing: "ESC _" or Application Program Command (APC), followed by Escape. */
        private const val ESC_APC_ESCAPE = 21

        /** Escape processing: ESC [ <parameter bytes> */
        private const val ESC_CSI_UNSUPPORTED_PARAMETER_BYTE = 22

        /** Escape processing: ESC [ <parameter bytes> <intermediate bytes> */
        private const val ESC_CSI_UNSUPPORTED_INTERMEDIATE_BYTE = 23

        /** The number of parameter arguments including colon separated sub-parameters. */
        private const val MAX_ESCAPE_PARAMETERS = 32

        /** Needs to be large enough to contain reasonable OSC 52 pastes. */
        private const val MAX_OSC_STRING_LENGTH = 8192

        /** DECSET 1 - application cursor keys. */
        private const val DECSET_BIT_APPLICATION_CURSOR_KEYS = 1
        private const val DECSET_BIT_REVERSE_VIDEO = 1 shl 1

        /**
         * http://www.vt100.net/docs/vt510-rm/DECOM: "When DECOM is set, the home cursor position is at the upper-left
         * corner of the screen, within the margins. The starting point for line numbers depends on the current top margin
         * setting. The cursor cannot move outside of the margins. When DECOM is reset, the home cursor position is at the
         * upper-left corner of the screen. The starting point for line numbers is independent of the margins. The cursor
         * can move outside of the margins."
         */
        private const val DECSET_BIT_ORIGIN_MODE = 1 shl 2

        /**
         * http://www.vt100.net/docs/vt510-rm/DECAWM: "If the DECAWM function is set, then graphic characters received when
         * the cursor is at the right border of the page appear at the beginning of the next line. Any text on the page
         * scrolls up if the cursor is at the end of the scrolling region. If the DECAWM function is reset, then graphic
         * characters received when the cursor is at the right border of the page replace characters already on the page."
         */
        private const val DECSET_BIT_AUTOWRAP = 1 shl 3

        /** DECSET 25 - if the cursor should be enabled, [isCursorEnabled]. */
        private const val DECSET_BIT_CURSOR_ENABLED = 1 shl 4
        private const val DECSET_BIT_APPLICATION_KEYPAD = 1 shl 5

        /** DECSET 1000 - if to report mouse press&release events. */
        private const val DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE = 1 shl 6

        /** DECSET 1002 - like 1000, but report moving mouse while pressed. */
        private const val DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT = 1 shl 7

        /** DECSET 1004 - NOT implemented. */
        private const val DECSET_BIT_SEND_FOCUS_EVENTS = 1 shl 8

        /** DECSET 1006 - SGR-like mouse protocol (the modern sane choice). */
        private const val DECSET_BIT_MOUSE_PROTOCOL_SGR = 1 shl 9

        /** DECSET 2004 - see [paste] */
        private const val DECSET_BIT_BRACKETED_PASTE_MODE = 1 shl 10

        /** Toggled with DECLRMM - http://www.vt100.net/docs/vt510-rm/DECLRMM */
        private const val DECSET_BIT_LEFTRIGHT_MARGIN_MODE = 1 shl 11

        /** Not really DECSET bit... - http://www.vt100.net/docs/vt510-rm/DECSACE */
        private const val DECSET_BIT_RECTANGULAR_CHANGEATTRIBUTE = 1 shl 12

        const val TERMINAL_TRANSCRIPT_ROWS_MIN = 100
        const val TERMINAL_TRANSCRIPT_ROWS_MAX = 50000
        const val DEFAULT_TERMINAL_TRANSCRIPT_ROWS = 2000

        /* The supported terminal cursor styles. */

        const val TERMINAL_CURSOR_STYLE_BLOCK = 0
        const val TERMINAL_CURSOR_STYLE_UNDERLINE = 1
        const val TERMINAL_CURSOR_STYLE_BAR = 2
        const val DEFAULT_TERMINAL_CURSOR_STYLE = TERMINAL_CURSOR_STYLE_BLOCK

        @JvmField
        val TERMINAL_CURSOR_STYLES_LIST: Array<Int> = arrayOf(TERMINAL_CURSOR_STYLE_BLOCK, TERMINAL_CURSOR_STYLE_UNDERLINE, TERMINAL_CURSOR_STYLE_BAR)

        private const val LOG_TAG = "TerminalEmulator"

        @JvmStatic
        fun mapDecSetBitToInternalBit(decsetBit: Int): Int {
            when (decsetBit) {
                1 -> return DECSET_BIT_APPLICATION_CURSOR_KEYS
                5 -> return DECSET_BIT_REVERSE_VIDEO
                6 -> return DECSET_BIT_ORIGIN_MODE
                7 -> return DECSET_BIT_AUTOWRAP
                25 -> return DECSET_BIT_CURSOR_ENABLED
                66 -> return DECSET_BIT_APPLICATION_KEYPAD
                69 -> return DECSET_BIT_LEFTRIGHT_MARGIN_MODE
                1000 -> return DECSET_BIT_MOUSE_TRACKING_PRESS_RELEASE
                1002 -> return DECSET_BIT_MOUSE_TRACKING_BUTTON_EVENT
                1004 -> return DECSET_BIT_SEND_FOCUS_EVENTS
                1006 -> return DECSET_BIT_MOUSE_PROTOCOL_SGR
                2004 -> return DECSET_BIT_BRACKETED_PASTE_MODE
                else -> return -1
                // throw new IllegalArgumentException("Unsupported decset: " + decsetBit);
            }
        }
    }
}
