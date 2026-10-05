import 'package:flutter/services.dart';

class NativeBridge {
  static const _channel =
      MethodChannel('id.expenselistener/native/v1');

  Future<Map<String, dynamic>> getStatus() async =>
      _map(await _channel.invokeMethod<Object?>('getStatus'));

  Future<List<Map<String, dynamic>>> getSourceApps() async {
    final result =
        await _channel.invokeListMethod<Object?>('getSourceApps') ?? [];
    return result.map((item) => _map(item)).toList(growable: false);
  }

  Future<void> saveConfiguration({
    required String url,
    required String secret,
    required List<String> packages,
    required bool rawConsent,
  }) =>
      _channel.invokeMethod<void>('saveConfiguration', {
        'url': url,
        'secret': secret,
        'packages': packages,
        'rawConsent': rawConsent,
      });

  Future<Map<String, dynamic>> testConnection() async =>
      _map(await _channel.invokeMethod<Object?>('testConnection'));

  Future<void> setForwarding(bool enabled) =>
      _channel.invokeMethod<void>('setForwarding', {'enabled': enabled});

  Future<void> openNotificationSettings() =>
      _channel.invokeMethod<void>('openNotificationSettings');

  Future<void> clearConfiguration() =>
      _channel.invokeMethod<void>('clearConfiguration');

  static Map<String, dynamic> _map(Object? value) {
    if (value is Map) {
      return value.map((key, value) => MapEntry(key.toString(), value));
    }
    return <String, dynamic>{};
  }
}
