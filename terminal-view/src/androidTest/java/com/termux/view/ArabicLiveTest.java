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
    @Test
    public void drawTextRunContextPositioningProbe() {
        for (boolean rtl : new boolean[]{false, true}) {
            probe(rtl);
        }
    }

    private void probe(boolean rtl) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(arabicFont());
        p.setTextSize(56f);
        p.setColor(Color.WHITE);
        char[] word = "\u0628\u0628\u0628".toCharArray(); // ببب : middle beh has a very different medial form
        float X = 60f, Y = 80f;
        int W = 240, H = 120;

        // (a) middle letter drawn WITH full-word context
        Bitmap ctx = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas cc = new Canvas(ctx); cc.drawColor(Color.BLACK);
        cc.drawTextRun(word, 1, 1, 0, word.length, X, Y, rtl, p);
        int[] ca = analyze(ctx, 0, H, "ctx_mid_" + (rtl ? "rtl" : "ltr"), false);

        // (b) middle letter drawn ISOLATED (context = itself)
        Bitmap iso = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas ic = new Canvas(iso); ic.drawColor(Color.BLACK);
        ic.drawTextRun(word, 1, 1, 1, 1, X, Y, rtl, p);
        int[] ia = analyze(iso, 0, H, "iso_mid_" + (rtl ? "rtl" : "ltr"), false);

        int ctxWidth = ca[3] - ca[2], isoWidth = ia[3] - ia[2];
        boolean shapingDiffers = (ca[1] != ia[1]) || (ctxWidth != isoWidth);
        boolean positionLeadingEdge = Math.abs(ca[2] - ia[2]) <= 6; // both start near X, context doesn't shift

        Log.i(TAG, "PROBE " + (rtl ? "RTL" : "LTR")
            + " ctx[first=" + ca[2] + ",last=" + ca[3] + ",ink=" + ca[1] + "]"
            + " iso[first=" + ia[2] + ",last=" + ia[3] + ",ink=" + ia[1] + "]"
            + " shapingDiffers=" + shapingDiffers + " positionLeadingEdge=" + positionLeadingEdge);

        assertTrue("context-drawn middle letter must render ink", ca[1] > 0);
        assertTrue((rtl ? "RTL" : "LTR") + ": wider context must change the glyph form (medial vs isolated)", shapingDiffers);
        assertTrue((rtl ? "RTL" : "LTR") + ": sub-range must be positioned at x (context must NOT offset it)", positionLeadingEdge);
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
}
