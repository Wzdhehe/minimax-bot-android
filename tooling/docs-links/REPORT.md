<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 文档链接扫描报告

对当前仓库 `README.md` 与 `docs/` 的实际扫描结果。**本报告只记录，不修复** —— 修复要改目录外的文件，超出本卡写入范围（`tooling/docs-links/`）。

- 扫描日期：2026-11-04（Asia/Shanghai）
- 仓库：`Wzdhehe/minimax-bot-android`
- 分支：`feat/t-plugins`，扫描时的 HEAD：`9d0dadd`（"Prepare the scoped Flash documentation task and record passing device checks"）
- 检查器：`tooling/docs-links/check_links.py`，用法与语法限制见 [README.md](README.md)

## 扫描命令与输出

### 1. 扫描

```console
$ python3 tooling/docs-links/check_links.py --root . README.md docs
docs/handoff/RuntimeBroker-2026-11-03-history.md:10: missing_target: handoff/T-AndroidNode.md
docs/handoff/RuntimeBroker-2026-11-03-history.md:12: missing_target: handoff/T-AndroidNode.md
docs/handoff/RuntimeBroker-2026-11-03-history.md:19: missing_target: ANDROID-RUNTIME-INTEGRATION.md
docs/handoff/RuntimeBroker-2026-11-03-history.md:25: missing_target: handoff/T-RuntimeBroker.md
docs/handoff/RuntimeBroker-2026-11-03-history.md:31: missing_target: handoff/T-RuntimeBroker.md
docs/handoff/RuntimeBroker-2026-11-03-history.md:33: missing_target: ANDROID-RUNTIME-INTEGRATION.md
docs/handoff/RuntimeBroker-2026-11-03-history.md:33: missing_target: handoff/T-RuntimeBroker.md
docs/handoff/RuntimeBroker-2026-11-03-history.md:35: missing_target: handoff/T-Isolation.md
docs/handoff/RuntimeBroker-2026-11-03-history.md:39: missing_target: handoff/T-RuntimeBroker.md
```

stdout 是上面的 9 行；stderr 是摘要行：

```console
checked 26 document(s), found 9 issue(s)
```

退出码：`1`（发现失效链接）。

### 2. 覆盖的文档

共 26 个（`README.md` + `docs/` 下全部 25 个已跟踪 `.md`）：

```
README.md
docs/ANDROID-RUNTIME-INTEGRATION.md
docs/DEVELOPMENT.md
docs/HANDOFF.md
docs/UPSTREAM-RUNTIME.md
docs/architecture.md
docs/feature-parity.md
docs/handoff/RuntimeBroker-2026-11-03-history.md
docs/handoff/T-AndroidNode.md
docs/handoff/T-Isolation.md
docs/handoff/T-NativeDataFiles.md
docs/handoff/T-Persistence.md
docs/handoff/T-Plugins.md
docs/handoff/T-Providers.md
docs/handoff/T-RuntimeBroker.md
docs/handoff/T-RuntimeFS.md
docs/handoff/T-RuntimeFiles.md
docs/handoff/T-SQLite.md
docs/handoff/T-Sandbox.md
docs/handoff/T-Schedule.md
docs/handoff/T-Settings.md
docs/handoff/T-Usage.md
docs/handoff/UI-Motion.md
docs/research.md
docs/research/nyral.md
docs/sandbox.md
```

### 3. 单元测试

```console
$ python3 -m unittest discover -s tooling/docs-links -p 'test_*.py' -v
...
----------------------------------------------------------------------
Ran 38 tests in 0.163s

OK
```

退出码 `0`。

## 已有问题

9 处失效链接**全部集中在同一个文件**：`docs/handoff/RuntimeBroker-2026-11-03-history.md`。

**根因**：这个文件原本在 `docs/` 下，后来被移动到 `docs/handoff/`，但正文里的相对链接前缀没有跟着改 —— 链接仍按「在 `docs/` 下」写，于是解析后多了一层或少了一层目录。

按目标形态分两类：

### 一、链接目标多了 `handoff/` 前缀（7 处）

目标写成了 `handoff/T-*.md`，但该文件现在**与它们同目录**，正确写法是去掉 `handoff/` 前缀。

