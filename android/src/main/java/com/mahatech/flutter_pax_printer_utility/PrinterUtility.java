package com.mahatech.flutter_pax_printer_utility;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Log;

import com.pax.poslinkperipheries.ProcessResult;
import com.pax.poslinkperipheries.printer.POSLinkPrinter;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/// The printer, on the PAX POSLink Peripheries SDK.
///
/// The SDK prints whole bitmaps only, so this class keeps the plugin's line-by-line
/// API by building each job in a PageLayout (text, feeds and images, laid out like the
/// PAX printer's own text engine) and printing it as one bitmap on start().
///
/// Printer errors are not swallowed: they throw PaxPrinterException, which the plugin
/// reports to Dart as a PRINTER_ERROR PlatformException.
public class PrinterUtility {
    private static final String TAG = "PAX";
    /// Printer status code for "not support".
    static final int PRINTER_NOT_SUPPORTED = 100;
    /// Longest start() waits for the SDK to report that the slip has printed.
    static final long PRINT_TIMEOUT_MS = 120000;

    /// Paper widths in printer dots.
    static final int PAPER_58MM_DOTS = 384;
    static final int PAPER_80MM_DOTS = 576;

    /// Gray level used until setGray is called (POSLinkPrinter.GrayLevel.STANDARD).
    static final int DEFAULT_GRAY = POSLinkPrinter.GrayLevel.STANDARD;

    private final Context context;
    private PageLayout<Bitmap> page;
    /// Paper width from setPrintWidth, or 0 to use the terminal's paper.
    private int printWidthOverride;
    private int gray = DEFAULT_GRAY;
    /// Height of the last printed page, for getDotLine() after start().
    private int lastPrintedHeight;

    public PrinterUtility(Context context) {
        this.context = context.getApplicationContext();
    }

    private POSLinkPrinter printer() {
        return POSLinkPrinter.getInstance(context);
    }

    /// The width of the paper in dots: 576 on the 80mm models the SDK knows,
    /// otherwise 384 (58mm), unless setPrintWidth chose one.
    int paperWidth() {
        if (printWidthOverride > 0) {
            return printWidthOverride;
        }
        final Integer recommended = POSLinkPrinter.RecommendWidth.MODEL_MAP_RECOMMEND_WIDTH.get(Build.MODEL);
        return recommended != null && recommended >= PAPER_80MM_DOTS ? PAPER_80MM_DOTS : PAPER_58MM_DOTS;
    }

    /// Uses [dots] as the paper width from the next init(); 0 goes back to the
    /// terminal's paper width.
    public void setPrintWidth(int dots) {
        if (dots < 0 || dots > 2048) {
            throw new IllegalArgumentException("Print width must be 0-2048 dots, was " + dots);
        }
        printWidthOverride = dots;
    }

    private PageLayout<Bitmap> page() {
        if (page == null) {
            page = new PageLayout<>(paperWidth());
        }
        return page;
    }

    /// Starts a new job: clears anything not yet printed and resets fonts, spacing,
    /// indent, double size and invert. The gray level is kept.
    public void init() {
        page = new PageLayout<>(paperWidth());
    }

    public String getStatus() {
        return statusCode2Str(printer().getStatus());
    }

    public void fontSet(String asciiFont, String extFont) {
        page().setFont(asciiFont, extFont);
    }

    public void spaceSet(int wordSpace, int lineSpace) {
        page().setSpacing(wordSpace, lineSpace);
    }

    /// The font, spacing and gray level the printReceipt / printQR templates use.
    public void applyTemplateStyle() {
        fontSet("FONT_8_16", "FONT_16_16");
        spaceSet(0, 10);
        setGray(1);
    }

    /// Adds text to the job. [charset] is ignored: text is drawn from its Unicode
    /// characters, so no encoding is needed.
    public void printStr(String str, String charset) {
        page().addText(str);
    }

    public void step(int dots) {
        page().step(dots);
    }

