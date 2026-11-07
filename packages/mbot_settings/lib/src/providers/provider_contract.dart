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

/// Non-secret mcode UserModelInputView metadata. Limits are token counts, not
/// request credentials; supported inputs advertise capability, not phone access.
class MbotModelSettings {
  MbotModelSettings(
      {required this.modelId,
      this.enabled = true,
      this.contextLimit,
      this.outputLimit,
      List<String> effortOptions = const [],
      List<String> inputModalities = const ['text'],
      this.reasoning})
      : effortOptions = List.unmodifiable(effortOptions),
        inputModalities = List.unmodifiable(inputModalities);
  final String modelId;
  final bool enabled;
  final int? contextLimit;
  final int? outputLimit;
  final List<String> effortOptions;
  final List<String> inputModalities;
  final bool? reasoning;

  factory MbotModelSettings.fromMap(Map<String, dynamic> value) {
    const allowed = {
      'modelId',
      'enabled',
      'limit',
      'effortOptions',
      'modalities',
      'reasoning'
    };
    if (value.keys.any((key) => !allowed.contains(key))) {
      throw const FormatException('Unexpected model metadata');
    }
    final limit = value['limit'] as Map?;
    final modes = value['modalities'] as Map?;
    return MbotModelSettings(
      modelId: value['modelId'] as String,
      enabled: value['enabled'] != false,
      contextLimit: limit?['context'] as int?,
      outputLimit: limit?['output'] as int?,
      effortOptions:
          (value['effortOptions'] as List? ?? const []).cast<String>(),
      inputModalities:
          (modes?['input'] as List? ?? const ['text']).cast<String>(),
      reasoning: value['reasoning'] as bool?,
    );
  }

  MbotModelSettings withEnabled(bool value) => MbotModelSettings(
        modelId: modelId,
        enabled: value,
        contextLimit: contextLimit,
        outputLimit: outputLimit,
        effortOptions: effortOptions,
        inputModalities: inputModalities,
        reasoning: reasoning,
      );
  Map<String, Object?> toMap() => {
        'modelId': modelId,
        'enabled': enabled,
        if (contextLimit != null || outputLimit != null)
          'limit': {
            if (contextLimit != null) 'context': contextLimit,
            if (outputLimit != null) 'output': outputLimit,
          },
        'effortOptions': effortOptions,
        if (reasoning != null) 'reasoning': reasoning,
        'modalities': {
          'input': inputModalities,
          'output': ['text']
        },
      };
}

class MbotProvider {
  MbotProvider(
      {required this.id,
      required this.name,
      required this.apiFormat,
      required this.baseUrl,
      required List<String> models,
      required this.enabled,
      required this.hasApiKey,
      List<MbotModelSettings> modelSettings = const []})
      : models = List.unmodifiable(models),
        modelSettings = List.unmodifiable([
          for (final id in models)
            modelSettings.where((row) => row.modelId == id).firstOrNull ??
                MbotModelSettings(modelId: id),
        ]);
  final String id;
  final String name;
  final String apiFormat;
  final String baseUrl;
  final List<String> models;
  final bool enabled;
  final bool hasApiKey;
  final List<MbotModelSettings> modelSettings;

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
        modelSettings: (value['modelSettings'] as List? ?? const [])
            .map((row) => MbotModelSettings.fromMap(
                Map<String, dynamic>.from(row as Map)))
            .toList(),
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
      this.enabled = true,
      this.modelSettings});
  final String? id;
  final String name;
  final String apiFormat;
  final String baseUrl;
  final List<String> models;
  final bool enabled;
  final List<MbotModelSettings>? modelSettings;
  Map<String, Object?> toMap() => {
        if (id != null) 'providerId': id,
        'name': name,
        'apiFormat': apiFormat,
        'baseUrl': baseUrl,
        'models': models,
        'enabled': enabled,
        if (modelSettings != null)
          'modelSettings': modelSettings!.map((row) => row.toMap()).toList(),
      };
}

abstract class MbotProviderPort {
  Future<MbotProviderCatalog> load();
  Future<MbotProviderCatalog> save(MbotProviderDraft draft,
      {String? newKey, bool clearKey = false});
  Future<MbotProviderCatalog> delete(String id);
  Future<MbotProviderCatalog> selectDefault(String id, String model);
}
