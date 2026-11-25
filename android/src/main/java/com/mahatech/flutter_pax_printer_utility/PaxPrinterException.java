package com.mahatech.flutter_pax_printer_utility;

/// A printer error reported by the PAX SDK, such as no paper.
///
/// errCode is the printer status code (2 = out of paper, 100 = not supported, ...),
/// the same numbers the earlier NeptuneLite-based versions reported, so apps keep
/// working unchanged. sdkCode is the POSLink ProcessResult code ("209904", ...) when
/// there is one.
final class PaxPrinterException extends Exception {
    final int errCode;
    final String sdkCode;

    PaxPrinterException(int errCode, String sdkCode, String message) {
        super(message);
        this.errCode = errCode;
        this.sdkCode = sdkCode;
    }
}
