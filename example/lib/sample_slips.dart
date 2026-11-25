import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/painting.dart';
import 'package:flutter_pax_printer_utility/flutter_pax_printer_utility.dart';

/// Sample slips laid out the way a fuel point-of-sale app prints them: a wide
/// banner image scaled to the paper, 32-character lines in FONT_12_24 with 10
/// dots between lines, a signature image, and paper feeds between the parts.
///
/// All names and numbers are made up. Each slip runs the same calls in the
/// same order as such an app, so the printout shows whether its slips keep
/// their layout.
class SampleSlips {
  SampleSlips._();

  static const String starLine = '********************************';
  static const String dashLine = '--------------------------------';
  static const String equalsLine = '================================';
  static const int grayLevel = 1;
  static const int standardStep = 50;
  static const int finalStep = 150;

  /// en_ZA currency formatting groups thousands with a no-break space (U+00A0).
  static const String amount = 'R2 410,55';

  static String _date(DateTime t) {
    String two(int n) => n.toString().padLeft(2, '0');
    return '${two(t.day)}/${two(t.month)}/${t.year} ${two(t.hour)}:${two(t.minute)}';
  }

  /// Draws a [width] x [height] PNG with [paint].
  static Future<Uint8List> _png(
      int width, int height, void Function(Canvas canvas, Size size) paint) async {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    final size = Size(width.toDouble(), height.toDouble());
    canvas.drawRect(Offset.zero & size, Paint()..color = const Color(0xFFFFFFFF));
    paint(canvas, size);
    final image = await recorder.endRecording().toImage(width, height);
    final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
    return bytes!.buffer.asUint8List();
  }

  static void _text(Canvas canvas, String text, double fontSize, Offset centre) {
    final painter = TextPainter(
      text: TextSpan(
        text: text,
        style: TextStyle(
          color: const Color(0xFF000000),
          fontSize: fontSize,
          fontWeight: FontWeight.bold,
        ),
      ),
      textDirection: TextDirection.ltr,
    )..layout();
    painter.paint(canvas, centre - Offset(painter.width / 2, painter.height / 2));
  }

  /// A 1349 x 219 banner, the size of a typical receipt logo that is far wider
  /// than the paper, so the plugin has to scale it down.
  static Future<Uint8List> banner() => _png(1349, 219, (canvas, size) {
        final black = Paint()..color = const Color(0xFF000000);
        canvas.drawRect(Offset.zero & size, black);
        canvas.drawRect(
            const Rect.fromLTWH(14, 14, 1321, 191), Paint()..color = const Color(0xFFFFFFFF));
        _text(canvas, 'SAMPLE FUEL CO.', 120, size.center(Offset.zero));
      });

  /// A signature-pad image: a pen stroke on white, wider than the paper.
  static Future<Uint8List> signature() => _png(800, 300, (canvas, size) {
        final pen = Paint()
          ..color = const Color(0xFF000000)
          ..strokeWidth = 8
          ..style = PaintingStyle.stroke
          ..strokeCap = StrokeCap.round;
        final path = Path()..moveTo(60, 200);
        for (double x = 60; x <= 740; x += 10) {
          path.lineTo(x, 160 + 70 * math.sin(x / 45) * math.cos(x / 130));
        }
        canvas.drawPath(path, pen);
      });

  /// A ruler [width] dots wide: a tick every 8 dots, a longer one every 32, and a
  /// label every 64, to see how many dots the printer actually prints.
  static Future<Uint8List> ruler(int width) => _png(width, 90, (canvas, size) {
        final black = Paint()..color = const Color(0xFF000000);
        canvas.drawRect(Rect.fromLTWH(0, 0, size.width, 3), black);
        for (int x = 0; x < width; x += 8) {
          final double tick = x % 64 == 0 ? 40 : (x % 32 == 0 ? 25 : 12);
          canvas.drawRect(Rect.fromLTWH(x.toDouble(), 3, 2, tick), black);
          if (x % 64 == 0) {
            _text(canvas, '$x', 18, Offset(x + 16.0, 62));
          }
        }
        canvas.drawRect(Rect.fromLTWH(size.width - 2, 0, 2, 90), black);
      });

