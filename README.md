# flutter_pax_printer_utility

#### This package is based on the PAX POSLink Peripheries SDK (V2.02.00G) for PAX Android terminals

The plugin ships no native libraries, so apps that use it need nothing extra
for Google Play's 16 KB page size requirement.

## Important: 
  **THIS PACKAGE WILL WORK ONLY IN ANDROID!**

## Installation

Add the plugin as a git dependency in your app's `pubspec.yaml`. The package
on pub.dev is the original author's NeptuneLite-based version, without the
changes described here.

```yaml
dependencies:
  flutter_pax_printer_utility:
    git:
      url: https://github.com/FifoTheHein/flutter_pax_printer_utility.git
      ref: 0.2.0
```

```dart
import 'package:flutter_pax_printer_utility/flutter_pax_printer_utility.dart';
// Only for the serial number and terminal info:
import 'package:flutter_pax_printer_utility/pax_device_info.dart';
```

## Release builds

The plugin ships the PAX SDK's ProGuard/R8 keep rules, so release builds work
with `minifyEnabled` and `shrinkResources` on; no build changes are needed.

## Quick start

Printing works in two steps: calls such as `printStr`, `printRow` and
`printBarcode` add to a print job, and `start` prints it. `init` starts a new
job (it clears anything not yet printed and resets the font, spacing, indent,
double size and invert).

```dart
await FlutterPaxPrinterUtility.init;
await FlutterPaxPrinterUtility.printStr('Hello from PAX\n', null);
await FlutterPaxPrinterUtility.step(150); // feed 150 dots of blank paper after the text
final String? status = await FlutterPaxPrinterUtility.start(); // "Success"
```

