import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'native_bridge.dart';

final nativeBridgeProvider = Provider<NativeBridge>((ref) => NativeBridge());

class HomeScreen extends ConsumerStatefulWidget {
  const HomeScreen({super.key});

  @override
  ConsumerState<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends ConsumerState<HomeScreen>
    with WidgetsBindingObserver {
  final _urlController = TextEditingController();
  final _secretController = TextEditingController();
  Map<String, dynamic> _status = {};
  List<Map<String, dynamic>> _apps = [];
  Set<String> _selectedPackages = {};
  bool _rawConsent = false;
  bool _loading = true;
  bool _busy = false;
  String? _message;
  bool _messageIsError = false;

  NativeBridge get _bridge => ref.read(nativeBridgeProvider);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _load();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _load();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _urlController.dispose();
    _secretController.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    try {
      final results = await Future.wait<Object>([
        _bridge.getStatus(),
        _bridge.getSourceApps(),
      ]);
      if (!mounted) return;
      final status = results[0] as Map<String, dynamic>;
      setState(() {
        _status = status;
        _apps = results[1] as List<Map<String, dynamic>>;
        _selectedPackages =
            (status['packages'] as List<dynamic>? ?? []).cast<String>().toSet();
        _rawConsent = status['rawConsent'] == true;
        _urlController.text = status['url'] as String? ?? '';
        _secretController.clear();
        _loading = false;
      });
    } on PlatformException catch (error) {
      _show(error.message ?? 'Pengaturan aplikasi tidak dapat dimuat.',
          error: true);
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _save() async {
    final uri = Uri.tryParse(_urlController.text.trim());
    if (uri == null ||
        uri.scheme != 'https' ||
        uri.host != 'script.google.com' ||
        !uri.path.startsWith('/macros/s/') ||
        !uri.path.endsWith('/exec')) {
      _show('Masukkan URL deployment Google Apps Script HTTPS yang valid.',
          error: true);
      return;
    }
    if (_secretController.text.trim().isEmpty &&
        _status['configured'] != true) {
      _show('Masukkan kunci rahasia yang dikonfigurasi di Apps Script.',
          error: true);
      return;
    }
    setState(() => _busy = true);
    try {
      await _bridge.saveConfiguration(
        url: _urlController.text.trim(),
        secret: _secretController.text,
        packages: _selectedPackages.toList(),
        rawConsent: _rawConsent,
      );
      _secretController.clear();
      await _load();
      _show('Konfigurasi disimpan dengan aman di perangkat ini.');
    } on PlatformException catch (error) {
      _show(error.message ?? 'Konfigurasi tidak dapat disimpan.', error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _testConnection() async {
    if (_status['configured'] != true ||
        _urlController.text.trim() != (_status['url'] as String? ?? '') ||
        _secretController.text.isNotEmpty) {
      _show('Simpan perubahan konfigurasi terlebih dahulu.');
      return;
    }
    setState(() => _busy = true);
    try {
      final result = await _bridge.testConnection();
      await _load();
      _show(result['message'] as String? ?? 'Koneksi berhasil.');
    } on PlatformException catch (error) {
      _show(error.message ?? 'Tes koneksi gagal.', error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _toggleForwarding(bool enabled) async {
    try {
      await _bridge.setForwarding(enabled);
      await _load();
    } on PlatformException catch (error) {
      _show(error.message ?? 'Status penerusan tidak dapat diubah.',
          error: true);
    }
  }

  void _show(String text, {bool error = false}) {
    if (!mounted) return;
    setState(() {
      _message = text;
      _messageIsError = error;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    final permission = _status['listenerEnabled'] == true;
    final configured = _status['configured'] == true;
    final forwarding = _status['forwarding'] == true;
    return Scaffold(
      appBar: AppBar(title: const Text('Pencatat Pengeluaran')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text(
            'Notifikasi transaksi dari aplikasi yang Anda pilih akan dikirim '
            'ke Google Apps Script dan ditambahkan ke Google Sheets Anda. '
            'Aplikasi ini tidak menyimpan riwayat transaksi.',
          ),
          if (_message != null) ...[
            const SizedBox(height: 12),
            Text(
              _message!,
              style: TextStyle(
                color: _messageIsError
                    ? Theme.of(context).colorScheme.error
                    : Theme.of(context).colorScheme.primary,
              ),
            ),
          ],
          const SizedBox(height: 16),
          TextField(
            controller: _urlController,
            keyboardType: TextInputType.url,
            decoration: const InputDecoration(
              labelText: 'URL deployment Apps Script',
              hintText: 'https://script.google.com/macros/s/.../exec',
              border: OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _secretController,
            obscureText: true,
            decoration: InputDecoration(
              labelText: configured
                  ? 'Kunci rahasia (kosongkan untuk tetap memakai yang tersimpan)'
                  : 'Kunci rahasia',
              helperText: 'Gunakan 32–256 karakter.',
              border: const OutlineInputBorder(),
            ),
          ),
          const SizedBox(height: 8),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Kirim teks notifikasi lengkap'),
            subtitle: const Text(
              'Teks dapat berisi saldo atau sebagian detail rekening. '
              'Teks akan disimpan di kolom Raw spreadsheet.',
            ),
            value: _rawConsent,
            onChanged: (value) => setState(() => _rawConsent = value),
          ),
          const SizedBox(height: 8),
          FilledButton(
            onPressed: _busy ? null : _save,
            child: const Text('Simpan konfigurasi'),
          ),
          OutlinedButton(
            onPressed: _busy ? null : _testConnection,
            child: const Text('Tes koneksi Apps Script'),
          ),
          const Divider(height: 32),
          ListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Notification access'),
            subtitle: Text(permission ? 'Aktif' : 'Belum aktif'),
            trailing: OutlinedButton(
              onPressed: _bridge.openNotificationSettings,
              child: Text(permission ? 'Pengaturan' : 'Berikan akses'),
            ),
          ),
          Text('Pilih sumber notifikasi (Android 11 atau lebih baru):',
              style: Theme.of(context).textTheme.titleMedium),
          if (_apps.isEmpty)
            const Padding(
              padding: EdgeInsets.symmetric(vertical: 12),
              child: Text('Tidak ditemukan aplikasi yang dapat dibuka.'),
            ),
          ..._apps.map((app) {
            final packageName = app['package'] as String? ?? '';
            return CheckboxListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(app['label'] as String? ?? packageName),
              subtitle: Text(packageName),
              value: _selectedPackages.contains(packageName),
              onChanged: (selected) => setState(() {
                if (selected == true) {
                  _selectedPackages.add(packageName);
                } else {
                  _selectedPackages.remove(packageName);
                }
              }),
            );
          }),
          const SizedBox(height: 8),
          OutlinedButton(
            onPressed: _busy ? null : _save,
            child: const Text('Simpan pilihan sumber'),
          ),
          const Divider(height: 32),
          SwitchListTile(
            contentPadding: EdgeInsets.zero,
            title: const Text('Penerusan otomatis'),
            subtitle: Text(
              forwarding ? 'Penerusan aktif' : 'Penerusan dijeda',
            ),
            value: forwarding,
            onChanged: configured && permission && _selectedPackages.isNotEmpty
                ? _toggleForwarding
                : null,
          ),
          Text('Pengiriman terakhir berhasil: '
              '${_status['lastSuccess'] ?? 'Belum pernah'}'),
          if (_status['lastSkipped'] is String)
            Text('Notifikasi pengeluaran tak dikenal terakhir dilewati: '
                '${_status['lastSkipped']}'),
          if (_status['lastError'] is String &&
              (_status['lastError'] as String).isNotEmpty)
            Text(
              'Kesalahan terakhir: ${_status['lastError']}',
              style: TextStyle(color: Theme.of(context).colorScheme.error),
            ),
          const SizedBox(height: 12),
          TextButton(
            onPressed: _busy
                ? null
                : () async {
                    final confirmed = await showDialog<bool>(
                      context: context,
                      builder: (context) => AlertDialog(
                        title: const Text('Hapus konfigurasi?'),
                        content: const Text(
                          'URL, kunci rahasia, pilihan sumber, dan pengiriman '
                          'notifikasi yang masih tertunda akan dihapus.',
                        ),
                        actions: [
                          TextButton(
                            onPressed: () => Navigator.pop(context, false),
                            child: const Text('Batal'),
                          ),
                          FilledButton(
                            onPressed: () => Navigator.pop(context, true),
                            child: const Text('Hapus'),
                          ),
                        ],
                      ),
                    );
                    if (confirmed != true) return;
                    try {
                      await _bridge.clearConfiguration();
                      _urlController.clear();
                      _secretController.clear();
                      await _load();
                    } on PlatformException catch (error) {
                      _show(error.message ?? 'Konfigurasi tidak dapat dihapus.',
                          error: true);
                    }
                  },
            child: const Text('Hapus konfigurasi dan pengiriman tertunda'),
          ),
        ],
      ),
    );
  }
}
