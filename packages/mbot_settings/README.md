# mbot_settings

Standalone Flutter package for the M Bot Android settings shell.

- **License intent**: original code for this fork; does **not** link or copy DSH Go (AGPL).
- **Scope (E.2)**: settings hub lists all product groups; this package implements **通知** and **后台运行**.
- **Platform**: Dart interfaces + fakes for tests. Native Android implementations are deferred and must be self-authored inside this package (or a sibling package), never taken from DSH.
- **Honest copy**: shutting off notifications or killing the process means scheduled tasks will **not** run. The UI must not claim otherwise.

Item definitions live in `lib/src/ia/settings_groups.dart` so architecture docs can align against one source.
