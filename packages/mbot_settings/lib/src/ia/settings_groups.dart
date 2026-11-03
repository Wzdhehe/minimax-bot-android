/// Central product IA for the M Bot settings hub (report E.2).
///
/// Architecture docs (`docs/architecture.md`) should align titles and acceptance
/// against these constants. Placeholders are listed so the hub can render every
/// group before each page is implemented.
library;

/// One top-level settings group on the hub.
enum MbotSettingsGroupId {
  accountAndModel,
  plugins,
  notifications,
  schedules,
  backgroundRunning,
  workspaceAndStorage,
  appearance,
  about,
}

/// One row inside a group (hub entry or page control).
enum MbotSettingsItemId {
  // accountAndModel
  providers,
  usageQuota,
  apiKey,
  baseUrl,
  defaultModel,
  retryTimeoutHelp,

  // plugins
  pluginMarketplace,
  pluginInstalled,
  pluginInstallUninstall,
  pluginEnableDisable,
  pluginPermissions,

  // notifications
  notifyMaster,
  notifyScheduleDue,
  notifyTurnDone,
  notifyFailed,
  notifyApproval,
  notifyQuestion,
  notifyOpenSystemChannel,

  // schedules
  scheduleList,
  scheduleCreate,
  scheduleEdit,
  scheduleDelete,

  // backgroundRunning
  backgroundNotificationPermission,
  backgroundBatteryOptimization,
  backgroundVendorGuide,
  backgroundHonestDisclaimer,

  // workspaceAndStorage
  workspaceUsage,
  workspaceExportClear,
  workspaceBackupHelp,

  // appearance
  themeMode,
  fontSize,

  // about
  appVersion,
  licenses,
  diagnostics,
}

/// Whether a hub row opens a real page or is still a placeholder.
enum MbotSettingsEntryKind {
  /// Implemented in this package.
  implemented,

  /// Listed for IA parity; tapping shows a short "即将实现" note.
  placeholder,
}

/// Static description of a hub group and its entry rows.
class MbotSettingsGroup {
  const MbotSettingsGroup({
    required this.id,
    required this.title,
    required this.subtitle,
    required this.kind,
    required this.items,
  });

  final MbotSettingsGroupId id;
  final String title;
  final String subtitle;
  final MbotSettingsEntryKind kind;
  final List<MbotSettingsItemId> items;
}

/// Android notification channel ids owned by M Bot (self-defined; not from DSH).
///
/// Native creation is deferred. Flutter still names them so settings can deep-link
/// once a self-authored plugin registers the channels.
abstract final class MbotNotificationChannels {
  static const scheduleDue = 'mbot_schedule_due';
  static const turnDone = 'mbot_turn_done';
  static const failed = 'mbot_failed';
  static const approval = 'mbot_approval';
  static const question = 'mbot_question';

  static const all = <String>[
    scheduleDue,
    turnDone,
    failed,
    approval,
    question,
  ];

  static String forCategory(MbotNotifyCategory category) => switch (category) {
        MbotNotifyCategory.scheduleDue => scheduleDue,
        MbotNotifyCategory.turnDone => turnDone,
        MbotNotifyCategory.failed => failed,
        MbotNotifyCategory.approval => approval,
        MbotNotifyCategory.question => question,
      };
}

/// In-app notification categories (report E.2 「通知」).
enum MbotNotifyCategory {
  /// 定时任务到点
  scheduleDue,

  /// Bot 新消息 / 一轮完成
  turnDone,

  /// 出错或被打断
  failed,

  /// 等待确认
  approval,

  /// 向你提问
  question,
}

extension MbotNotifyCategoryLabel on MbotNotifyCategory {
  String get title => switch (this) {
        MbotNotifyCategory.scheduleDue => '定时任务到点',
        MbotNotifyCategory.turnDone => 'Bot 新消息 / 一轮完成',
        MbotNotifyCategory.failed => '出错或被打断',
        MbotNotifyCategory.approval => '等待确认',
        MbotNotifyCategory.question => '向你提问',
      };

  String get subtitle => switch (this) {
        MbotNotifyCategory.scheduleDue => '到点执行后弹出通知（进程仍存活时）',
        MbotNotifyCategory.turnDone => 'Bot 回复完成或有新消息时通知',
        MbotNotifyCategory.failed => '回合失败或被中断时通知',
        MbotNotifyCategory.approval => '需要你确认某一步时通知',
        MbotNotifyCategory.question => 'Bot 向你提问等待回答时通知',
      };

