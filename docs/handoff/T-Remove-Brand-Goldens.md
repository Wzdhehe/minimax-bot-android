<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# Asset-removal golden inventory

Flutter 3.47.9 / Dart 3.13.7, `DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts` (Noto CJK Light/Regular/Bold).

201 PNGs changed, from 199 exact golden test cases in 37 suites. This is **not** a global `--update-goldens` run.
[Exact test selection](T-Remove-Brand-Golden-Selection.json) records the selected full test names. The Star suite was also run in full (40 passing tests); one disabled-pet index test was verified separately.

Selection: 191 branch-only pixel failures plus 6 shared baseline failures whose baseline-vs-branch **actual** PNG captures differed, plus the 2 Star dialog cases. The six shared snapshots are `drawer_g9_folder_editor_{dark,light}`, `settings_hub_full_{dark,light}`, and `settings_appearance_large_text_{dark,light}`. Fifteen unrelated shared failing snapshots had identical actual captures and were left untouched. Some selected cases emit multiple images.

A complete-suite rerun caught a 160-pixel phase difference in `wide_panel_dark`: the isolated golden began with a cold procedural-dot atlas, while earlier geometry tests had already warmed it in a full run. A cold-only test passed and the same test after one geometry test failed. The adaptive suite now preloads `WhaleSprites.load()` in `setUpAll`, following the other chat golden suites; only `wide_panel_dark.png` was regenerated again. The full 19-test adaptive suite then passed. This does not increase the 201 unique PNG count.

Spot-checked rendered samples: brand_mark_light (geometric M), app_icon_picker_light (all four M variants), activity_strip_states_light (dots), pet_composer_deepy_medium_light (solid test rectangle), star_prompt_light (neutral Phosphor star, MiniMax Bot copy), drawer_g9_folder_editor_light (M). No old brand glyph, whale or mascot artwork in those samples. Legacy `deepy`/`girl` golden filenames now describe numeric rectangle fixtures, not redistributed art.

Reproduce only the selected cases, from `dsh-go/app` (sequential; no APK/Gradle build):

```sh
export DSH_TEST_FONT_DIR=/workspace/toolchains/test-fonts
python3 - <<'PYTHON'
import json, re, subprocess
selection = json.load(open('../../../docs/handoff/T-Remove-Brand-Golden-Selection.json'))
for path, names in selection.items():
    pattern = '^(?:' + '|'.join(re.escape(name) for name in names) + ')$'
    subprocess.run(['/workspace/toolchains/flutter-3.47.9/bin/flutter', 'test',
                    '--no-pub', '--concurrency=2', '--update-goldens', path,
                    '--name', pattern], check=True)
PYTHON
```

## Counts by directory

| Directory | PNGs |
|---|---:|
| `dsh-go/app/test/chat/dock/goldens` | 8 |
| `dsh-go/app/test/chat/goldens` | 34 |
| `dsh-go/app/test/community/goldens` | 2 |
| `dsh-go/app/test/drawer/goldens` | 13 |
| `dsh-go/app/test/integration/goldens` | 4 |
| `dsh-go/app/test/landscape/goldens` | 6 |
| `dsh-go/app/test/launch/goldens` | 12 |
| `dsh-go/app/test/onboarding/goldens` | 6 |
| `dsh-go/app/test/pets/goldens` | 23 |
| `dsh-go/app/test/plugins/seats/goldens` | 2 |
| `dsh-go/app/test/remote/goldens` | 4 |
| `dsh-go/app/test/settings/goldens` | 19 |
| `dsh-go/app/test/share/goldens` | 16 |
| `dsh-go/app/test/shell/adaptive/goldens` | 4 |
| `dsh-go/app/test/shell/goldens` | 22 |
| `dsh-go/app/test/star/goldens` | 2 |
| `dsh-go/app/test/system_entry/goldens` | 2 |
| `dsh-go/app/test/update/goldens` | 8 |
| `dsh-go/app/test/usage/goldens` | 1 |
| `dsh-go/app/test/wallpaper/goldens` | 8 |
| `dsh-go/app/test/web/goldens` | 1 |
| `dsh-go/app/test/workspace/goldens` | 4 |

## Complete updated paths (201)