If the printer reports an error while printing (for example no paper), `start`
throws a `PlatformException` with code `PRINTER_ERROR`; see [Errors](#errors).

`init`, `getStatus` and `getPaperSize` are getters, so they have no `()`.
All calls run in order on one background thread, and `start` waits until the
slip has printed without blocking the app's UI.

### How printing works

The PAX POSLink Peripheries SDK prints whole images. The plugin builds each job
from `init` to `start` into one page, laid out like the PAX printer's own
fonts: `FONT_12_24` characters are 12 x 24 dots, so 32 fit on 58mm paper.
Text longer than a line wraps at the paper edge. `start` prints the page as
one image. A slip of about 1,500 dot lines takes about 2.5 seconds on an A910S.

## Printer status

```dart
final PrinterStatus status = await FlutterPaxPrinterUtility.getStatus;
if (status == PrinterStatus.OUT_OF_PAPER) {
  // ask the user to load paper
}
```

## Text

```dart
await FlutterPaxPrinterUtility.init;

// Font: an ASCII font and a font for other characters, named width_height in dots.
await FlutterPaxPrinterUtility.fontSet(
    EFontTypeAscii.FONT_12_24, EFontTypeExtCode.FONT_24_24);

// Extra dots between characters and between lines.
await FlutterPaxPrinterUtility.spaceSet(0, 10); // each 0-255

// Print darkness: 1 is the default; 3 or 4 for two-layer thermal paper;
// 50-500 sets a percentage.
await FlutterPaxPrinterUtility.setGray(1);

// Left margin in dots, for the lines and images that follow.
await FlutterPaxPrinterUtility.leftIndents(20);

// '\n' starts a new line. The charset argument is ignored.
await FlutterPaxPrinterUtility.printStr('Thank you for your purchase\n', null);

// Double width / height: the first flag is for ASCII characters, the second for others.
await FlutterPaxPrinterUtility.setDoubleWidth(true, true);
await FlutterPaxPrinterUtility.setDoubleHeight(true, true);
await FlutterPaxPrinterUtility.printStr('TOTAL R 99.00\n', null);
await FlutterPaxPrinterUtility.setDoubleWidth(false, false);
await FlutterPaxPrinterUtility.setDoubleHeight(false, false);

// White text on black.
await FlutterPaxPrinterUtility.setInvert(true);
await FlutterPaxPrinterUtility.printStr(' PAID \n', null);
await FlutterPaxPrinterUtility.setInvert(false);

await FlutterPaxPrinterUtility.step(150);
await FlutterPaxPrinterUtility.start();
```

Every `EFontTypeAscii` and `EFontTypeExtCode` font works, including
`FONT_16_24`, `FONT_16_48`, `FONT_32_24` and `FONT_32_48`, which NeptuneLite
had deprecated: each font is a cell of the named size in dots. Text is drawn
in a bold monospace font fitted to that cell; there is no separate bold
setting. Latin-1 characters (including accented letters and the no-break space
in currency amounts) use the ASCII font, other characters the second font.

The gray level stays set across `init`; everything else in this section goes
back to its default.

## Rows

`printRow` lays out columns in the current font, wrapping long text within its column:

```dart
final width = FlutterPaxPrinterUtility.charsPerLine(EFontTypeAscii.FONT_12_24); // 32 on 58mm paper
await FlutterPaxPrinterUtility.printRow([
  const PrintColumn('2', width: 4),
  const PrintColumn('Coffee'),
  const PrintColumn('R 50.00', width: 10, align: PrintAlign.RIGHT),
], charsPerLine: width);
```

Columns with a `width` get exactly that many characters; the others share the
rest by `flex` (default 1). `align` is `LEFT`, `CENTER` or `RIGHT`.
`formatRow` returns the laid-out text without printing it. `charsPerLine`
takes the paper size too (`paperSize: PaxPaperSize.MM_80`); halve it while
double width is on.

## Dividers

```dart
await FlutterPaxPrinterUtility.printDivider();                          // solid, 2 dots thick
await FlutterPaxPrinterUtility.printDivider(dashed: true, thickness: 1);
await FlutterPaxPrinterUtility.printDivider(paperSize: PaxPaperSize.MM_80);
```

## Barcodes and QR codes

Barcodes are encoded on the device and added to the print job as an image:

```dart
await FlutterPaxPrinterUtility.printBarcode('600123456789', PaxBarcodeFormat.EAN_13);
await FlutterPaxPrinterUtility.printBarcode('ABC-000123', PaxBarcodeFormat.CODE_128, height: 80);
await FlutterPaxPrinterUtility.printBarcode('https://example.com', PaxBarcodeFormat.QR_CODE, width: 300);

// QR code with an explicit size in dots.
await FlutterPaxPrinterUtility.printQRCode('https://example.com', 300, 300);
await FlutterPaxPrinterUtility.start();
```

Supported formats: `CODE_128`, `CODE_39`, `CODE_93`, `CODABAR`, `EAN_8`, `EAN_13`, `UPC_A`, `UPC_E`, `ITF`, `QR_CODE`, `PDF_417`, `DATA_MATRIX`, `AZTEC`.
Data that isn't valid for the format (for example letters in an EAN-13) throws a `PlatformException` with code `INVALID_BARCODE`; a barcode wider than the paper throws `BARCODE_TOO_WIDE`.

## Images

```dart
// From your app's assets (declared in pubspec.yaml).
await FlutterPaxPrinterUtility.printImageAsset('assets/images/logo.png');

// From the web. Returns false if the image can't be downloaded within 30 seconds.
final bool? printed = await FlutterPaxPrinterUtility.printImageUrl('https://example.com/logo.png');

// From encoded image bytes (PNG, JPEG, ...), for example a widget captured with RepaintBoundary.
final Uint8List pngBytes = await renderReceiptWidget(); // your own code
await FlutterPaxPrinterUtility.printBitmap(pngBytes);

await FlutterPaxPrinterUtility.start();
```

Images print at their own size, starting at the left margin. An image wider
than the paper (after the left margin) is scaled down to fit, keeping its
shape.

## Templates

Each template starts a new job and prints it, in `FONT_8_16` with 10 dots
between lines. It returns "Success", or throws `PRINTER_ERROR` like `start`:

```dart
await FlutterPaxPrinterUtility.printReceipt('My Shop\nTotal: R 99.00\n');
await FlutterPaxPrinterUtility.printReceiptWithQr('My Shop\nScan to pay:\n', 'https://example.com/pay/123');
await FlutterPaxPrinterUtility.printQRReceipt('My Shop', 'Order 123', 'Table 4', 'Thank you', 'https://example.com/order/123');
```

## Paper

```dart
// Paper width: MM_58 or MM_80 on the models the PAX SDK lists, else UNKNOWN.
final PaxPaperSize paper = await FlutterPaxPrinterUtility.getPaperSize;

// Cut the paper: 0 = full cut, 1 = partial cut. Terminals without a cutter
// throw PRINTER_ERROR (details: 100, "Not supported").
await FlutterPaxPrinterUtility.cutPaper(0);

// Dot lines in the job so far; after start, in the job just printed.
final int? dotLine = await FlutterPaxPrinterUtility.getDotLine();

// Page width in dots for the next jobs (0 = the terminal's paper).
await FlutterPaxPrinterUtility.setPrintWidth(576);
```

`getPaperSize` knows the models the PAX SDK lists with a print width (A920,
A930, A80, E500, E600, E700, E800, SK600, SK700, SK800 and others). Other
terminals, such as the A910S, return `UNKNOWN` and print on 384-dot (58mm)
pages. A page wider than the printer is scaled down by the terminal to fit, so
a wider `setPrintWidth` prints smaller, not cut off (seen on an A910S).

## Terminal information

```dart
final String? serial = await PaxDeviceInfo.getSerial();
final Map<String, String> info = await PaxDeviceInfo.getTermInfo();
print(info['MODEL']);    // for example A910S
print(info['SN']);       // serial number
print(info['FIRMWARE']); // PayDroid firmware, for example PayDroid_10.0.0_Sagittarius_V11.1.39_20250909
```

The PAX SDK has no separate serial number or version for the built-in
printer, so these are the terminal's.

## Errors

Calls throw a `PlatformException` when something goes wrong:
- `PRINTER_ERROR`: the printer reported an error, for example no paper while
  `start` prints, or a function the terminal does not support. `details` is
  the printer status code: 2 = out of paper, 100 = not supported, and so on.
- `PAX_ERROR`: any other native failure, for example a device without the PAX
  services.
- `INVALID_BARCODE` / `BARCODE_TOO_WIDE`: see [Barcodes and QR codes](#barcodes-and-qr-codes).

`printBitmap`, `printImageAsset`, `printImageUrl` and `printQRCode` return
`false`, without printing, when there is no image to print (bytes that are not
an image, a download that failed, or text that cannot be encoded as a QR code).

```dart
try {
  await FlutterPaxPrinterUtility.cutPaper(0);
} on PlatformException catch (e) {
  if (e.code == 'PRINTER_ERROR') {
    print('Printer error ${e.details}: ${e.message}');
  }
}
```

## What this package can do
- [✅] Initialize the printer => `init`
- [✅] Get the printer status => `getStatus`
- [✅] Set the print darkness => `setGray`
- [✅] Print white on black => `setInvert`
- [✅] Change the font => `fontSet`
- [✅] Change character and line spacing => `spaceSet`
- [✅] Print text => `printStr`
- [✅] Feed paper => `step`
- [✅] Double width / double height text => `setDoubleWidth`, `setDoubleHeight`
- [✅] Print QR codes => `printQRCode` or `printBarcode`
- [✅] Print barcodes => `printBarcode` with a `PaxBarcodeFormat`
- [✅] Cut paper => `cutPaper`
- [✅] Print images from the web, assets or bytes => `printImageUrl`, `printImageAsset`, `printBitmap`
- [✅] Set the left margin => `leftIndents`
- [✅] Print rows like a receipt with custom width and alignment => `printRow`
- [✅] Draw a divider line => `printDivider` (solid or dashed)
- [✅] Get the paper size => `getPaperSize` (models the PAX SDK lists)
- [✅] Set the page width => `setPrintWidth`
- [✅] Get the terminal's serial number => `PaxDeviceInfo.getSerial()`
- [✅] Get the terminal's model, serial number and firmware version => `PaxDeviceInfo.getTermInfo()`

## Tested devices

- PAX A910S (32-bit, Android 10, PayDroid V11.1.39): version 0.2.0
- PAX A910S (64-bit, Android 12, PayDroid 12 Acacia V13.1.14): version 0.2.0
- PAX A920: earlier NeptuneLite-based versions only
