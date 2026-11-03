// SPDX-License-Identifier: AGPL-3.0-only
import 'package:flutter/material.dart';
import 'loading_surface.dart';
import '../schedules/schedule_port.dart';

class MbotScheduleSettingsPage extends StatefulWidget {
  const MbotScheduleSettingsPage({super.key, required this.port});
  final MbotSchedulePort port;
  @override
  State<MbotScheduleSettingsPage> createState() =>
      _MbotScheduleSettingsPageState();
}

class _MbotScheduleSettingsPageState extends State<MbotScheduleSettingsPage> {
  MbotScheduleData? _data;
  bool _busy = false;
  bool _failed = false;
  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() => _mutate(() async {});
  Future<void> _mutate(Future<void> Function() operation) async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _failed = false;
    });
    try {
      await operation();
      final data = await widget.port.load();
      if (mounted) setState(() => _data = data);
    } catch (_) {
      if (mounted) setState(() => _failed = true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  Future<void> _edit([Map<String, Object?>? existing]) async {
    final bots = _data?.bots ?? [];
    if (bots.isEmpty) return;
    final name =
        TextEditingController(text: existing?['name']?.toString() ?? '');
    final prompt =
        TextEditingController(text: existing?['prompt']?.toString() ?? '');
    final minutes = TextEditingController(
        text: existing?['kind'] == 'once'
            ? ''
            : (existing?['everyMinutes'] ?? 60).toString());
    var botId = existing?['botId'] as String? ?? bots.first['id'] as String;
    var kind = existing?['kind'] as String? ?? 'daily';
    var time = existing?['time']?.toString() ?? '09:00';
    var invalid = false;
    try {
      final route = DialogRoute<bool>(
          context: context,
          builder: (context) => StatefulBuilder(
              builder: (context, update) => AlertDialog(
                    title: Text(existing == null ? '新建定时任务' : '编辑定时任务'),
                    content: SizedBox(
                        width: 480,
                        child: SingleChildScrollView(
                            child: Column(
                                mainAxisSize: MainAxisSize.min,
                                children: [
                              if (invalid) const Text('请填写名称、任务内容和有效时间'),
                              TextField(
                                  key: const Key('mbot.schedule.name'),
                                  controller: name,
                                  maxLength: 40,
                                  decoration:
                                      const InputDecoration(labelText: '任务名称')),
                              if (bots.length > 1)
                                DropdownButtonFormField<String>(
                                    initialValue: botId,
                                    isExpanded: true,
                                    decoration: const InputDecoration(
                                        labelText: '执行 Bot'),
                                    items: [
                                      for (final b in bots)
                                        DropdownMenuItem(
                                            value: b['id'] as String,
                                            child: Text(
                                                b['name']?.toString() ?? 'Bot'))
                                    ],
                                    onChanged: (v) => update(() => botId = v!)),
                              DropdownButtonFormField<String>(
                                  initialValue: kind,
                                  decoration:
                                      const InputDecoration(labelText: '频率'),
                                  items: const [
                                    DropdownMenuItem(
                                        value: 'daily', child: Text('每天')),
                                    DropdownMenuItem(
                                        value: 'interval', child: Text('间隔')),
                                    DropdownMenuItem(
                                        value: 'once', child: Text('一次'))
                                  ],
                                  onChanged: (v) => update(() => kind = v!)),
                              if (kind == 'daily')
                                ListTile(
                                    title: Text(time),
                                    trailing: const Icon(Icons.schedule),
                                    onTap: () async {
                                      final parts = time
                                          .split(':')
                                          .map(int.parse)
                                          .toList();
                                      final picked = await showTimePicker(
                                          context: context,
                                          initialTime: TimeOfDay(
                                              hour: parts[0],
                                              minute: parts[1]));
                                      if (picked != null && context.mounted) {
                                        update(() => time =
                                            '${picked.hour.toString().padLeft(2, '0')}:${picked.minute.toString().padLeft(2, '0')}');
                                      }
                                    }),
                              if (kind != 'daily')
                                TextField(
                                    controller: minutes,
                                    keyboardType: TextInputType.number,
                                    decoration: InputDecoration(
                                        labelText: kind == 'once'
                                            ? '多少分钟后（编辑留空保留原时间）'
                                            : '间隔分钟，至少 5 分钟')),
                              TextField(
                                  key: const Key('mbot.schedule.prompt'),
                                  controller: prompt,
                                  minLines: 2,
                                  maxLines: 6,
                                  maxLength: 4000,
                                  decoration:
                                      const InputDecoration(labelText: '任务内容')),
                            ]))),
                    actions: [
                      TextButton(
                          onPressed: () => Navigator.pop(context, false),
                          child: const Text('取消')),
                      FilledButton(
                          key: const Key('mbot.schedule.save'),
                          onPressed: () {
                            final n = int.tryParse(minutes.text);
                            final validTime = kind == 'daily' ||
                                (kind == 'once' &&
                                    existing?['kind'] == 'once' &&
                                    minutes.text.isEmpty) ||
                                (n != null &&
                                    n >= (kind == 'interval' ? 5 : 1) &&
                                    n <= 10080);
                            if (name.text.trim().isEmpty ||
                                prompt.text.trim().isEmpty ||
                                !validTime) {
                              update(() => invalid = true);
                              return;
                            }
                            Navigator.pop(context, true);
                          },
                          child: const Text('保存'))
                    ],
                  )));
      final accepted = await Navigator.of(context).push(route);
      await route.completed;
      if (accepted != true || !mounted) return;
      final value = <String, Object?>{
        'name': name.text.trim(),
        'prompt': prompt.text.trim(),
        'botId': botId,
        'kind': kind,
        'time': time,
        if (existing != null) 'id': existing['id'],
        'enabled': existing?['enabled'] != false,
        if (kind == 'interval') 'everyMinutes': int.parse(minutes.text),
        if (kind == 'once' && minutes.text.isNotEmpty)
          'inMinutes': int.parse(minutes.text)
      };
      await _mutate(() => widget.port.save(value));
    } finally {
      name.dispose();
      prompt.dispose();
      minutes.dispose();
    }
  }

  Future<void> _delete(Map<String, Object?> row) async {
    final accepted = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
                title: const Text('删除定时任务？'),
                content: Text(row['name']?.toString() ?? ''),
                actions: [
                  TextButton(
                      onPressed: () => Navigator.pop(context, false),
                      child: const Text('取消')),
                  TextButton(
                      onPressed: () => Navigator.pop(context, true),
                      child: const Text('删除'))
                ]));
    if (accepted == true) {
      await _mutate(() => widget.port.delete(row['id'] as String));
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
      appBar: AppBar(title: const Text('定时任务'), actions: [
        IconButton(
            key: const Key('mbot.schedule.add'),
            tooltip: '新建',
            onPressed: _busy || _data?.bots.isNotEmpty != true ? null : _edit,
            icon: const Icon(Icons.add)),
        IconButton(
            tooltip: '刷新',
            onPressed: _busy ? null : _reload,
            icon: const Icon(Icons.refresh))
      ]),
      body: Align(
          alignment: Alignment.topCenter,
          child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 760),
              child: MbotLoadTransition(
                  loading: _data == null && _busy,
                  child: ListView.builder(
                    itemCount: (_data?.schedules.length ?? 0) + 1,
                    itemBuilder: (context, index) {
                      if (index == 0) {
                        return Column(children: [
                          const Padding(
                              padding: EdgeInsets.all(20),
                              child: Text(
                                  '定时任务只在 Bot 服务进程存活时执行。进程被杀后不会执行，当前不提供补跑。')),
                          if (_busy) const LinearProgressIndicator(),
                          if (_failed)
                            const Padding(
                                padding: EdgeInsets.all(20),
                                child: Text('操作未确认，请检查本机服务并刷新列表后再决定是否重试。')),
                          if (_data?.schedules.isEmpty == true)
                            const Padding(
                                padding: EdgeInsets.all(20),
                                child: Text('暂无定时任务')),
                        ]);
                      }
                      final row = _data!.schedules[index - 1];
                      return ListTile(
                          key: Key('mbot.schedule.${row['id']}'),
                          title: Text(row['name']?.toString() ?? '',
                              maxLines: 1, overflow: TextOverflow.ellipsis),
                          subtitle: Text(row['kind'] == 'daily'
                              ? '每天 ${row['time']}'
                              : row['kind'] == 'interval'
                                  ? '每 ${row['everyMinutes']} 分钟'
                                  : '一次性任务'),
                          onTap: _busy ? null : () => _edit(row),
                          trailing:
                              Row(mainAxisSize: MainAxisSize.min, children: [
                            Switch(
                                value: row['enabled'] != false,
                                onChanged: _busy
                                    ? null
                                    : (v) => _mutate(() => widget.port
                                        .save({...row, 'enabled': v}))),
                            PopupMenuButton<String>(
                                enabled: !_busy,
                                onSelected: (v) {
                                  if (v == 'delete') {
                                    _delete(row);
                                  } else {
                                    _mutate(() =>
                                        widget.port.run(row['id'] as String));
                                  }
                                },
                                itemBuilder: (_) => const [
                                      PopupMenuItem(
                                          value: 'run', child: Text('立即执行')),
                                      PopupMenuItem(
                                          value: 'delete', child: Text('删除'))
                                    ]),
                          ]));
                    },
                  )))));
}