```text
dsh-go/app/test/chat/dock/goldens/dock_deck_folded_dark.png
dsh-go/app/test/chat/dock/goldens/dock_deck_folded_light.png
dsh-go/app/test/chat/dock/goldens/dock_deck_spread_dark.png
dsh-go/app/test/chat/dock/goldens/dock_deck_spread_light.png
dsh-go/app/test/chat/dock/goldens/dock_open_dark.png
dsh-go/app/test/chat/dock/goldens/dock_open_light.png
dsh-go/app/test/chat/dock/goldens/dock_stack_dark.png
dsh-go/app/test/chat/dock/goldens/dock_stack_light.png
dsh-go/app/test/chat/goldens/activity_strip_levels_dark.png
dsh-go/app/test/chat/goldens/activity_strip_levels_light.png
dsh-go/app/test/chat/goldens/activity_strip_states_dark.png
dsh-go/app/test/chat/goldens/activity_strip_states_light.png
dsh-go/app/test/chat/goldens/auto_approve_dark.png
dsh-go/app/test/chat/goldens/auto_approve_light.png
dsh-go/app/test/chat/goldens/conversation_dark.png
dsh-go/app/test/chat/goldens/conversation_full_dark.png
dsh-go/app/test/chat/goldens/conversation_full_light.png
dsh-go/app/test/chat/goldens/conversation_light.png
dsh-go/app/test/chat/goldens/empty_dark.png
dsh-go/app/test/chat/goldens/empty_light.png
dsh-go/app/test/chat/goldens/empty_tiers_dark.png
dsh-go/app/test/chat/goldens/empty_tiers_failed_dark.png
dsh-go/app/test/chat/goldens/empty_tiers_failed_light.png
dsh-go/app/test/chat/goldens/empty_tiers_light.png
dsh-go/app/test/chat/goldens/empty_tiers_narrow_dark.png
dsh-go/app/test/chat/goldens/empty_tiers_narrow_light.png
dsh-go/app/test/chat/goldens/en_conversation_light.png
dsh-go/app/test/chat/goldens/en_empty_light.png
dsh-go/app/test/chat/goldens/model_empty_dark.png
dsh-go/app/test/chat/goldens/model_empty_light.png
dsh-go/app/test/chat/goldens/model_sheet_crowded_dark.png
dsh-go/app/test/chat/goldens/model_sheet_crowded_light.png
dsh-go/app/test/chat/goldens/model_sheet_dark.png
dsh-go/app/test/chat/goldens/model_sheet_light.png
dsh-go/app/test/chat/goldens/run_running_dark.png
dsh-go/app/test/chat/goldens/run_running_light.png
dsh-go/app/test/chat/goldens/run_trajectory_live_dark.png
dsh-go/app/test/chat/goldens/run_trajectory_live_light.png
dsh-go/app/test/chat/goldens/send_options_dark.png
dsh-go/app/test/chat/goldens/send_options_light.png
dsh-go/app/test/chat/goldens/streaming_dark.png
dsh-go/app/test/chat/goldens/streaming_light.png
dsh-go/app/test/community/goldens/community_group_dark.png
dsh-go/app/test/community/goldens/community_group_light.png
dsh-go/app/test/drawer/goldens/drawer_archive_undo_bulk_dark.png
dsh-go/app/test/drawer/goldens/drawer_archive_undo_bulk_light.png
dsh-go/app/test/drawer/goldens/drawer_archive_undo_dark.png
dsh-go/app/test/drawer/goldens/drawer_archive_undo_light.png
dsh-go/app/test/drawer/goldens/drawer_g9_empty_dark.png
dsh-go/app/test/drawer/goldens/drawer_g9_empty_light.png
dsh-go/app/test/drawer/goldens/drawer_g9_filter_dark.png
dsh-go/app/test/drawer/goldens/drawer_g9_filter_light.png
dsh-go/app/test/drawer/goldens/drawer_g9_folder_editor_dark.png
dsh-go/app/test/drawer/goldens/drawer_g9_folder_editor_light.png
dsh-go/app/test/drawer/goldens/drawer_g9_folders_dark.png
dsh-go/app/test/drawer/goldens/drawer_g9_folders_light.png
dsh-go/app/test/drawer/goldens/en_drawer_g9_folders_light.png
dsh-go/app/test/integration/goldens/chat_empty_workspace_dark.png
dsh-go/app/test/integration/goldens/chat_empty_workspace_light.png
dsh-go/app/test/integration/goldens/drawer_ds_bot_dark.png
dsh-go/app/test/integration/goldens/drawer_ds_bot_light.png
dsh-go/app/test/landscape/goldens/phone_chat_dark.png
dsh-go/app/test/landscape/goldens/phone_chat_light.png
dsh-go/app/test/landscape/goldens/phone_drawer_light.png
dsh-go/app/test/landscape/goldens/w600_chat_light.png
dsh-go/app/test/landscape/goldens/w840_chat_dark.png
dsh-go/app/test/landscape/goldens/w840_chat_light.png
dsh-go/app/test/launch/goldens/launch_handover_dark.png
dsh-go/app/test/launch/goldens/launch_handover_light.png
dsh-go/app/test/launch/goldens/launch_icon_frost_handover.png
dsh-go/app/test/launch/goldens/launch_icon_ink_handover.png
dsh-go/app/test/launch/goldens/launch_icon_mist_handover.png
dsh-go/app/test/launch/goldens/launch_icon_night_handover.png
dsh-go/app/test/launch/goldens/launch_noicon_in_dark.png
dsh-go/app/test/launch/goldens/launch_noicon_in_light.png
dsh-go/app/test/launch/goldens/launch_rest_dark.png
dsh-go/app/test/launch/goldens/launch_rest_light.png
dsh-go/app/test/launch/goldens/launch_sweep_dark.png
dsh-go/app/test/launch/goldens/launch_sweep_light.png
dsh-go/app/test/onboarding/goldens/chat_missing_dark.png
dsh-go/app/test/onboarding/goldens/chat_missing_light.png
dsh-go/app/test/onboarding/goldens/chat_stopped_light.png
dsh-go/app/test/onboarding/goldens/drawer_missing_dark.png
dsh-go/app/test/onboarding/goldens/drawer_missing_light.png
dsh-go/app/test/onboarding/goldens/en_chat_missing_light.png
dsh-go/app/test/pets/goldens/en_pet_settings_light.png
dsh-go/app/test/pets/goldens/pet_bubble_dark.png
dsh-go/app/test/pets/goldens/pet_bubble_light.png
dsh-go/app/test/pets/goldens/pet_composer_deepy_large_dark.png
dsh-go/app/test/pets/goldens/pet_composer_deepy_large_light.png
dsh-go/app/test/pets/goldens/pet_composer_deepy_medium_dark.png
dsh-go/app/test/pets/goldens/pet_composer_deepy_medium_light.png
dsh-go/app/test/pets/goldens/pet_composer_deepy_small_dark.png
dsh-go/app/test/pets/goldens/pet_composer_deepy_small_light.png
dsh-go/app/test/pets/goldens/pet_composer_girl_large_dark.png
dsh-go/app/test/pets/goldens/pet_composer_girl_large_light.png
dsh-go/app/test/pets/goldens/pet_composer_girl_medium_dark.png
dsh-go/app/test/pets/goldens/pet_composer_girl_medium_light.png
dsh-go/app/test/pets/goldens/pet_composer_girl_small_dark.png
dsh-go/app/test/pets/goldens/pet_composer_girl_small_light.png
dsh-go/app/test/pets/goldens/pet_held_deepy_dark.png
dsh-go/app/test/pets/goldens/pet_held_deepy_light.png
dsh-go/app/test/pets/goldens/pet_held_girl_dark.png
dsh-go/app/test/pets/goldens/pet_held_girl_light.png
dsh-go/app/test/pets/goldens/pet_settings_dark.png
dsh-go/app/test/pets/goldens/pet_settings_light.png
dsh-go/app/test/pets/goldens/pet_strip_dark.png
dsh-go/app/test/pets/goldens/pet_strip_light.png
dsh-go/app/test/plugins/seats/goldens/seat_settings_dark.png
dsh-go/app/test/plugins/seats/goldens/seat_settings_light.png
dsh-go/app/test/remote/goldens/drawer_ssh_dark.png
dsh-go/app/test/remote/goldens/drawer_ssh_light.png
dsh-go/app/test/remote/goldens/drawer_ssh_reconnecting_dark.png
dsh-go/app/test/remote/goldens/drawer_ssh_reconnecting_light.png
dsh-go/app/test/settings/goldens/about_dark.png
dsh-go/app/test/settings/goldens/about_light.png
dsh-go/app/test/settings/goldens/app_icon_picker_dark.png
dsh-go/app/test/settings/goldens/app_icon_picker_light.png
dsh-go/app/test/settings/goldens/appearance_custom_dark.png
dsh-go/app/test/settings/goldens/appearance_custom_light.png
dsh-go/app/test/settings/goldens/appearance_dark.png
dsh-go/app/test/settings/goldens/appearance_light.png
dsh-go/app/test/settings/goldens/en_appearance_light.png
dsh-go/app/test/settings/goldens/launch_section_center_dark.png
dsh-go/app/test/settings/goldens/launch_section_center_light.png
dsh-go/app/test/settings/goldens/launch_section_default_dark.png
dsh-go/app/test/settings/goldens/launch_section_default_light.png
dsh-go/app/test/settings/goldens/launch_section_fill_dark.png
dsh-go/app/test/settings/goldens/launch_section_fill_light.png
dsh-go/app/test/settings/goldens/settings_hub_full_dark.png
dsh-go/app/test/settings/goldens/settings_hub_full_light.png
dsh-go/app/test/settings/goldens/wallpaper_section_default_dark.png
dsh-go/app/test/settings/goldens/wallpaper_section_default_light.png
dsh-go/app/test/share/goldens/card_file_dark.png
dsh-go/app/test/share/goldens/card_images_grid_dark.png
dsh-go/app/test/share/goldens/card_images_light.png
dsh-go/app/test/share/goldens/card_images_missing_paper.png
dsh-go/app/test/share/goldens/card_long_light.png
dsh-go/app/test/share/goldens/card_plugin_card.png
dsh-go/app/test/share/goldens/card_plugin_link_card.png
dsh-go/app/test/share/goldens/card_plugin_link_light.png
dsh-go/app/test/share/goldens/card_reply_card.png
dsh-go/app/test/share/goldens/card_reply_card_dusk.png
dsh-go/app/test/share/goldens/card_reply_dark.png
dsh-go/app/test/share/goldens/card_reply_glass.png
dsh-go/app/test/share/goldens/card_reply_glass_turquoise.png
dsh-go/app/test/share/goldens/card_reply_light.png
dsh-go/app/test/share/goldens/card_reply_paper.png
dsh-go/app/test/share/goldens/card_rounds_light.png
dsh-go/app/test/shell/adaptive/goldens/wide_panel_dark.png
dsh-go/app/test/shell/adaptive/goldens/wide_panel_light.png
dsh-go/app/test/shell/adaptive/goldens/wide_sidebar_dark.png
dsh-go/app/test/shell/adaptive/goldens/wide_sidebar_light.png
dsh-go/app/test/shell/goldens/brand_mark_dark.png
dsh-go/app/test/shell/goldens/brand_mark_light.png
dsh-go/app/test/shell/goldens/drawer_dark.png
dsh-go/app/test/shell/goldens/drawer_light.png
dsh-go/app/test/shell/goldens/drawer_stopped_dark.png
dsh-go/app/test/shell/goldens/drawer_stopped_light.png
dsh-go/app/test/shell/goldens/en_quick_panel_320_dark.png
dsh-go/app/test/shell/goldens/en_quick_panel_320_light.png
dsh-go/app/test/shell/goldens/en_quick_panel_items_320_dark.png
dsh-go/app/test/shell/goldens/en_quick_panel_items_320_light.png
dsh-go/app/test/shell/goldens/quick_panel_320_dark.png
dsh-go/app/test/shell/goldens/quick_panel_320_light.png
dsh-go/app/test/shell/goldens/quick_panel_dark.png
dsh-go/app/test/shell/goldens/quick_panel_items_320_dark.png
dsh-go/app/test/shell/goldens/quick_panel_items_320_light.png
dsh-go/app/test/shell/goldens/quick_panel_light.png
dsh-go/app/test/shell/goldens/settings_appearance_dark.png
dsh-go/app/test/shell/goldens/settings_appearance_large_text_dark.png
dsh-go/app/test/shell/goldens/settings_appearance_large_text_light.png
dsh-go/app/test/shell/goldens/settings_appearance_light.png
dsh-go/app/test/shell/goldens/welcome_dark.png
dsh-go/app/test/shell/goldens/welcome_light.png
dsh-go/app/test/star/goldens/star_prompt_dark.png
dsh-go/app/test/star/goldens/star_prompt_light.png
dsh-go/app/test/system_entry/goldens/share_landed_dark.png
dsh-go/app/test/system_entry/goldens/share_landed_light.png
dsh-go/app/test/update/goldens/update_sheet_dark.png
dsh-go/app/test/update/goldens/update_sheet_downloading_dark.png
dsh-go/app/test/update/goldens/update_sheet_downloading_light.png
dsh-go/app/test/update/goldens/update_sheet_light.png
dsh-go/app/test/update/goldens/update_sheet_signature_dark.png
dsh-go/app/test/update/goldens/update_sheet_signature_light.png
dsh-go/app/test/update/goldens/update_sheet_star_dark.png
dsh-go/app/test/update/goldens/update_sheet_star_light.png
dsh-go/app/test/usage/goldens/en_welcome_light.png
dsh-go/app/test/wallpaper/goldens/chat_bright_dark.png
dsh-go/app/test/wallpaper/goldens/chat_bright_light.png
dsh-go/app/test/wallpaper/goldens/chat_dark_dark.png
dsh-go/app/test/wallpaper/goldens/chat_dark_light.png
dsh-go/app/test/wallpaper/goldens/drawer_dark.png
dsh-go/app/test/wallpaper/goldens/drawer_light.png
dsh-go/app/test/wallpaper/goldens/home_bright_light.png
dsh-go/app/test/wallpaper/goldens/home_dark_dark.png
dsh-go/app/test/web/goldens/en_web_handle_sheet_light.png
dsh-go/app/test/workspace/goldens/drawer_workspace_dark.png
dsh-go/app/test/workspace/goldens/drawer_workspace_light.png
dsh-go/app/test/workspace/goldens/workspace_sheet_dark.png
dsh-go/app/test/workspace/goldens/workspace_sheet_light.png
```
