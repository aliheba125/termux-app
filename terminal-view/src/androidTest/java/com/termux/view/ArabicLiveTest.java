package com.termux.view;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalOutput;
import com.termux.terminal.TerminalRow;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Live, on-emulator examination of the REAL TerminalRenderer + TerminalEmulator with Arabic,
 * English, mixed, numbers, tashkeel, cursor and selection. Hunts for silent errors (any exception
 * from render() fails the test), documents the cursor-vs-joining behavior, verifies the
 * visual->logical mapping, and decisively probes how Canvas.drawTextRun positions a sub-range that
 * is drawn with a wider shaping context (this determines the correct cursor fix).
 */
@RunWith(AndroidJUnit4.class)
public class ArabicLiveTest {

    static final String TAG = "ARABIC_LIVE";

    static final class Out extends TerminalOutput {
        public void write(byte[] d, int o, int c) {}
        public void titleChanged(String a, String b) {}
        public void onCopyTextToClipboard(String t) {}
        public void onPasteTextFromClipboard() {}
        public void onBell() {}
        public void onColorsChanged() {}
    }

    private static TerminalEmulator emu(int cols, int rows, String input) {
        TerminalEmulator e = new TerminalEmulator(new Out(), cols, rows, 13, 15, rows * 2, null);
        byte[] b = input.getBytes(StandardCharsets.UTF_8);
        e.append(b, b.length);
        return e;
    }

    private static Typeface arabicFont() {
        try {
            return Typeface.createFromAsset(
                InstrumentationRegistry.getInstrumentation().getContext().getAssets(),
                "KawkabMono-Regular.ttf");
        } catch (Throwable t) {
            return Typeface.MONOSPACE;
        }
    }

    /** Count blank vertical-column gaps within the ink extent of a bitmap band, + ascii preview. */
    private static int[] analyze(Bitmap bmp, int y0, int y1, String label, boolean preview) {
        int W = bmp.getWidth();
        boolean[] col = new boolean[W];
        int first = -1, last = -1, inkCols = 0;
        for (int x = 0; x < W; x++) {
            boolean ink = false;
            for (int y = y0; y < y1; y++) if ((bmp.getPixel(x, y) & 0x00FFFFFF) > 0x101010) { ink = true; break; }
            col[x] = ink;
            if (ink) { if (first < 0) first = x; last = x; inkCols++; }
        }
        int gaps = 0; boolean g = false;
        if (first >= 0) for (int x = first; x <= last; x++) { if (!col[x]) { if (!g) { gaps++; g = true; } } else g = false; }
        if (preview) {
            Log.i(TAG, "---- preview [" + label + "] ----");
            int sx = Math.max(1, W / 90), sy = Math.max(1, (y1 - y0) / 12);
            for (int y = y0; y < y1; y += sy) {
                StringBuilder sb = new StringBuilder();
                for (int x = 0; x < W; x += sx) {
                    int m = 0;
                    for (int by = y; by < Math.min(y1, y + sy); by++)
                        for (int bx = x; bx < Math.min(W, x + sx); bx++) m = Math.max(m, bmp.getPixel(bx, by) & 0xFF);
                    sb.append(m > 120 ? '#' : (m > 30 ? '.' : ' '));
                }
                if (sb.toString().trim().length() > 0) Log.i(TAG, sb.toString());
            }
        }
        return new int[]{gaps, inkCols, first, last};
    }

    private void renderInto(Bitmap bmp, TerminalEmulator e, TerminalRenderer r) {
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        r.render(e, c, 0, -1, -1, -1, -1);
    }

    // ===================== SECOND-AUDIT STRESS / EDGE-CASE / STABILITY TESTS =====================

