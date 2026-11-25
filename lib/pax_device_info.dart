import 'dart:async';

import 'package:flutter/services.dart';

/// Information about the PAX terminal the app is running on.
class PaxDeviceInfo {
  static const MethodChannel _channel = MethodChannel('pax_device_info');

  /// Returns the terminal's serial number, as reported by the PAX SDK
  /// (`DeviceModel.getSN`).
  ///
  /// Throws a [PlatformException] with code `PAX_ERROR` if the SDK reports no
  /// serial number.
  static Future<String?> getSerial() async {
    return await _channel.invokeMethod<String>('getSerialNumber');
  }

  /// Returns what is known about the terminal:
  ///
  /// - `MODEL`: the model name (for example `A910S`)
  /// - `SN`: the serial number from the PAX SDK
  /// - `FIRMWARE`: the Android build ID, which on PAX terminals is the PayDroid
  ///   firmware version (for example
  ///   `PayDroid_10.0.0_Sagittarius_V11.1.39_20250909` on an A910S)
  ///
  /// Keys without a value are left out. The PAX POSLink Peripheries SDK does
  /// not report separate processor, BIOS or printer firmware versions.
  static Future<Map<String, String>> getTermInfo() async {
    final Map<Object?, Object?>? info =
        await _channel.invokeMethod<Map<Object?, Object?>>('getTermInfo');
    return {
      for (final entry in (info ?? const {}).entries)
        '${entry.key}': '${entry.value}'
    };
  }
}