  /// The set-up every slip starts with: init, check the printer, feed, gray,
  /// banner, feed, then the 12 x 24 font with 10 dots between lines.
  static Future<void> _setup() async {
    await FlutterPaxPrinterUtility.init;
    final status = await FlutterPaxPrinterUtility.getStatus;
    if (status != PrinterStatus.SUCCESS) {
      throw StateError('Printer not ready: ${status.name}');
    }
    await FlutterPaxPrinterUtility.step(finalStep);
    await FlutterPaxPrinterUtility.setGray(grayLevel);
    await FlutterPaxPrinterUtility.printBitmap(await banner());
    await FlutterPaxPrinterUtility.step(standardStep);
    await FlutterPaxPrinterUtility.fontSet(
        EFontTypeAscii.FONT_12_24, EFontTypeExtCode.FONT_16_16);
    await FlutterPaxPrinterUtility.spaceSet(0, 10);
  }

  static Future<String> _finish(Stopwatch watch) async {
    await FlutterPaxPrinterUtility.step(finalStep);
    final status = await FlutterPaxPrinterUtility.start();
    final dots = await FlutterPaxPrinterUtility.getDotLine();
    return '$status, $dots dot lines, ${watch.elapsedMilliseconds} ms';
  }

  static Future<void> _signature() async {
    await FlutterPaxPrinterUtility.step(standardStep);
    await FlutterPaxPrinterUtility.printBitmap(await signature());
  }

  static void _testCardMessage(StringBuffer b) {
    b.writeln('');
    b.writeln(starLine);
    b.writeln('***     TEST TRANSACTION     ***');
    b.writeln('***       DO NOT FILE        ***');
    b.writeln(starLine);
    b.writeln('');
  }

  static String _afterSignature() {
    final b = StringBuffer();
    b.writeln('');
    b.writeln('');
    b.writeln(starLine);
    b.writeln('                ${_date(DateTime.now())}');
    b.writeln('');
    b.writeln('');
    b.writeln('Sample Depot Midrand');
    b.writeln(' - facilitated by Sample Fuel Co ');
    return b.toString();
  }

  /// Fuel slip: details, a signature, then the closing lines.
  static Future<String> fuelSlip({bool testCard = false}) async {
    final watch = Stopwatch()..start();
    await _setup();
    final b = StringBuffer();
    if (testCard) _testCardMessage(b);
    b.writeln('Transaction Ref: 7004512');
    b.writeln('Account: Sample Logistics (Pty) Ltd');
    b.writeln('Service: Fleet');
    b.writeln('Slip Number: 000123');
    b.writeln('Identifier: 7001 2345 6789 0123');
    b.writeln('Vehicle: ABC 123 GP');
    b.writeln('Driver: J Smith');
    b.writeln('Driver Code: 0042');
    b.writeln('Date: ${_date(DateTime.now())}');
    b.writeln('Depot: Sample Depot Midrand');
    b.writeln('Odometer: 152340');
    b.writeln('Payment: Account');
    b.writeln('');
    b.writeln('Product: Diesel 50ppm');
    b.writeln('Litres: 120.5');
    b.writeln('Amount: $amount');
    b.writeln('Signed:');
    await FlutterPaxPrinterUtility.printStr(b.toString(), null);
    await _signature();
    await FlutterPaxPrinterUtility.printStr(_afterSignature(), null);
    return _finish(watch);
  }

  /// Transaction receipt (shop items): a heading box, details and a signature.
  static Future<String> transactionReceipt() async {
    final watch = Stopwatch()..start();
    await _setup();
    final b = StringBuffer();
    b.writeln(starLine);
    b.writeln('***    TRANSACTION RECEIPT   ***');
    b.writeln(starLine);
    b.writeln('Ref. number: 7004513');
    b.writeln('Account: Sample Logistics (Pty) Ltd');
    b.writeln('Date: ${_date(DateTime.now())}');
    b.writeln('Depot: Sample Depot Midrand');
    b.writeln('Vehicle: ABC 123 GP');
    b.writeln('Driver: J Smith');
    b.writeln('Product: AdBlue 20L');
    b.writeln('Item cost: R389,99');
    b.writeln('Qty: 2');
    b.writeln('Total(incl.): R779,98');
    b.writeln('Payment: Account');
    b.writeln('');
    b.writeln('Signed:');
    await FlutterPaxPrinterUtility.printStr(b.toString(), null);
    await _signature();
    await FlutterPaxPrinterUtility.printStr(_afterSignature(), null);
    return _finish(watch);
  }