| 行号 | 目标原文 | 实际解析到 | 正确目标应为 |
|---|---|---|---|
| 10 | `handoff/T-AndroidNode.md` | `docs/handoff/handoff/T-AndroidNode.md` | `T-AndroidNode.md` |
| 12 | `handoff/T-AndroidNode.md` | `docs/handoff/handoff/T-AndroidNode.md` | `T-AndroidNode.md` |
| 25 | `handoff/T-RuntimeBroker.md` | `docs/handoff/handoff/T-RuntimeBroker.md` | `T-RuntimeBroker.md` |
| 31 | `handoff/T-RuntimeBroker.md` | `docs/handoff/handoff/T-RuntimeBroker.md` | `T-RuntimeBroker.md` |
| 33 | `handoff/T-RuntimeBroker.md` | `docs/handoff/handoff/T-RuntimeBroker.md` | `T-RuntimeBroker.md` |
| 35 | `handoff/T-Isolation.md` | `docs/handoff/handoff/T-Isolation.md` | `T-Isolation.md` |
| 39 | `handoff/T-RuntimeBroker.md` | `docs/handoff/handoff/T-RuntimeBroker.md` | `T-RuntimeBroker.md` |

被指向的文件确实存在，只是不在链接写的位置：`docs/handoff/T-AndroidNode.md`、`docs/handoff/T-RuntimeBroker.md`、`docs/handoff/T-Isolation.md`。

### 二、链接目标少了 `../`（2 处）

目标写成了 `ANDROID-RUNTIME-INTEGRATION.md`，但那个文件在**上一层** `docs/` 下。

| 行号 | 目标原文 | 实际解析到 | 正确目标应为 |
|---|---|---|---|
| 19 | `ANDROID-RUNTIME-INTEGRATION.md` | `docs/handoff/ANDROID-RUNTIME-INTEGRATION.md` | `../ANDROID-RUNTIME-INTEGRATION.md` |
| 33 | `ANDROID-RUNTIME-INTEGRATION.md` | `docs/handoff/ANDROID-RUNTIME-INTEGRATION.md` | `../ANDROID-RUNTIME-INTEGRATION.md` |

被指向的文件存在：`docs/ANDROID-RUNTIME-INTEGRATION.md`。

### 对照：同目录的其它文档没有这个问题

以下链接写法正确，检查器没有报它们 —— 说明这是**单个文件的移动遗留**，不是仓库通用写法问题：

| 所在文件 | 链接 | 解析到 |
|---|---|---|
| `docs/DEVELOPMENT.md` | `handoff/T-RuntimeBroker.md` | `docs/handoff/T-RuntimeBroker.md` ✅ |
| `docs/HANDOFF.md` | `handoff/T-AndroidNode.md` | `docs/handoff/T-AndroidNode.md` ✅ |
| `docs/handoff/T-RuntimeBroker.md` | `T-Isolation.md` | `docs/handoff/T-Isolation.md` ✅ |

## 未做的检查

按卡片的语法范围，下列内容**没有被检查**，所以本报告不能证明它们没问题：

- reference-style 链接（`[文字][ref]` + `[ref]: target.md`）。
- 目标里含未转义括号的链接。
- 带 title 的链接（`[文字](file.md "标题")`）。
- 跨多行链接。
- **标题锚点是否存在**（只查文件在不在，不查文档里的标题还在不在）—— 例如 `#section` 指向的段落被删了，这里查不出来。
- 远程网址是否可达（不发网络请求）。
- 四空格缩进的代码块。

详见 [README.md](README.md) 的「明确不支持的写法」。

另外，本次扫描只覆盖 `README.md` 与 `docs/`。仓库里其他 `.md`（例如 `dsh-go/` 下的 186 个）**没有**扫描；需要时用同样的命令指定路径即可：

```bash
python3 tooling/docs-links/check_links.py --root . dsh-go
```

## 已知遗留：Python 产物未被忽略

根 `.gitignore` 不包含 `__pycache__/` 或 `*.pyc`。跑一次测试就会在 `tooling/docs-links/` 下生成
`__pycache__/`，在 `git status` 里显示为未跟踪。本卡**没有**提交它，也没有在本目录新增 `.gitignore`
（卡里只列了四个交付文件，`.gitignore` 不在其中）。主 agent 接入 CI 时若需要在仓库级忽略 Python 产物，
请一并决定加到根 `.gitignore` 还是本目录。

## 状态

- 检查器、测试、README 均已就位；`tooling/docs-links/` 之外**没有任何改动**。
- 上面列出的 9 处失效链接**未修复** —— 修复需要改 `docs/handoff/RuntimeBroker-2026-11-03-history.md`，超出本卡写入范围。
- 本卡不改 CI 接入、不改总 HANDOFF；由主 agent 验收并记录。
