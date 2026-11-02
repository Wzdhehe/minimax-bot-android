import 'package:shared_preferences/shared_preferences.dart';

/// Tiny bool/string store used by notification prefs.
abstract class PrefsStore {
  Future<bool?> getBool(String key);
  Future<void> setBool(String key, bool value);
}

/// In-memory store for tests and hosts that inject their own persistence.
class MemoryPrefsStore implements PrefsStore {
  MemoryPrefsStore([Map<String, bool>? seed]) : _bools = Map<String, bool>.from(seed ?? const {});

  final Map<String, bool> _bools;

  Map<String, bool> get snapshot => Map<String, bool>.unmodifiable(_bools);

  @override
  Future<bool?> getBool(String key) async => _bools[key];

  @override
  Future<void> setBool(String key, bool value) async {
    _bools[key] = value;
  }
}

/// App-private SharedPreferences backend (Android/iOS/desktop plugins).
class SharedPreferencesPrefsStore implements PrefsStore {
  SharedPreferencesPrefsStore(this._prefs);

  final SharedPreferences _prefs;

  static Future<SharedPreferencesPrefsStore> create() async =>
      SharedPreferencesPrefsStore(await SharedPreferences.getInstance());

  @override
  Future<bool?> getBool(String key) async => _prefs.getBool(key);

  @override
  Future<void> setBool(String key, bool value) async {
    await _prefs.setBool(key, value);
  }
}