  MbotSettingsItemId get itemId => switch (this) {
        MbotNotifyCategory.scheduleDue => MbotSettingsItemId.notifyScheduleDue,
        MbotNotifyCategory.turnDone => MbotSettingsItemId.notifyTurnDone,
        MbotNotifyCategory.failed => MbotSettingsItemId.notifyFailed,
        MbotNotifyCategory.approval => MbotSettingsItemId.notifyApproval,
        MbotNotifyCategory.question => MbotSettingsItemId.notifyQuestion,
      };
}

/// Canonical hub groups for M Bot settings. Keep titles stable for architecture alignment.
abstract final class MbotSettingsIa {
  static const groups = <MbotSettingsGroup>[
    MbotSettingsGroup(
      id: MbotSettingsGroupId.accountAndModel,
      title: '账号与模型',
      subtitle: '供应商、API 协议、默认模型',
      kind: MbotSettingsEntryKind.placeholder,
      items: [
        MbotSettingsItemId.providers,
        MbotSettingsItemId.usageQuota,
        MbotSettingsItemId.apiKey,
        MbotSettingsItemId.baseUrl,
        MbotSettingsItemId.defaultModel,
        MbotSettingsItemId.retryTimeoutHelp,
      ],
    ),
    MbotSettingsGroup(
      id: MbotSettingsGroupId.plugins,
      title: '插件',
      subtitle: '市场、安装与启停',
      kind: MbotSettingsEntryKind.placeholder,
      items: [
        MbotSettingsItemId.pluginMarketplace,
        MbotSettingsItemId.pluginInstalled,
        MbotSettingsItemId.pluginInstallUninstall,
        MbotSettingsItemId.pluginEnableDisable,
        MbotSettingsItemId.pluginPermissions,
      ],
    ),
    MbotSettingsGroup(
      id: MbotSettingsGroupId.notifications,
      title: '通知',
      subtitle: '总开关、分类与系统渠道',
      kind: MbotSettingsEntryKind.implemented,
      items: [
        MbotSettingsItemId.notifyMaster,
        MbotSettingsItemId.notifyScheduleDue,
        MbotSettingsItemId.notifyTurnDone,
        MbotSettingsItemId.notifyFailed,
        MbotSettingsItemId.notifyApproval,
        MbotSettingsItemId.notifyQuestion,
        MbotSettingsItemId.notifyOpenSystemChannel,
      ],
    ),
    MbotSettingsGroup(
      id: MbotSettingsGroupId.backgroundRunning,
      title: '后台运行',
      subtitle: '只检测状态并跳转系统设置',
      kind: MbotSettingsEntryKind.implemented,
      items: [
        MbotSettingsItemId.backgroundNotificationPermission,
        MbotSettingsItemId.backgroundBatteryOptimization,
        MbotSettingsItemId.backgroundVendorGuide,
        MbotSettingsItemId.backgroundHonestDisclaimer,
      ],
    ),
    MbotSettingsGroup(
      id: MbotSettingsGroupId.workspaceAndStorage,
      title: '工作区与存储',
      subtitle: '占用、导出与清空',
      kind: MbotSettingsEntryKind.placeholder,
      items: [
        MbotSettingsItemId.workspaceUsage,
        MbotSettingsItemId.workspaceExportClear,
        MbotSettingsItemId.workspaceBackupHelp,
      ],
    ),
    MbotSettingsGroup(
      id: MbotSettingsGroupId.appearance,
      title: '外观',
      subtitle: '主题与字号',
      kind: MbotSettingsEntryKind.placeholder,
      items: [
        MbotSettingsItemId.themeMode,
        MbotSettingsItemId.fontSize,
      ],
    ),
    MbotSettingsGroup(
      id: MbotSettingsGroupId.about,
      title: '关于',
      subtitle: '版本、许可与诊断',
      kind: MbotSettingsEntryKind.placeholder,
      items: [
        MbotSettingsItemId.appVersion,
        MbotSettingsItemId.licenses,
        MbotSettingsItemId.diagnostics,
      ],
    ),
  ];

  static MbotSettingsGroup byId(MbotSettingsGroupId id) =>
      groups.firstWhere((g) => g.id == id);
}