    // Every tricky input must render WITHOUT throwing (any exception fails the test) - covers a 3rd
    // RTL script (Hebrew), emoji (surrogate width-2), CJK+Arabic, single char, lone control chars,
    // all-whitespace/tabs, and a heavy mix. This is the "no silent crash on unseen scenario" check.
    @Test
    public void edgeCaseInputsRenderWithoutCrash() {
        TerminalRenderer r = new TerminalRenderer(32, Typeface.MONOSPACE);
        String[][] cases = {
            {"hebrew", "\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"},           // שלום עולם
            {"emoji_arabic", "\u0645\uD83D\uDE00\u0628"},                              // م😀ب (surrogate width2 between arabic)
            {"cjk_arabic", "\u4E2D\u0645\u6587\u0628"},                               // 中م文ب
            {"arabic_emoji_cjk_latin", "a\u06451\u0628\uD83D\uDE0E\u4E2D\u05E9z"},     // aم1ب😎中שz all-in-one
            {"single_arabic", "\u0645"},
            {"single_hebrew", "\u05E9"},
            {"lone_combining", "\u0645\u064B\u064C\u064D\u0651\u0652"},                // meem + 5 tashkeel
            {"max_combining", "\u0628\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B\u064B"}, // beh + 18 combining (exceeds MAX 15)
            {"all_spaces", "        "},
            {"tabs", "\tab\t\u0645\u0628"},
            {"rtl_marks", "\u200F\u0645\u200E\u0628\u202Bx\u202C"},                    // RLM/LRM/RLE/PDF control marks + arabic
            {"arabic_digits", "\u0661\u0662\u0663 \u0645\u0628 456"},                  // arabic-indic digits ١٢٣ + latin digits
            {"empty", ""},
        };
        for (String[] cse : cases) {
            try {
                TerminalEmulator e = emu(30, 4, cse[1]);
                Bitmap bmp = renderScreen(e, r);
                // also exercise selection over the whole first row and a boundary selection
                renderMulti(emu(30, 4, cse[1]), r, 0, 0, 0, 29);   // full first row selected
                renderMulti(emu(30, 4, cse[1]), r, 0, 2, 5, 3);    // multi-row + inverted cols
                Log.i(TAG, "EDGE '" + cse[0] + "' rendered OK");
            } catch (Throwable t) {
                Log.e(TAG, "EDGE '" + cse[0] + "' CRASHED: " + t, t);
                throw new AssertionError("edge case '" + cse[0] + "' crashed: " + t, t);
            }
        }
    }

    // getLogicalColumn must never throw for ANY column index (including negative and >= columns) on
    // tricky rows - it is called from touch hit-testing with arbitrary values.
    @Test
    public void getLogicalColumnNeverThrows() {
        TerminalRenderer r = new TerminalRenderer(32, Typeface.MONOSPACE);
        String[] rows = {"\u0645\u0631\u062D\u0628\u0627", "a\u0645\uD83D\uDE00\u4E2D\u05E9z", "\u0645", "", "     "};
        for (String content : rows) {
            TerminalEmulator e = emu(20, 3, content);
            TerminalBuffer s = e.getScreen();
            TerminalRow row = s.allocateFullLineIfNecessary(s.externalToInternalRow(0));
            for (int v = -3; v <= 25; v++) {
                int lc = r.getLogicalColumn(row, 20, v); // must not throw or return an OOB column
                if (lc < -1 || lc > 20) throw new AssertionError("getLogicalColumn returned OOB " + lc + " for v=" + v);
            }
        }
        Log.i(TAG, "getLogicalColumn never threw and stayed in range for all tricky rows/columns");
    }

