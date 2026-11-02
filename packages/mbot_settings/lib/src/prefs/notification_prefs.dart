import '../ia/settings_groups.dart';
import 'prefs_store.dart';

/// Persisted in-app notification switches (private store; not system channels).
class MbotNotificationPrefs {
  const MbotNotificationPrefs({
    this.enabled = true,
    this.scheduleDue = true,
    this.turnDone = true,
    this.failed = true,
    this.approval = true,
    this.question = true,
  });

  final bool enabled;
  final bool scheduleDue;
  final bool turnDone;
  final bool failed;
  final bool approval;
  final bool question;

  bool categoryEnabled(MbotNotifyCategory category) => switch (category) {
        MbotNotifyCategory.scheduleDue => scheduleDue,
        MbotNotifyCategory.turnDone => turnDone,
        MbotNotifyCategory.failed => failed,
        MbotNotifyCategory.approval => approval,
        MbotNotifyCategory.question => question,
      };

  /// Effective gate used by a future notifier: master off ⇒ nothing fires.
  bool allows(MbotNotifyCategory category) => enabled && categoryEnabled(category);

  MbotNotificationPrefs copyWith({
    bool? enabled,
    bool? scheduleDue,
    bool? turnDone,
    bool? failed,
    bool? approval,
    bool? question,
  }) =>
      MbotNotificationPrefs(
        enabled: enabled ?? this.enabled,
        scheduleDue: scheduleDue ?? this.scheduleDue,
        turnDone: turnDone ?? this.turnDone,
        failed: failed ?? this.failed,
        approval: approval ?? this.approval,
        question: question ?? this.question,
      );

  @override
  bool operator ==(Object other) =>
      other is MbotNotificationPrefs &&
      other.enabled == enabled &&
      other.scheduleDue == scheduleDue &&
      other.turnDone == turnDone &&
      other.failed == failed &&
      other.approval == approval &&
      other.question == question;

  @override
  int get hashCode => Object.hash(enabled, scheduleDue, turnDone, failed, approval, question);
}

/// Loads/saves [MbotNotificationPrefs] through a [PrefsStore].
class MbotNotificationPrefsController {
  MbotNotificationPrefsController(this._store, {MbotNotificationPrefs? initial})
      : _prefs = initial ?? const MbotNotificationPrefs();

  static const _keys = (
    enabled: 'mbot_notify_enabled',
    scheduleDue: 'mbot_notify_schedule_due',
    turnDone: 'mbot_notify_turn_done',
    failed: 'mbot_notify_failed',
    approval: 'mbot_notify_approval',
    question: 'mbot_notify_question',
  );

  final PrefsStore _store;
  MbotNotificationPrefs _prefs;
  final List<void Function(MbotNotificationPrefs)> _listeners = [];

  MbotNotificationPrefs get value => _prefs;

  void addListener(void Function(MbotNotificationPrefs) listener) => _listeners.add(listener);

  void removeListener(void Function(MbotNotificationPrefs) listener) => _listeners.remove(listener);

  void _emit() {
    for (final listener in List<void Function(MbotNotificationPrefs)>.from(_listeners)) {
      listener(_prefs);
    }
  }

  Future<void> load() async {
    _prefs = MbotNotificationPrefs(
      enabled: await _store.getBool(_keys.enabled) ?? true,
      scheduleDue: await _store.getBool(_keys.scheduleDue) ?? true,
      turnDone: await _store.getBool(_keys.turnDone) ?? true,
      failed: await _store.getBool(_keys.failed) ?? true,
      approval: await _store.getBool(_keys.approval) ?? true,
      question: await _store.getBool(_keys.question) ?? true,
    );
    _emit();
  }

  Future<void> setEnabled(bool value) => _save(_keys.enabled, value, (p) => p.copyWith(enabled: value));

  Future<void> setCategory(MbotNotifyCategory category, bool value) => switch (category) {
        MbotNotifyCategory.scheduleDue => _save(_keys.scheduleDue, value, (p) => p.copyWith(scheduleDue: value)),
        MbotNotifyCategory.turnDone => _save(_keys.turnDone, value, (p) => p.copyWith(turnDone: value)),
        MbotNotifyCategory.failed => _save(_keys.failed, value, (p) => p.copyWith(failed: value)),
        MbotNotifyCategory.approval => _save(_keys.approval, value, (p) => p.copyWith(approval: value)),
        MbotNotifyCategory.question => _save(_keys.question, value, (p) => p.copyWith(question: value)),
      };

  Future<void> _save(
    String key,
    bool value,
    MbotNotificationPrefs Function(MbotNotificationPrefs) apply,
  ) async {
    _prefs = apply(_prefs);
    _emit();
    await _store.setBool(key, value);
  }
}
