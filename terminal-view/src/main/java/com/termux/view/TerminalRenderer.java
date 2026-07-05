package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

import java.text.Bidi;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 *
 * <h3>Bidirectional (RTL) text support</h3>
 * Lines that contain no right-to-left characters are rendered by {@link #renderNormalLine} which is
 * byte-for-byte identical to the historical Termux renderer (fast path, zero behavioural change).
 * <p/>
 * Lines that {@link Bidi#requiresBidi require bidi processing} (Arabic, Hebrew, Persian, ...) are
 * rendered by {@link #renderBidiLine}. That path:
 * <ul>
 *     <li>runs the Unicode Bidirectional Algorithm ({@link java.text.Bidi}) with an LTR paragraph
 *         base direction (keeps the terminal grid layout stable, only reorders RTL segments),</li>
 *     <li>reorders the row's cells into visual order via {@link Bidi#reorderVisually},</li>
 *     <li>draws each visual segment with the correct {@code isRtl} flag passed to
 *         {@link Canvas#drawTextRun} so Android's text engine performs Arabic shaping (contextual
 *         joining) and lays the glyphs out right-to-left.</li>
 * </ul>
 */
public final class TerminalRenderer {

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /** The {@link Paint#getFontSpacing()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setTextSize(textSize);

        mFontLineSpacing = (int) Math.ceil(mTextPaint.getFontSpacing());
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
                // Normalize the selection columns: on RTL lines a drag can produce a descending
                // logical range (start column > end column). Without this the selection would appear
                // to vanish on Arabic/Hebrew text even though it is active.
                if (selx1 >= 0 && selx2 >= 0 && selx1 > selx2) {
                    int tmp = selx1; selx1 = selx2; selx2 = tmp;
                }
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            // Fast path: lines with no right-to-left characters are rendered exactly as before.
            // Bidi.requiresBidi() is a cheap scan that returns false for plain ASCII / LTR content.
            if (charsUsedInLine > 0 && Bidi.requiresBidi(line, 0, charsUsedInLine)) {
                renderBidiLine(canvas, lineObject, palette, heightOffset, columns, charsUsedInLine,
                    cursorX, cursorShape, selx1, selx2, reverseVideo);
            } else {
                renderNormalLine(canvas, lineObject, palette, heightOffset, columns, charsUsedInLine,
                    cursorX, cursorShape, selx1, selx2, reverseVideo);
            }
        }
    }

    /**
     * Historical left-to-right renderer for a single row. Behaviour is intentionally unchanged: it
     * builds runs of identical style/cursor/selection state and draws them with {@code isRtl=false}.
     */
    private void renderNormalLine(Canvas canvas, TerminalRow lineObject, int[] palette, float heightOffset,
                                  int columns, int charsUsedInLine, int cursorX, int cursorShape,
                                  int selx1, int selx2, boolean reverseVideo) {
        final char[] line = lineObject.mText;

        long lastRunStyle = 0;
        boolean lastRunInsideCursor = false;
        boolean lastRunInsideSelection = false;
        int lastRunStartColumn = -1;
        int lastRunStartIndex = 0;
        boolean lastRunFontWidthMismatch = false;
        int currentCharIndex = 0;
        float measuredWidthForRun = 0.f;

        for (int column = 0; column < columns; ) {
            final char charAtIndex = line[currentCharIndex];
            final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
            final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
            final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
            final int codePointWcWidth = WcWidth.width(codePoint);
            final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
            final boolean insideSelection = column >= selx1 && column <= selx2;
            final long style = lineObject.getStyle(column);

            // Check if the measured text width for this code point is not the same as that expected by wcwidth().
            // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
            // smileys which android font renders as wide.
            // If this is detected, we draw this code point scaled to match what wcwidth() expects.
            final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                currentCharIndex, charsForCodePoint);
            final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

            if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                if (column == 0) {
                    // Skip first column as there is nothing to draw, just record the current style.
                } else {
                    final int columnWidthSinceLastRun = column - lastRunStartColumn;
                    final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                    int cursorColor = lastRunInsideCursor ? palette[TextStyle.COLOR_INDEX_CURSOR] : 0;
                    boolean invertCursorTextColor = false;
                    if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                        invertCursorTextColor = true;
                    }
                    drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                        lastRunStartIndex, charsSinceLastRun, lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                        cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection, false);
                }
                measuredWidthForRun = 0.f;
                lastRunStyle = style;
                lastRunInsideCursor = insideCursor;
                lastRunInsideSelection = insideSelection;
                lastRunStartColumn = column;
                lastRunStartIndex = currentCharIndex;
                lastRunFontWidthMismatch = fontWidthMismatch;
            }
            measuredWidthForRun += measuredCodePointWidth;
            column += codePointWcWidth;
            currentCharIndex += charsForCodePoint;
            while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                // Eat combining chars so that they are treated as part of the last non-combining code point,
                // instead of e.g. being considered inside the cursor in the next run.
                currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
            }
        }

        final int columnWidthSinceLastRun = columns - lastRunStartColumn;
        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
        int cursorColor = lastRunInsideCursor ? palette[TextStyle.COLOR_INDEX_CURSOR] : 0;
        boolean invertCursorTextColor = false;
        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
            invertCursorTextColor = true;
        }
        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection, false);
    }

    /** Immutable visual layout of one bidi row, shared by rendering and hit-testing. */
    private static final class BidiLayout {
        final int cellCount;
        final int[] cellCharStart;      // index into line[] where each (logical) cell starts
        final int[] cellCharCount;      // number of java chars (base + combining) in each cell
        final int[] cellWidth;          // display columns occupied by each cell (1 or 2)
        final int[] cellColumn;         // logical start column of each cell
        final long[] cellStyle;         // style of each cell
        final byte[] cellLevels;        // resolved bidi level per (logical) cell
        final Integer[] visualToLogical; // visual order index -> logical cell index
        final int[] cellVisualColumn;   // visual start column per (logical) cell

        BidiLayout(int cellCount, int[] cellCharStart, int[] cellCharCount, int[] cellWidth,
                   int[] cellColumn, long[] cellStyle, byte[] cellLevels, Integer[] visualToLogical,
                   int[] cellVisualColumn) {
            this.cellCount = cellCount;
            this.cellCharStart = cellCharStart;
            this.cellCharCount = cellCharCount;
            this.cellWidth = cellWidth;
            this.cellColumn = cellColumn;
            this.cellStyle = cellStyle;
            this.cellLevels = cellLevels;
            this.visualToLogical = visualToLogical;
            this.cellVisualColumn = cellVisualColumn;
        }
    }

    /**
     * Compute the visual (display) order of a row using the Unicode Bidirectional Algorithm with an
     * LTR paragraph base direction. Returns {@code null} when the row needs no reordering (the caller
     * should then use the LTR fast path). Deliberately free of any {@link Canvas}/{@link Paint} state
     * so the same result can drive both rendering and hit-testing.
     */
    private static BidiLayout computeBidiLayout(TerminalRow lineObject, int columns, int charsUsedInLine) {
        final char[] line = lineObject.mText;

        // 1. Decompose the row into cells (in logical/column order). A cell is one base code point plus
        //    any trailing combining marks, and it occupies wcwidth() columns (1 or 2).
        final int[] cellCharStart = new int[columns];
        final int[] cellCharCount = new int[columns];
        final int[] cellWidth = new int[columns];
        final int[] cellColumn = new int[columns];
        final long[] cellStyle = new long[columns];
        int cellCount = 0;
        {
            int idx = 0, col = 0;
            while (col < columns && idx < charsUsedInLine) {
                final int start = idx;
                final char c = line[idx];
                final boolean high = Character.isHighSurrogate(c);
                final int codePoint = high ? Character.toCodePoint(c, line[idx + 1]) : c;
                int w = WcWidth.width(codePoint);
                if (w <= 0) w = 1; // defensive: a cell's leading code point should never be zero-width
                idx += high ? 2 : 1;
                while (idx < charsUsedInLine && WcWidth.width(line, idx) <= 0) {
                    idx += Character.isHighSurrogate(line[idx]) ? 2 : 1;
                }
                cellCharStart[cellCount] = start;
                cellCharCount[cellCount] = idx - start;
                cellWidth[cellCount] = w;
                cellColumn[cellCount] = col;
                cellStyle[cellCount] = lineObject.getStyle(col);
                cellCount++;
                col += w;
            }
        }
        if (cellCount == 0) return null;

        // 2. Run the bidi algorithm and derive a per-char level.
        final Bidi bidi = new Bidi(line, 0, null, 0, charsUsedInLine, Bidi.DIRECTION_LEFT_TO_RIGHT);
        if (bidi.isLeftToRight()) return null;
        final byte[] charLevel = new byte[charsUsedInLine];
        for (int r = 0, runs = bidi.getRunCount(); r < runs; r++) {
            final int s = bidi.getRunStart(r);
            final int e = Math.min(bidi.getRunLimit(r), charsUsedInLine);
            final byte level = (byte) bidi.getRunLevel(r);
            for (int i = s; i < e; i++) charLevel[i] = level;
        }

        // 3. Reorder cells into visual (left-to-right on screen) order.
        final byte[] cellLevels = new byte[cellCount];
        final Integer[] visualToLogical = new Integer[cellCount];
        for (int i = 0; i < cellCount; i++) {
            cellLevels[i] = charLevel[cellCharStart[i]];
            visualToLogical[i] = i;
        }
        Bidi.reorderVisually(cellLevels, 0, visualToLogical, 0, cellCount);

        // 4. Assign a visual start column to each (logical) cell.
        final int[] cellVisualColumn = new int[cellCount];
        int visCol = 0;
        for (int v = 0; v < cellCount; v++) {
            final int logical = visualToLogical[v];
            cellVisualColumn[logical] = visCol;
            visCol += cellWidth[logical];
        }

        return new BidiLayout(cellCount, cellCharStart, cellCharCount, cellWidth, cellColumn,
            cellStyle, cellLevels, visualToLogical, cellVisualColumn);
    }

    /**
     * Bidirectional renderer for a single row that contains right-to-left text.
     * <p/>
     * The terminal buffer stores cells in logical (typing) order. We compute the visual (display)
     * order with the Unicode Bidirectional Algorithm and draw each contiguous visual segment with
     * the appropriate direction, letting {@link Canvas#drawTextRun} perform Arabic shaping.
     */
    private void renderBidiLine(Canvas canvas, TerminalRow lineObject, int[] palette, float heightOffset,
                                int columns, int charsUsedInLine, int cursorX, int cursorShape,
                                int selx1, int selx2, boolean reverseVideo) {
        final BidiLayout L = computeBidiLayout(lineObject, columns, charsUsedInLine);
        if (L == null) {
            // No actual RTL runs after resolution - use the identical LTR fast path.
            renderNormalLine(canvas, lineObject, palette, heightOffset, columns, charsUsedInLine,
                cursorX, cursorShape, selx1, selx2, reverseVideo);
            return;
        }
        final char[] line = lineObject.mText;

        // Walk the cells in visual order, grouping into segments that share style, direction, cursor
        // and selection state AND stay contiguous in logical order (so shaping is preserved), then draw.
        int v = 0;
        while (v < L.cellCount) {
            final int firstLogical = L.visualToLogical[v];
            final long style = L.cellStyle[firstLogical];
            final byte level = L.cellLevels[firstLogical];
            final boolean rtl = (level & 1) != 0;
            final boolean insideCursor = cellInsideCursor(cursorX, L.cellColumn[firstLogical], L.cellWidth[firstLogical]);
            final boolean insideSelection = cellInsideSelection(selx1, selx2, L.cellColumn[firstLogical]);

            int minLogical = firstLogical;
            int maxLogical = firstLogical;
            int segEnd = v + 1;
            while (segEnd < L.cellCount) {
                final int nextLogical = L.visualToLogical[segEnd];
                if (L.cellStyle[nextLogical] != style
                    || L.cellLevels[nextLogical] != level
                    || cellInsideCursor(cursorX, L.cellColumn[nextLogical], L.cellWidth[nextLogical]) != insideCursor
                    || cellInsideSelection(selx1, selx2, L.cellColumn[nextLogical]) != insideSelection)
                    break;
                // Require logical adjacency so the char range handed to drawTextRun stays contiguous.
                if (rtl) {
                    if (nextLogical != minLogical - 1) break;
                    minLogical = nextLogical;
                } else {
                    if (nextLogical != maxLogical + 1) break;
                    maxLogical = nextLogical;
                }
                segEnd++;
            }

            final int segStartVisualColumn = L.cellVisualColumn[L.visualToLogical[v]];
            final int lastVisualLogical = L.visualToLogical[segEnd - 1];
            final int segWidthColumns = L.cellVisualColumn[lastVisualLogical] + L.cellWidth[lastVisualLogical] - segStartVisualColumn;
            final int charStart = L.cellCharStart[minLogical];
            final int charEnd = L.cellCharStart[maxLogical] + L.cellCharCount[maxLogical];
            final int charCount = charEnd - charStart;

            // Shaping context = the whole contiguous same-level run this segment belongs to, even when
            // the segment was split by a cursor/selection/style boundary. Passing the full run as the
            // context to drawTextRun keeps Arabic letters joined across those splits, while only the
            // [charStart,charEnd) range is drawn. Verified on a real emulator: a mid-word cell drawn
            // with full-run context renders its medial (joined) form at the correct position.
            int runMinLogical = minLogical, runMaxLogical = maxLogical;
            while (runMinLogical - 1 >= 0 && L.cellLevels[runMinLogical - 1] == level) runMinLogical--;
            while (runMaxLogical + 1 < L.cellCount && L.cellLevels[runMaxLogical + 1] == level) runMaxLogical++;
            final int ctxStart = L.cellCharStart[runMinLogical];
            final int ctxEnd = L.cellCharStart[runMaxLogical] + L.cellCharCount[runMaxLogical];
            final float measuredWidth = mTextPaint.getRunAdvance(line, charStart, charEnd, ctxStart, ctxEnd, rtl, charEnd);

            final int cursorColor = insideCursor ? palette[TextStyle.COLOR_INDEX_CURSOR] : 0;
            final boolean invertCursorTextColor = insideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK;

            drawTextRun(canvas, line, palette, heightOffset, segStartVisualColumn, segWidthColumns,
                charStart, charCount, ctxStart, ctxEnd - ctxStart, measuredWidth, cursorColor, cursorShape, style,
                reverseVideo || invertCursorTextColor || insideSelection, rtl);

            v = segEnd;
        }
    }

    /**
     * Map a visual (on-screen) column to the logical buffer column for a row, inverting the bidi
     * reordering. Returns {@code visualColumn} unchanged for rows that need no bidi. Used for
     * hit-testing so touch text-selection on RTL lines selects the intended characters.
     */
    public int getLogicalColumn(TerminalRow lineObject, int columns, int visualColumn) {
        if (visualColumn < 0 || visualColumn >= columns) return visualColumn;
        final int charsUsedInLine = lineObject.getSpaceUsed();
        if (charsUsedInLine <= 0 || !Bidi.requiresBidi(lineObject.mText, 0, charsUsedInLine)) return visualColumn;
        final BidiLayout L = computeBidiLayout(lineObject, columns, charsUsedInLine);
        if (L == null) return visualColumn;
        for (int i = 0; i < L.cellCount; i++) {
            final int vc = L.cellVisualColumn[i];
            if (visualColumn >= vc && visualColumn < vc + L.cellWidth[i]) return L.cellColumn[i];
        }
        return visualColumn;
    }

    private static boolean cellInsideCursor(int cursorX, int cellColumn, int cellWidth) {
        return cursorX >= cellColumn && cursorX < cellColumn + cellWidth;
    }

    private static boolean cellInsideSelection(int selx1, int selx2, int cellColumn) {
        return selx1 >= 0 && cellColumn >= selx1 && cellColumn <= selx2;
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, int contextCharIndex, int contextCharCount,
                             float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo, boolean rtl) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        // Guard against a zero/degenerate measured width (e.g. a run whose glyphs have no advance
        // because they are missing from the font): scaling by runWidthColumns / mes would divide by
        // ~0 and feed Infinity/NaN into canvas.scale(), silently corrupting the canvas matrix.
        if (mes > 0.01f && Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) {
                // Place the bar cursor on the leading edge of the cell: left for LTR, right for RTL.
                if (rtl) left += ((right - left) * 3) / 4.;
                else right -= ((right - left) * 3) / 4.;
            }
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // The text alignment is the default Paint.Align.LEFT. The last-but-one argument is the
            // run direction: passing the resolved bidi direction lets the platform text engine shape
            // (contextually join) Arabic/Hebrew and lay the glyphs out right-to-left when needed.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, contextCharIndex, contextCharCount, left, y - mFontLineSpacingAndAscent, rtl, mTextPaint);
        }

        if (savedMatrix) canvas.restore();
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }
}
