package com.mahatech.flutter_pax_printer_utility;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.pax.poslinkperipheries.printer.DeviceModel;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import io.flutter.embedding.engine.plugins.FlutterPlugin;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;

/** FlutterPaxPrinterUtilityPlugin */
public class FlutterPaxPrinterUtilityPlugin implements FlutterPlugin, MethodCallHandler {
  /// The MethodChannel that will the communication between Flutter and native Android
  ///
  /// This local reference serves to register the plugin with the Flutter Engine and unregister it
  /// when the Flutter Engine is detached from the Activity
  private MethodChannel channel;
  /// Separate channel for terminal information, used by PaxDeviceInfo in Dart.
  private MethodChannel deviceInfoChannel;
  private static PrinterUtility printerUtility;
  private static android.content.Context printerContext;

  /// All PAX SDK calls run on this one background thread, in the order Dart sent them.
  ///
  /// start() is synchronous: it returns only when the slip has finished printing,
  /// which takes seconds. Running it (and the SDK's first-use setup) on the main
  /// thread would freeze the app's UI for the whole print. A single thread keeps print
  /// jobs strictly in order, so a second job's init() can never clear the buffer while
  /// an earlier job is still printing. Shared by all engines, like printerUtility.
  private static final ExecutorService printerThread = Executors.newSingleThreadExecutor(r -> {
    Thread thread = new Thread(r, "pax-printer");
    thread.setDaemon(true);
    return thread;
  });
  private static final Handler mainHandler = new Handler(Looper.getMainLooper());

  /// printImageUrl downloads run here, so a slow server never holds the printer thread
  /// for longer than IMAGE_DOWNLOAD_TIMEOUT_MS.
  private static final ExecutorService downloadThreads = Executors.newCachedThreadPool(r -> {
    Thread thread = new Thread(r, "pax-image-download");
    thread.setDaemon(true);
    return thread;
  });
  /// Longest printImageUrl waits for its image, counted from the call.
  static final long IMAGE_DOWNLOAD_TIMEOUT_MS = 30000;