    /// Adds [bitmap] to the job. Returns false, without printing, if there is no
    /// bitmap (an image that could not be loaded, decoded or encoded).
    public boolean printBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            Log.e(TAG, "No bitmap to print (image could not be loaded, decoded or encoded)");
            return false;
        }
        page().addImage(bitmap, bitmap.getWidth(), bitmap.getHeight());
        return true;
    }

    /// The paper size code: 2 for 58mm and 3 for 80mm on the models the SDK lists,
    /// -1 for other models.
    public int getPrinterSize() {
        final Integer recommended = POSLinkPrinter.RecommendWidth.MODEL_MAP_RECOMMEND_WIDTH.get(Build.MODEL);
        if (recommended == null) {
            return -1;
        }
        return recommended >= PAPER_80MM_DOTS ? 3 : 2;
    }

    /// Prints the job as one bitmap. Blocks until the slip has printed and returns
    /// "Success"; throws PaxPrinterException if the printer reports an error. An
    /// empty job prints nothing.
    public String start() throws PaxPrinterException, InterruptedException {
        final PageLayout<Bitmap> job = page();
        page = null;
        if (job.isEmpty()) {
            lastPrintedHeight = 0;
            return statusCode2Str(0);
        }
        final java.util.List<PageLayout.Block> blocks = job.finish();
        final int height = job.height();
        final long renderStart = System.currentTimeMillis();
        final Bitmap bitmap = PageRenderer.render(blocks, job.paperWidth(), height);
        Log.i(TAG, "Rendered " + job.paperWidth() + "x" + height + " in "
                + (System.currentTimeMillis() - renderStart) + " ms");

        final long printStart = System.currentTimeMillis();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<ProcessResult> error = new AtomicReference<>();
        final POSLinkPrinter printer = printer();
        printer.setGray(gray);
        printer.print(bitmap, POSLinkPrinter.CutMode.DO_NOT_CUT, new POSLinkPrinter.PrintListener() {
            @Override
            public void onSuccess() {
                done.countDown();
            }

            @Override
            public void onError(ProcessResult result) {
                error.set(result);
                done.countDown();
            }
        }, 0, true);
        if (!done.await(PRINT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("The printer did not finish within "
                    + PRINT_TIMEOUT_MS / 1000 + " s");
        }
        Log.i(TAG, "start: printed " + height + " dot lines in "
                + (System.currentTimeMillis() - printStart) + " ms");
        lastPrintedHeight = height;
        final ProcessResult result = error.get();
        if (result != null) {
            throw toException("print", result.getCode(), result.getMessage());
        }
        return statusCode2Str(0);
    }

    public void leftIndents(int indent) {
        page().setIndent(indent);
    }

    /// Dot lines in the job so far, or after start(), in the job just printed.
    public int getDotLine() {
        return page != null && !page.isEmpty() ? page.height() : lastPrintedHeight;
    }

    /// Gray level for the next print: POSLinkPrinter.GrayLevel values (1 standard,
    /// 3 or 4 for two-layer thermal paper, or 50-500 as a percentage).
    public void setGray(int level) {
        gray = level;
    }

    public void setDoubleWidth(boolean isAscDouble, boolean isLocalDouble) {
        page().setDoubleWidth(isAscDouble, isLocalDouble);
    }

    public void setDoubleHeight(boolean isAscDouble, boolean isLocalDouble) {
        page().setDoubleHeight(isAscDouble, isLocalDouble);
    }

    public void setInvert(boolean isInvert) {
        page().setInvert(isInvert);
    }

    /// Cuts the paper now; mode 0 is a full cut, 1 a partial cut.
    public void cutPaper(int mode) throws PaxPrinterException {
        final int result = printer().cutPaper(mode);
        Log.i(TAG, "cutPaper(" + mode + "): " + result);
        if (result != 0) {
            final String sdkCode = POSLinkPrinter.ERROR_CODE_MSG_MAP.get(result);
            throw new PaxPrinterException(result, sdkCode, "cutPaper: " + statusCode2Str(result));
        }
    }

    /// Turns a POSLink ProcessResult code into a PaxPrinterException carrying the
    /// printer status code it stands for.
    static PaxPrinterException toException(String operation, String sdkCode, String message) {
        int status = -1;
        for (Map.Entry<Integer, String> entry : POSLinkPrinter.ERROR_CODE_MSG_MAP.entrySet()) {
            if (entry.getValue().equals(sdkCode)) {
                status = entry.getKey();
                break;
            }
        }
        return new PaxPrinterException(status, sdkCode,
                operation + ": " + message + " (" + sdkCode + ")");
    }

    public String statusCode2Str(int status) {
        switch (status) {
            case 0:
                return "Success";
            case 1:
                return "Printer is busy";
            case 2:
                return "Out of paper";
            case 3:
                return "The format of print data packet error";
            case 4:
                return "Printer malfunctions";
            case 8:
                return "Printer over heats";
            case 9:
                return "Printer voltage is too low";
            case 98:
                return "Invalid argument";
            case 99:
                return "Connection error";
            case 100:
                return "Not supported";
            case 101:
                return "No permission";
            case 102:
                return "Printer service busy";
            case 240:
                return "Printing is unfinished";
            case 250:
                return "Cutter jam";
            case 251:
                return "Cover open";
            case 252:
                return "The printer has not installed font library";
            case 254:
                return "Data package is too long";
            default:
                return "Unknown printer status " + status;
        }
    }
}
