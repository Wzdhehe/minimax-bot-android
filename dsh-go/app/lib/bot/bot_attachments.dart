// SPDX-License-Identifier: AGPL-3.0-only
import 'dart:typed_data';

import 'package:file_picker/file_picker.dart';
import 'package:dsh_client/dsh_client.dart';

/// The system picker supplies a user-selected stream. Device paths stay in the
/// picker plugin, never in messages, descriptors, environment or diagnostics.
class BotPickedAttachment {
  const BotPickedAttachment({required this.name, required this.length, required this.data});
  final String name;
  final int length;
  final Stream<List<int>> data;
}

Future<List<BotPickedAttachment>> pickBotAttachments() async {
  final selected = await FilePicker.pickFiles();
  final result = <BotPickedAttachment>[];
  for (final file in selected.take(11)) {
    final length = file.lengthSync();
    if (length != null) {
      result.add(BotPickedAttachment(name: file.name, length: length, data: file.readAsByteStream()));
    } else {
      // Content providers may omit the size. Determine it with a bounded read,
      // never an unbounded readAsBytes or a native path exposed to the Agent.
      final bytes = BytesBuilder(copy: false);
      await for (final chunk in file.readAsByteStream().timeout(const Duration(seconds: 30))) {
        if (bytes.length + chunk.length > MiniMaxBotApi.maxUploadBytes) throw StateError('attachment_too_large');
        bytes.add(chunk);
      }
      result.add(BotPickedAttachment(name: file.name, length: bytes.length, data: Stream.value(bytes.takeBytes())));
    }
  }
  return result;
}