  /// Failed transaction slip: text only, with blank lines for a signature.
  static Future<String> failedTransaction() async {
    final watch = Stopwatch()..start();
    await _setup();
    final b = StringBuffer();
    b.writeln(starLine);
    b.writeln('***    Transaction Failed    ***');
    b.writeln(starLine);
    b.writeln('Auth #: 7004514');
    b.writeln('Account: Sample Logistics (Pty) Ltd');
    b.writeln('Identifier: 7001 2345 6789 0123');
    b.writeln('Vehicle: ABC 123 GP');
    b.writeln('Driver: J Smith');
    b.writeln('Product: Diesel 50ppm');
    b.writeln('Instructions ');
    b.writeln(equalsLine);
    b.writeln('Please contact the call centre');
    b.writeln('\n' * 8);
    b.writeln(dashLine);
    b.writeln('Signed: Attendant P Ndlovu');
    b.writeln('');
    b.writeln('                ${_date(DateTime.now())}');
    await FlutterPaxPrinterUtility.printStr(b.toString(), null);
    return _finish(watch);
  }

  /// Pump attendant slip: two-column nozzle line and two signature lines.
  static Future<String> attendantSlip() async {
    final watch = Stopwatch()..start();
    await _setup();
    final b = StringBuffer();
    b.writeln(starLine);
    b.writeln('***      ATTENDANT SLIP      ***');
    b.writeln(starLine);
    b.writeln('Auth #: 7004515');
    b.writeln('Account: Sample Logistics (Pty) Ltd');
    b.writeln('Vehicle: ABC 123 GP');
    b.writeln('Product: Diesel 50ppm');
    b.writeln('');
    b.writeln('');
    b.writeln('Max litres ');
    b.writeln(equalsLine);
    b.writeln('400 L');
    b.writeln('');
    b.writeln('');
    b.writeln('Payment instruction ');
    b.writeln(equalsLine);
    b.writeln('Account');
    b.writeln('');
    b.writeln('');
    b.writeln('-------------      -------------');
    b.writeln('Nozzle #           Pumped Litres');
    b.write('\n' * 4);
    b.writeln(dashLine);
    b.writeln('Signed: Attendant P Ndlovu');
    b.write('\n' * 4);
    b.writeln(dashLine);
    b.writeln('Signed: Driver');
    b.writeln('');
    b.writeln('Pump activation');
    b.writeln('code: 00451277');
    b.writeln('');
    b.writeln(starLine);
    b.writeln('                ${_date(DateTime.now())}');
    await FlutterPaxPrinterUtility.printStr(b.toString(), null);
    return _finish(watch);
  }

  /// Rulers on a page [pageWidth] dots wide, then 384, to find how many dots
  /// the printer prints across.
  static Future<String> rulers(int pageWidth) async {
    final watch = Stopwatch()..start();
    await FlutterPaxPrinterUtility.setPrintWidth(pageWidth);
    try {
      await FlutterPaxPrinterUtility.init;
      await FlutterPaxPrinterUtility.fontSet(
          EFontTypeAscii.FONT_8_16, EFontTypeExtCode.FONT_16_16);
      await FlutterPaxPrinterUtility.printStr('Ruler, $pageWidth-dot page:\n', null);
      await FlutterPaxPrinterUtility.printBitmap(await ruler(pageWidth));
      await FlutterPaxPrinterUtility.step(20);
      await FlutterPaxPrinterUtility.printStr(
          '${List.generate(pageWidth ~/ 8, (i) => '${i % 10}').join()}\n', null);
      await FlutterPaxPrinterUtility.step(finalStep);
      final status = await FlutterPaxPrinterUtility.start();
      return '$status, ${watch.elapsedMilliseconds} ms';
    } finally {
      await FlutterPaxPrinterUtility.setPrintWidth(0);
    }
  }

  /// One long slip of [lines] numbered lines, to check tall pages print in one
  /// piece and how long they take.
  static Future<String> longSlip(int lines) async {
    final watch = Stopwatch()..start();
    await _setup();
    final b = StringBuffer();
    for (int i = 1; i <= lines; i++) {
      b.writeln('Line ${i.toString().padLeft(3)} ${'.' * 19}${i.toString().padLeft(3)}');
    }
    await FlutterPaxPrinterUtility.printStr(b.toString(), null);
    return _finish(watch);
  }
}