    // Memory stability: render a full screen of Arabic 600 times into ONE reused bitmap (isolating the
    // renderer's per-frame allocations from test bitmap churn). After GC, retained heap must not grow
    // unboundedly (no leak). Quantifies the computeBidiLayout per-frame allocation concern.
    @Test
    public void memoryStableUnderRepeatedArabicRender() throws Exception {
        TerminalRenderer r = new TerminalRenderer(28, Typeface.MONOSPACE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 24; i++) sb.append("\u0645\u0631\u062D\u0628\u0627 \u0627\u0644\u0633\u0644\u0627\u0645 hello 123\r\n");
        TerminalEmulator e = emu(80, 24, sb.toString());
        Bitmap bmp = Bitmap.createBitmap((int) (r.getFontWidth() * 80) + 4, r.getFontLineSpacing() * 24 + 8, Bitmap.Config.ARGB_8888);
        for (int i = 0; i < 30; i++) renderInto(bmp, e, r); // warmup
        Runtime rt = Runtime.getRuntime();
        System.gc(); Thread.sleep(150);
        long before = rt.totalMemory() - rt.freeMemory();
        long t0 = System.nanoTime();
        for (int i = 0; i < 600; i++) renderInto(bmp, e, r);
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;
        System.gc(); Thread.sleep(250);
        long after = rt.totalMemory() - rt.freeMemory();
        Log.i(TAG, "MEM/PERF 600 full-Arabic-screen renders: elapsed=" + elapsedMs + "ms avg="
            + (elapsedMs / 600.0) + "ms/frame retainedDelta=" + (after - before) + " bytes");
        assertTrue("no unbounded heap retention after GC (delta=" + (after - before) + ")", (after - before) < 8_000_000);
    }