  /// The downloaded image, or null if it failed or was not ready by [deadline].
  private static Bitmap awaitImage(Future<Bitmap> image, long deadline, String url) {
    try {
      return image.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      image.cancel(true);
      android.util.Log.e("PAX", "Image download timed out: " + url);
      return null;
    } catch (ExecutionException e) {
      android.util.Log.e("PAX", "Image download failed: " + url, e.getCause());
      return null;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private interface PrinterCall {
    void handle(@NonNull MethodCall call, @NonNull Result result) throws Exception;
  }

  /// A Result that replies on the main thread, as Flutter requires, and at most once.
  private static final class MainThreadResult implements Result {
    private final Result result;
    private final AtomicBoolean replied = new AtomicBoolean(false);

    MainThreadResult(Result result) {
      this.result = result;
    }

    @Override
    public void success(Object value) {
      if (replied.compareAndSet(false, true)) {
        mainHandler.post(() -> result.success(value));
      }
    }

    @Override
    public void error(@NonNull String code, String message, Object details) {
      if (replied.compareAndSet(false, true)) {
        mainHandler.post(() -> result.error(code, message, details));
      }
    }

    @Override
    public void notImplemented() {
      if (replied.compareAndSet(false, true)) {
        mainHandler.post(result::notImplemented);
      }
    }
  }

  /// Runs [handler] on the printer thread. If it throws, Dart gets an error instead of
  /// waiting forever for a reply: PRINTER_ERROR (details: the printer status code) for
  /// a printer error such as no paper, PAX_ERROR for anything else.
  private static void runOnPrinterThread(MethodCall call, Result result, PrinterCall handler) {
    final MainThreadResult reply = new MainThreadResult(result);
    printerThread.execute(() -> {
      try {
        handler.handle(call, reply);
      } catch (PaxPrinterException e) {
        android.util.Log.e("PAX", call.method + " failed: " + e.getMessage());
        reply.error("PRINTER_ERROR", call.method + " failed: " + e.getMessage(), e.errCode);
      } catch (Throwable e) {
        android.util.Log.e("PAX", call.method + " failed", e);
        reply.error("PAX_ERROR", call.method + " failed: " + e, null);
      }
    });
  }

  @Override
  public void onAttachedToEngine(@NonNull FlutterPluginBinding flutterPluginBinding) {
    channel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "flutter_pax_printer_utility");
    channel.setMethodCallHandler(this);
    printerContext = flutterPluginBinding.getApplicationContext();
    printerUtility = new PrinterUtility(printerContext);

    deviceInfoChannel = new MethodChannel(flutterPluginBinding.getBinaryMessenger(), "pax_device_info");
    deviceInfoChannel.setMethodCallHandler((call, result) ->
        runOnPrinterThread(call, result, (c, r) -> {
          if (c.method.equals("getSerialNumber")) {
            getSerialNumber(r);
          } else if (c.method.equals("getTermInfo")) {
            getTermInfo(r);
          } else {
            r.notImplemented();
          }
        }));
  }

  /// Replies with what is known about the terminal: MODEL (Android's model name),
  /// SN (the serial number from the PAX SDK) and FIRMWARE (Android's build
  /// display ID, which on PAX terminals is the PayDroid firmware version). Keys
  /// without a value are left out.
  private void getTermInfo(@NonNull Result result) {
    Map<String, String> info = new java.util.HashMap<>();
    putIfPresent(info, "MODEL", android.os.Build.MODEL);
    putIfPresent(info, "SN", DeviceModel.getSN(printerContext));
    putIfPresent(info, "FIRMWARE", android.os.Build.DISPLAY);
    result.success(info);
  }

  private static void putIfPresent(Map<String, String> map, String key, String value) {
    if (value != null && !value.isEmpty()) {
      map.put(key, value);
    }
  }

  /// Replies with the terminal's serial number from the PAX SDK (DeviceModel.getSN).
  private void getSerialNumber(@NonNull Result result) {
    String serialNumber = DeviceModel.getSN(printerContext);
    if (serialNumber == null || serialNumber.isEmpty()) {
      result.error("PAX_ERROR", "Device serial number is empty", null);
      return;
    }
    result.success(serialNumber);
  }

  @Override
  public void onMethodCall(@NonNull MethodCall call, @NonNull Result result) {
    if (call.method.equals("getPlatformVersion")) {
      result.success("Android " + android.os.Build.VERSION.RELEASE);
    } else if (call.method.equals("printImageUrl")) {
      // Start the download now, alongside any earlier print jobs, so the printer thread
      // only waits for what is left of it. The image still keeps its place in the job.
      final String url = call.argument("url");
      final long deadline = System.currentTimeMillis() + IMAGE_DOWNLOAD_TIMEOUT_MS;
      final Future<Bitmap> image = downloadThreads.submit(() -> QRCodeUtil.getBitmapFromURL(url));
      runOnPrinterThread(call, result,
          (c, r) -> r.success(printerUtility.printBitmap(awaitImage(image, deadline, url))));
    } else {
      runOnPrinterThread(call, result, this::handlePrinterCall);
    }
  }

  /// Handles every printer method. Runs on the printer thread; see printerThread.
  private void handlePrinterCall(@NonNull MethodCall call, @NonNull Result result)
      throws Exception {
    if (call.method.equals("init")) { // instant bind or init
      printerUtility.init();
      result.success(true);
    } else if (call.method.equals("getStatus")) {
      String status = printerUtility.getStatus();
      result.success(status);
    }  else if (call.method.equals("printReceipt")) { // instant print receipt
      String text = call.argument("text");
      printerUtility.init();
      printerUtility.applyTemplateStyle();
      printerUtility.printStr(text, null);
      printerUtility.printStr("", null);
      printerUtility.step(150);
      final String status = printerUtility.start();
      result.success(status);
    } else if (call.method.equals("printReceiptWithQr")) { // instant print receipt
      String text = call.argument("text");
      String qrString = call.argument("qr_string");
      printerUtility.init();
      printerUtility.applyTemplateStyle();
      printerUtility.printStr(text, null);
      printerUtility.printStr("", null);
      if (qrString != null) {
        printerUtility.printBitmap(QRCodeUtil.encodeAsBitmap(qrString, 512, 512));
        printerUtility.printStr("", null);
      }
      printerUtility.step(150);
      final String status = printerUtility.start();
      result.success(status);
    } else if (call.method.equals("printQR")) { // instant print qrcode
      String text1 = call.argument("text1");
      String text2 = call.argument("text2");
      String text3 = call.argument("text3");
      String text4 = call.argument("text4");
      String qrString = call.argument("qr_string");
      printerUtility.init();
      printerUtility.applyTemplateStyle();
      printerUtility.printStr(text1, null);
      printerUtility.printStr("", null);
      printerUtility.printStr(text2, null);
      printerUtility.printStr(text3, null);
      printerUtility.printStr("", null);
      printerUtility.printBitmap(QRCodeUtil.encodeAsBitmap(qrString, 512, 512));
      printerUtility.printStr("", null);
      printerUtility.printStr(text4, null);
      printerUtility.step(150);
      final String status = printerUtility.start();
      result.success(status);
    } else if (call.method.equals("fontSet")) {
      printerUtility.fontSet(
          fontOrDefault(call.argument("asciiFontType"), PageLayout.DEFAULT_ASCII_FONT),
          fontOrDefault(call.argument("cFontType"), PageLayout.DEFAULT_EXT_FONT));
      result.success(true);
    } else if (call.method.equals("spaceSet")) {
      String wordSpace = call.argument("wordSpace");
      String lineSpace = call.argument("lineSpace");
      printerUtility.spaceSet(Integer.parseInt(wordSpace), Integer.parseInt(lineSpace));
      result.success(true);
    } else if (call.method.equals("printStr")) {
      String text = call.argument("text");
      String charset = call.argument("charset");
      printerUtility.printStr(text, charset);
      result.success(true);
    } else if (call.method.equals("step")) {
      int step = call.argument("step");
      printerUtility.step(step);
      result.success(true);
    } else if (call.method.equals("printBitmap")) {
      byte[] bytes = call.argument("bitmap");
      // decodeByteArray returns null for bytes that are not an image; printBitmap then returns false.
      Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
      result.success(printerUtility.printBitmap(bitmap));
    }  else if (call.method.equals("printImageAsset")) {
      byte[] bytes = call.argument("bitmap");
      // decodeByteArray returns null for bytes that are not an image; printBitmap then returns false.
      Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
      result.success(printerUtility.printBitmap(bitmap));
    } else if (call.method.equals("printQRCode")) {
      String qrString = call.argument("text");
      int width = call.argument("width");
      int height = call.argument("height");
      result.success(printerUtility.printBitmap(QRCodeUtil.encodeAsBitmap(qrString, width, height)));
    } else if (call.method.equals("printBarcode")) {
      String data = call.argument("data");
      String formatName = call.argument("format");
      int width = call.argument("width");
      int height = call.argument("height");
      int maxWidth = call.argument("maxWidth");
      Bitmap barcode;
      try {
        barcode = QRCodeUtil.encodeBarcode(data, com.google.zxing.BarcodeFormat.valueOf(formatName), width, height, maxWidth);
      } catch (Exception e) {
        result.error("INVALID_BARCODE", "Cannot encode \"" + data + "\" as " + formatName + ": " + e.getMessage(), null);
        return;
      }
      if (barcode.getWidth() > maxWidth) {
        result.error("BARCODE_TOO_WIDE", formatName + " barcode needs " + barcode.getWidth()
                + " dots but the paper is " + maxWidth + " dots wide; shorten the data", null);
        return;
      }
      result.success(printerUtility.printBitmap(barcode));
    } else if (call.method.equals("printDivider")) {
      int width = call.argument("width");
      int thickness = call.argument("thickness");
      boolean dashed = call.argument("dashed");
      result.success(printerUtility.printBitmap(QRCodeUtil.dividerBitmap(width, thickness, dashed)));
    } else if (call.method.equals("setPrintWidth")) {
      int dots = call.argument("dots");
      printerUtility.setPrintWidth(dots);
      result.success(true);
    } else if (call.method.equals("getPrinterSize")) {
      result.success(printerUtility.getPrinterSize());
    } else if (call.method.equals("start")) {
      final String status = printerUtility.start();
      result.success(status);
    } else if (call.method.equals("leftIndents")) {
      int indent = call.argument("indent");
      printerUtility.leftIndents(indent);
      result.success(true);
    } else if (call.method.equals("getDotLine")) {
      int dontLine = printerUtility.getDotLine();
      result.success(dontLine);
    } else if (call.method.equals("setGray")) {
      int level = call.argument("level");
      printerUtility.setGray(level);
      result.success(true);
    } else if (call.method.equals("setDoubleWidth")) {
      boolean isAscDouble = call.argument("isAscDouble");
      boolean isLocalDouble = call.argument("isLocalDouble");
      printerUtility.setDoubleWidth(isAscDouble, isLocalDouble);
      result.success(true);
    } else if (call.method.equals("setDoubleHeight")) {
      boolean isAscDouble = call.argument("isAscDouble");
      boolean isLocalDouble = call.argument("isLocalDouble");
      printerUtility.setDoubleHeight(isAscDouble, isLocalDouble);
      result.success(true);
    } else if (call.method.equals("setInvert")) {
      boolean isInvert = call.argument("isInvert");
      printerUtility.setInvert(isInvert);
      result.success(true);
    } else if (call.method.equals("cutPaper")) {
      int mode = call.argument("mode");
      printerUtility.cutPaper(mode);
      result.success(true);
    } else {
      result.notImplemented();
    }
  }

  /// [name] if it is a FONT_<width>_<height> font name, else [fallback]. Unknown
  /// names print in the default font, as with the earlier SDK.
  private static String fontOrDefault(String name, String fallback) {
    try {
      PageLayout.fontCell(name);
      return name;
    } catch (IllegalArgumentException e) {
      android.util.Log.w("PAX", "Unknown font " + name + ", using " + fallback);
      return fallback;
    }
  }

  @Override
  public void onDetachedFromEngine(@NonNull FlutterPluginBinding binding) {
    channel.setMethodCallHandler(null);
    deviceInfoChannel.setMethodCallHandler(null);
  }
}
