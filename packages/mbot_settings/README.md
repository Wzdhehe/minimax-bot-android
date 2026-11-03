# mbot_settings

Standalone Flutter package for the M Bot Android settings shell.

- **Integration**: self-authored settings UI, composed into the AGPL Android app. New provider files carry AGPL-3.0-only headers; no DSH implementation was copied into this package.
- **Scope**: hub IA, notification and background pages, and the provider metadata/editor UI. Native storage/platform implementations live in `dsh-go/app/android/.../bot`.
- **Platform**: Dart interfaces + fakes for tests; the app supplies its own native channels, without calling DSH runtime channels.
- **Honest copy**: disabling notifications only stops reminders. Killing the Bot/runtime process stops its in-process scheduled tasks.

Item definitions live in `lib/src/ia/settings_groups.dart` so architecture docs can align against one source.