    // ANR / O(n^2) guard: a very large paste of Arabic (wrapped across the screen) must render fast.
    @Test
    public void hugeArabicContentRendersFast() {
        TerminalRenderer r = new TerminalRenderer(28, Typeface.MONOSPACE);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8000; i++) sb.append("\u0645\u0631\u062D\u0628\u0627"); // 40k chars, wraps into many rows
        TerminalEmulator e = emu(80, 40, sb.toString());
        Bitmap bmp = Bitmap.createBitmap((int) (r.getFontWidth() * 80) + 4, r.getFontLineSpacing() * 40 + 8, Bitmap.Config.ARGB_8888);
        long t0 = System.nanoTime();
        renderInto(bmp, e, r);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        Log.i(TAG, "HUGE 40k-char Arabic full 80x40 screen render = " + ms + "ms");
        assertTrue("huge Arabic screen must render well under ANR threshold (" + ms + "ms)", ms < 800);
    }

    private Bitmap renderScreen(TerminalEmulator e, TerminalRenderer r) {
        int cols = e.mColumns, rows = e.mRows;
        int W = Math.max(1, (int) Math.ceil(r.getFontWidth() * cols) + 4);
        int H = Math.max(1, r.getFontLineSpacing() * rows + 8);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        r.render(e, c, 0, -1, -1, -1, -1);
        return bmp;
    }

    // ---- Test 1: every scenario renders WITHOUT throwing (silent-error hunt) + mixed ar/en ----
    @Test
    public void allScenariosRenderWithoutError() {
        TerminalRenderer r = new TerminalRenderer(32, arabicFont());
        String[][] cases = {
            {"english", "hello world"},
            {"pure_arabic", "\u0645\u0631\u062D\u0628\u0627"},
            {"arabic_english", "hi \u0645\u0631\u062D\u0628\u0627 world"},
            {"arabic_numbers", "\u0633\u0639\u0631 100 \u0631\u064A\u0627\u0644"},
            {"tashkeel", "\u0645\u064E\u0631\u0652\u062D\u064E\u0628\u064B\u0627"},
            {"multiline", "\u0633\u0637\u0631\u0661\r\nline2 \u0639\u0631\u0628\u064A"},
            {"empty", ""},
        };
        for (String[] cse : cases) {
            TerminalEmulator e = emu(40, 4, cse[1]);
            Bitmap bmp = renderScreen(e, r);
            int[] a = analyze(bmp, 0, r.getFontLineSpacing() + 6, cse[0], true);
            Log.i(TAG, "scenario '" + cse[0] + "' rendered OK, row0 gaps=" + a[0] + " inkCols=" + a[1]);
        }
        // render with a selection AND with cursor moved into mid-word, exercising the complex paths.
        TerminalEmulator e2 = emu(40, 4, "\u0645\u0631\u062D\u0628\u0627");
        TerminalRenderer r2 = new TerminalRenderer(32, arabicFont());
        int cols = e2.mColumns, rows = e2.mRows;
        Bitmap b2 = Bitmap.createBitmap((int) (r2.getFontWidth() * cols) + 4, r2.getFontLineSpacing() * rows + 8, Bitmap.Config.ARGB_8888);
        Canvas c2 = new Canvas(b2);
        c2.drawColor(Color.BLACK);
        r2.render(e2, c2, 0, 0, 0, 1, 3); // selection cols 1..3 on row 0 - exercises RTL selection segmenting
        Log.i(TAG, "render-with-selection on arabic completed without error");
        assertTrue(true);
    }

    // ---- Test 2: DECISIVE probe of drawTextRun sub-range positioning with wider context ----
    // Determines whether we can preserve Arabic joining across a cursor/selection split by passing
    // the full run as shaping context while drawing only the split sub-range.
    // Determines whether preserving Arabic joining across a cursor/selection split is possible by
    // passing the full run as shaping context while drawing only the split sub-range. Arabic is RTL,
    // so we probe with isRtl=true (the real case). Uses a pixel-diff (not just width) to detect
    // whether the wider context actually changes the chosen glyph form.
    @Test
    public void drawTextRunContextPositioningProbe() {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(arabicFont());
        p.setTextSize(56f);
        p.setColor(Color.WHITE);
        char[] word = "\u0628\u0628\u0628".toCharArray(); // ببب : medial beh differs strongly from isolated beh
        float X = 60f, Y = 80f;
        int W = 260, H = 120;

        Bitmap ctx = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(ctx); cc.drawColor(Color.BLACK);
        cc.drawTextRun(word, 1, 1, 0, word.length, X, Y, true, p); // middle beh, context = whole word

        Bitmap iso = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas ic = new Canvas(iso); ic.drawColor(Color.BLACK);
        ic.drawTextRun(word, 1, 1, 1, 1, X, Y, true, p);       // middle beh, context = itself (isolated)

        int[] ca = analyze(ctx, 0, H, "ctx_mid_rtl", true);
        int[] ia = analyze(iso, 0, H, "iso_mid_rtl", true);

        int diff = 0, both = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            int c = ctx.getPixel(x, y) & 0xFF, i = iso.getPixel(x, y) & 0xFF;
            if (c > 30 || i > 30) both++;
            if (Math.abs(c - i) > 40) diff++;
        }
        double diffRatio = both == 0 ? 0 : (double) diff / both;
        boolean shapingDiffers = diffRatio > 0.15;
        boolean positionLeadingEdge = Math.abs(ca[2] - ia[2]) <= 8;

        Log.i(TAG, "PROBE RTL ctx[first=" + ca[2] + ",last=" + ca[3] + ",ink=" + ca[1] + "]"
            + " iso[first=" + ia[2] + ",last=" + ia[3] + ",ink=" + ia[1] + "]"
            + " diffRatio=" + String.format("%.2f", diffRatio)
            + " shapingDiffers=" + shapingDiffers + " positionLeadingEdge=" + positionLeadingEdge);

        // Informational assertions: we log the verdict; only require that ink was produced so the
        // test itself doesn't fail the run (the design decision is taken from the logged values).
        assertTrue("context-drawn middle letter must render ink", ca[1] > 0);
        Log.i(TAG, "VERDICT context-preservation viable = " + (shapingDiffers && positionLeadingEdge));
    }

    // ---- Test 3: real getLogicalColumn mapping on the actual renderer ----
    @Test
    public void logicalColumnMappingRealCode() {
        TerminalRenderer r = new TerminalRenderer(32, Typeface.MONOSPACE);

        TerminalEmulator e1 = emu(20, 3, "\u0645\u0631\u062D\u0628\u0627"); // مرحبا logical 0..4
        TerminalBuffer s1 = e1.getScreen();
        TerminalRow row1 = s1.allocateFullLineIfNecessary(s1.externalToInternalRow(0));
        int l0 = r.getLogicalColumn(row1, 20, 0);
        int l4 = r.getLogicalColumn(row1, 20, 4);
        Log.i(TAG, "pure-arabic getLogicalColumn: visual0->" + l0 + " visual4->" + l4);
        assertEquals("leftmost visual -> last logical (alef)", 4, l0);
        assertEquals("rightmost visual -> first logical (meem)", 0, l4);
        assertEquals("getSelectedText is logical order", "\u0645\u0631\u062D\u0628\u0627", s1.getSelectedText(0, 0, 4, 0));

        TerminalEmulator e2 = emu(20, 3, "hi \u0645\u0631\u062D\u0628\u0627"); // h0 i1 sp2 م3..ا7
        TerminalBuffer s2 = e2.getScreen();
        TerminalRow row2 = s2.allocateFullLineIfNecessary(s2.externalToInternalRow(0));
        assertEquals(0, r.getLogicalColumn(row2, 20, 0));
        assertEquals(1, r.getLogicalColumn(row2, 20, 1));
        assertEquals("visual3 -> logical7 (alef)", 7, r.getLogicalColumn(row2, 20, 3));
        assertEquals("visual7 -> logical3 (meem)", 3, r.getLogicalColumn(row2, 20, 7));
        // english row: identity
        TerminalEmulator e3 = emu(20, 3, "hello");
        TerminalBuffer s3 = e3.getScreen();
        TerminalRow row3 = s3.allocateFullLineIfNecessary(s3.externalToInternalRow(0));
        for (int i = 0; i < 5; i++) assertEquals("english identity", i, r.getLogicalColumn(row3, 20, i));
    }

    // ---- Test 4: cursor on a mid-word Arabic letter must NOT break the joining of its neighbours.
    // We render مرحبا without a cursor and with the cursor on the middle letter, then compare pixels
    // OUTSIDE the cursor cell. If joining is preserved (full-run shaping context) the neighbouring
    // cells render identically; a broken renderer would draw them in isolated forms and differ a lot.
    @Test
    public void cursorPreservesNeighbourJoining() {
        TerminalRenderer r = new TerminalRenderer(40, arabicFont());
        TerminalEmulator noCur = emu(20, 2, "\u0645\u0631\u062D\u0628\u0627\u001b[?25l"); // hide cursor
        TerminalEmulator cur = emu(20, 2, "\u0645\u0631\u062D\u0628\u0627\u001b[3G");      // cursor on logical col 2 (ح)
        Bitmap a = renderScreen(noCur, r);
        Bitmap b = renderScreen(cur, r);

        // Find the VISUAL column that shows logical column 2 (the cursor cell).
        TerminalBuffer s = cur.getScreen();
        TerminalRow row = s.allocateFullLineIfNecessary(s.externalToInternalRow(0));
        int cursorVisualCol = -1;
        for (int v = 0; v < 20; v++) if (r.getLogicalColumn(row, 20, v) == 2) { cursorVisualCol = v; break; }
        float fw = r.getFontWidth();
        int cx0 = (int) (cursorVisualCol * fw) - 2, cx1 = (int) ((cursorVisualCol + 1) * fw) + 2;

        int W = Math.min(a.getWidth(), b.getWidth()), H = Math.min(a.getHeight(), b.getHeight());
        int diff = 0, tot = 0;
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
            if (x >= cx0 && x < cx1) continue; // skip the cursor cell band (it is intentionally inverted)
            int pa = a.getPixel(x, y) & 0xFF, pb = b.getPixel(x, y) & 0xFF;
            tot++;
            if (Math.abs(pa - pb) > 40) diff++;
        }
        double ratio = tot == 0 ? 0 : (double) diff / tot;
        analyze(a, 0, r.getFontLineSpacing() + 6, "no_cursor", true);
        analyze(b, 0, r.getFontLineSpacing() + 6, "cursor_midword", true);
        Log.i(TAG, "cursorPreservesNeighbourJoining: cursorVisualCol=" + cursorVisualCol
            + " diffRatioOutsideCursor=" + String.format("%.4f", ratio));
        assertTrue("cursorVisualCol found", cursorVisualCol >= 0);
        assertTrue("neighbours must render (near) identically with/without cursor => joining preserved (diff="
            + String.format("%.4f", ratio) + ")", ratio < 0.03);
    }

    private Bitmap renderScreenSel(TerminalEmulator e, TerminalRenderer r, int selX1, int selX2) {
        int cols = e.mColumns, rows = e.mRows;
        int W = Math.max(1, (int) Math.ceil(r.getFontWidth() * cols) + 4);
        int H = Math.max(1, r.getFontLineSpacing() * rows + 8);
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        r.render(e, c, 0, 0, 0, selX1, selX2); // selection on row 0, logical columns selX1..selX2
        return bmp;
    }

    private static int[] inkVExtent(Bitmap b) {
        int top = -1, bot = -1;
        for (int y = 0; y < b.getHeight(); y++) {
            boolean ink = false;
            for (int x = 0; x < b.getWidth(); x++) if ((b.getPixel(x, y) & 0xFF) > 40) { ink = true; break; }
            if (ink) { if (top < 0) top = y; bot = y; }
        }
        return new int[]{top, bot};
    }

    private long highlightAdds(TerminalEmulator base, TerminalRenderer r, String text, int sx1, int sx2) {
        Bitmap no = renderScreen(emu(20, 3, text), r);
        Bitmap sel = renderScreenSel(emu(20, 3, text), r, sx1, sx2);
        float fw = r.getFontWidth();
        int xEnd = (int) (5 * fw), y1 = r.getFontLineSpacing() + 6;
        long bNo = 0, bSel = 0;
        for (int y = 0; y < Math.min(y1, no.getHeight()); y++)
            for (int x = 0; x < Math.min(xEnd, no.getWidth()); x++) {
                if ((no.getPixel(x, y) & 0xFF) > 128) bNo++;
                if ((sel.getPixel(x, y) & 0xFF) > 128) bSel++;
            }
        return bSel - bNo;
    }

    // Reproduces the REAL selection flow (TextSelectionCursorController) for Arabic: how a long-press
    // word-select and a left->right drag translate touch to the logical selection range, and whether
    // that range actually produces a visible highlight. This is font-independent (pure logic), so it
    // faithfully reproduces the device behaviour that the direct-range test bypassed.
    @Test
    public void selectionControllerFlowArabic() {
        TerminalRenderer r = new TerminalRenderer(40, Typeface.MONOSPACE);
        String word = "\u0645\u0631\u062D\u0628\u0627";
        TerminalEmulator e = emu(20, 3, word);
        TerminalBuffer s = e.getScreen();
        TerminalRow row = s.allocateFullLineIfNecessary(s.externalToInternalRow(0));

        // (1) long-press at visual middle -> setInitialTextSelectionPosition word expansion
        int selX1 = r.getLogicalColumn(row, 20, 2), selX2 = selX1;
        if (!" ".equals(s.getSelectedText(selX1, 0, selX1, 0))) {
            while (selX1 > 0 && !"".equals(s.getSelectedText(selX1 - 1, 0, selX1 - 1, 0))) selX1--;
            while (selX2 < 19 && !"".equals(s.getSelectedText(selX2 + 1, 0, selX2 + 1, 0))) selX2++;
        }
        Log.i(TAG, "CTRL longpress -> range=[" + selX1 + "," + selX2 + "] highlightAdds=" + highlightAdds(e, r, word, selX1, selX2));

        // (2) drag: start handle at visual col 0, end handle at visual col 4
        int a = r.getLogicalColumn(row, 20, 0), b = r.getLogicalColumn(row, 20, 4);
        Log.i(TAG, "CTRL drag handles: startVis0->logical" + a + "  endVis4->logical" + b + "  (a>b means RTL inversion)");
        // current controller behaviour: collapses when start>end on same row
        int cCollapsed = (a > b) ? a : b; // end handle sets mSelX2=mSelX1 when a>b -> [a,a]
        Log.i(TAG, "CTRL drag CURRENT(collapse) -> range=[" + Math.min(a, cCollapsed) + "," + Math.max(a, cCollapsed) + "]"
            + " highlightAdds=" + highlightAdds(e, r, word, Math.min(a, cCollapsed), Math.max(a, cCollapsed)));
        // proposed fix: normalize (min..max) instead of collapse
        Log.i(TAG, "CTRL drag FIXED(normalize) -> range=[" + Math.min(a, b) + "," + Math.max(a, b) + "]"
            + " highlightAdds=" + highlightAdds(e, r, word, Math.min(a, b), Math.max(a, b)));
    }

    private Bitmap renderMulti(TerminalEmulator e, TerminalRenderer r, int selY1, int selY2, int selX1, int selX2) {
        int cols = e.mColumns, rows = e.mRows;
        Bitmap bmp = Bitmap.createBitmap(Math.max(1, (int) Math.ceil(r.getFontWidth() * cols) + 4),
            Math.max(1, r.getFontLineSpacing() * rows + 8), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.BLACK);
        r.render(e, c, 0, selY1, selY2, selX1, selX2);
        return bmp;
    }

    // bright pixels within the exact vertical band of screen row `rowIndex`.
    private long brightInRow(Bitmap b, TerminalRenderer r, int rowIndex, int cols) {
        int ls = r.getFontLineSpacing();
        int y0 = r.mFontLineSpacingAndAscent + ls * rowIndex;
        int y1 = r.mFontLineSpacingAndAscent + ls * (rowIndex + 1);
        int xEnd = (int) (cols * r.getFontWidth());
        long bright = 0;
        for (int y = Math.max(0, y0); y < Math.min(b.getHeight(), y1); y++)
            for (int x = 0; x < Math.min(xEnd, b.getWidth()); x++)
                if ((b.getPixel(x, y) & 0xFF) > 128) bright++;
        return bright;
    }

    // ISSUE 1 evidence: in a multi-line selection, a middle/last row gets selx1==-1 from render().
    // renderNormalLine treats that as "from column 0" (column>=-1), but renderBidiLine's
    // cellInsideSelection has a `selx1 >= 0` guard, so an ARABIC middle/last row shows NO highlight
    // while LTR rows highlight. This reproduces the device report.
    @Test
    public void multiLineSelectionArabicRowLosesHighlight() {
        TerminalRenderer r = new TerminalRenderer(40, Typeface.MONOSPACE);
        String content = "abcde\r\n\u0645\u0631\u062D\u0628\u0627\r\nvwxyz"; // row0 latin, row1 ARABIC, row2 latin
        Bitmap sel = renderMulti(emu(20, 4, content), r, 0, 2, 0, 4); // select all three rows
        Bitmap no = renderScreen(emu(20, 4, content), r);
        long add0 = brightInRow(sel, r, 0, 20) - brightInRow(no, r, 0, 20); // latin first row
        long add1 = brightInRow(sel, r, 1, 20) - brightInRow(no, r, 1, 20); // ARABIC middle row
        long add2 = brightInRow(sel, r, 2, 20) - brightInRow(no, r, 2, 20); // latin last row
        Log.i(TAG, "MULTILINE highlightAdds row0(latin)=" + add0 + " row1(ARABIC)=" + add1 + " row2(latin)=" + add2);
        // Control: same layout but middle row is LATIN -> should highlight like the others.
        String control = "abcde\r\nfghij\r\nvwxyz";
        Bitmap selC = renderMulti(emu(20, 4, control), r, 0, 2, 0, 4);
        Bitmap noC = renderScreen(emu(20, 4, control), r);
        long addCtrlMid = brightInRow(selC, r, 1, 20) - brightInRow(noC, r, 1, 20);
        Log.i(TAG, "MULTILINE control latin-middle-row highlightAdds=" + addCtrlMid);
        Log.i(TAG, "MULTILINE VERDICT arabic-middle-row-broken=" + (add1 < add0 / 4 && add1 < addCtrlMid / 4));
    }

    // Confirms the fix: an inverted (descending) selection range - as produced by an RTL drag -
    // still highlights the full word because render() normalizes it.
    @Test
    public void renderNormalizesInvertedRtlSelection() {
        TerminalRenderer r = new TerminalRenderer(40, Typeface.MONOSPACE);
        String word = "\u0645\u0631\u062D\u0628\u0627";
        long forward = highlightAdds(null, r, word, 0, 4);
        long inverted = highlightAdds(null, r, word, 4, 0); // RTL descending range (start>end)
        Log.i(TAG, "RENDER-NORM forward[0,4]=" + forward + " inverted[4,0]=" + inverted);
        assertTrue("forward selection highlights", forward > 1000);
        assertTrue("inverted RTL selection must still highlight (render normalizes), got " + inverted,
            inverted > forward / 2);
    }

    // ---- Diagnostics for the two device-reported issues: (1) Arabic looks bigger than Latin,
    // (2) selection highlight not visible over Arabic. Uses MONOSPACE so Arabic falls back to the
    // system font (the user's default scenario). Informational: logs measurements.
    @Test
    public void diagnostics_sizeAndSelection() {
        TerminalRenderer r = new TerminalRenderer(40, Typeface.MONOSPACE);
        int ls = r.getFontLineSpacing();

        Bitmap latin = renderScreen(emu(20, 3, "HELLO"), r);
        Bitmap arabic = renderScreen(emu(20, 3, "\u0645\u0631\u062D\u0628\u0627"), r);
        int[] lv = inkVExtent(latin), av = inkVExtent(arabic);
        int lh = lv[1] - lv[0], ah = av[1] - av[0];
        Log.i(TAG, "SIZE lineSpacing=" + ls + " | latin inkH=" + lh + " [" + lv[0] + "," + lv[1] + "]"
            + " | arabic inkH=" + ah + " [" + av[0] + "," + av[1] + "]"
            + " | arabic/latin=" + String.format("%.2f", (double) ah / Math.max(1, lh))
            + " | arabicOverflowsCell=" + (ah > ls));

        for (String[] cse : new String[][]{{"latin", "hello"}, {"arabic", "\u0645\u0631\u062D\u0628\u0627"}}) {
            Bitmap no = renderScreen(emu(20, 3, cse[1]), r);
            Bitmap sel = renderScreenSel(emu(20, 3, cse[1]), r, 0, 4);
            float fw = r.getFontWidth();
            int xEnd = (int) (5 * fw), y1 = ls + 6;
            long bNo = 0, bSel = 0; int tot = 0;
            for (int y = 0; y < Math.min(y1, no.getHeight()); y++)
                for (int x = 0; x < Math.min(xEnd, no.getWidth()); x++) {
                    tot++;
                    if ((no.getPixel(x, y) & 0xFF) > 128) bNo++;
                    if ((sel.getPixel(x, y) & 0xFF) > 128) bSel++;
                }
            Log.i(TAG, "SELECTION " + cse[0] + " brightNoSel=" + bNo + " brightSel=" + bSel
                + " highlightAdds=" + (bSel - bNo) + " tot=" + tot
                + " => highlightVisible=" + ((bSel - bNo) > tot / 20));
            analyze(sel, 0, y1, "sel_" + cse[0], true);
        }
    }
}
