// SPDX-License-Identifier: AGPL-3.0-only
/// Provider metadata shared by settings and native host adapters. Saved secrets
/// are never part of this contract. New keys enter only via explicit user input.
library;

const mbotProviderApiFormats = [
  'anthropic-messages',
  'openai-completions',
  'openai-responses',
  'google-generative-ai',
];

class MbotProvider {
  MbotProvider(
      {required this.id,
      required this.name,
      required this.apiFormat,
      required this.baseUrl,
      required List<String> models,
      required this.enabled,
      required this.hasApiKey})
      : models = List.unmodifiable(models);
  final String id;
  final String name;
  final String apiFormat;
  final String baseUrl;
  final List<String> models;
  final bool enabled;
  final bool hasApiKey;

  factory MbotProvider.fromMap(Map<String, dynamic> value) {
    if (['key', 'apiKey', 'accessToken', 'authorization']
        .any(value.containsKey)) {
      throw const FormatException(
          'Provider projection contains a secret field');
    }
    return MbotProvider(
        id: value['providerId'] as String,
        name: value['name'] as String,
        apiFormat: value['apiFormat'] as String,
        baseUrl: value['baseUrl'] as String,
        models: (value['models'] as List).cast<String>(),
        enabled: value['enabled'] == true,
        hasApiKey: value['hasApiKey'] == true);
  }
}

class MbotProviderCatalog {
  MbotProviderCatalog(
      {required List<MbotProvider> providers,
      this.defaultProviderId,
      this.defaultModelId})
      : providers = List.unmodifiable(providers);
  final List<MbotProvider> providers;
  final String? defaultProviderId;
  final String? defaultModelId;
  factory MbotProviderCatalog.fromMap(Map<String, dynamic> value) =>
      MbotProviderCatalog(
        providers: (value['providers'] as List)
            .map((row) =>
                MbotProvider.fromMap(Map<String, dynamic>.from(row as Map)))
            .toList(),
        defaultProviderId: value['defaultProviderId'] as String?,
        defaultModelId: value['defaultModelId'] as String?,
      );
}

class MbotProviderDraft {
  const MbotProviderDraft(
      {this.id,
      required this.name,
      required this.apiFormat,
      required this.baseUrl,
      required this.models,
      this.enabled = true});
  final String? id;
  final String name;
  final String apiFormat;
  final String baseUrl;
  final List<String> models;
  final bool enabled;
  Map<String, Object?> toMap() => {
        if (id != null) 'providerId': id,
        'name': name,
        'apiFormat': apiFormat,
        'baseUrl': baseUrl,
        'models': models,
        'enabled': enabled
      };
}

abstract class MbotProviderPort {
  Future<MbotProviderCatalog> load();
  Future<MbotProviderCatalog> save(MbotProviderDraft draft,
      {String? newKey, bool clearKey = false});
  Future<MbotProviderCatalog> delete(String id);
  Future<MbotProviderCatalog> selectDefault(String id, String model);
}
