import 'package:expense_notification_listener/home_screen.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  const channel = MethodChannel('id.expenselistener/native/v1');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      switch (call.method) {
        case 'getStatus':
          return {
            'configured': false,
            'packages': <String>[],
            'rawConsent': false,
            'forwarding': false,
            'listenerEnabled': false,
          };
        case 'getSourceApps':
          return [
            {'package': 'com.example.bank', 'label': 'Example Bank'},
          ];
        default:
          return null;
      }
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows setup, consent, and source selection', (tester) async {
    await tester.pumpWidget(
      const ProviderScope(
        child: MaterialApp(home: HomeScreen()),
      ),
    );
    await tester.pumpAndSettle();

    expect(find.text('Pencatat Pengeluaran'), findsOneWidget);
    expect(find.text('Kirim teks notifikasi lengkap'), findsOneWidget);
    expect(find.text('Example Bank'), findsOneWidget);
    expect(find.text('Berikan akses'), findsOneWidget);
  });
}
