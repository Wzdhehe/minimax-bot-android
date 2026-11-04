<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 文档相对链接检查器

找出 Markdown 文档里**已经失效的相对文件链接** —— 通常是文件被移动或改名之后留下的残根。

只读检查器：不改仓库、不联网、不装依赖，只用 Python 3 标准库。

## 用法

```bash
# 扫描 README.md 和 docs/（仓库根为当前目录）
python3 tooling/docs-links/check_links.py --root . README.md docs

# 不带参数时扫描 README.md 和 docs/
python3 tooling/docs-links/check_links.py --root .

# 扫描任意文件或目录（相对 --root）
python3 tooling/docs-links/check_links.py --root . docs/handoff AGENTS.md
```

- `--root` 指定仓库根；后面的参数是**相对该根**的 Markdown 文件或目录。
- 目录会递归扫描其中的 `.md` 文件。
- 不带后续参数时扫描 `README.md` 和 `docs`。
- 参数指向的路径必须存在于 `--root` 内；越界或不存在都算输入错误。

## 退出码

| 码 | 含义 |
|---|---|
| `0` | 支持范围内没有发现问题 |
| `1` | 发现失效链接或越界链接 |
| `2` | 输入或读取错误（根不是目录、路径不存在、路径越界、文件读不了） |

## 输出

问题写到 **stdout**，一行一条，按「源文件 → 行号」排序：

```
<源文件相对路径>:<行号>: <原因>: <链接目标原文>
```

例如：

```
docs/handoff/T-Plugins.md:12: missing_target: handoff/T-Sandbox.md
```

摘要行（`checked N document(s), found N issue(s)`）写到 **stderr**，不与结果混在一起，方便直接 `grep` 或 `wc -l` 统计。

行号从 1 开始。**行号 0** 表示问题属于整个文件而不是某一行（扫描目录时遇到指向根外的符号链接）。

## 原因码

| 原因 | 含义 |
|---|---|
| `missing_target` | 目标文件或目录不存在 |
| `outside_repository` | 目标解析后逃出 `--root`（含符号链接逃出）；**不会**去读取或扫描根之外的文件 |

## 支持的写法

只认下面几种行内链接：

| 写法 | 例子 |
|---|---|
| 普通行内链接 | `[文字](relative/file.md)` |
| 图片 | `![图片](images/demo.png)` |
| 尖括号目标（可含空格） | `[文字](<relative file.md>)` |

路径规则：

- 相对**链接所在文件的目录**解析，不是相对工作目录。
- 解析前先去掉 `#fragment` 和 `?query`，再检查文件或目录是否存在。
- 支持 URL 编码：`my%20file.md` 解析为 `my file.md`。
- 目标是目录也算存在。

## 明确不支持的写法

这是一个**小的定向检查器，不是完整的 Markdown 解析器**。下面这些写法**不检查**，也不会报错：

- **reference-style 链接** —— `[文字][ref]` 加单独一行的 `[ref]: target.md`。
- **目标里含未转义括号** —— `[文字](a(b).md)`。
- **带 title 的链接** —— `[文字](file.md "标题")`。
- **跨多行的链接**。
- **标题锚点是否有效** —— 只查文件在不在，不查文档里的标题还在不在。
- **远程网址是否可达** —— 远程链接一律跳过，不发网络请求。
- **四空格缩进的代码块** —— 只认围栏代码块（``` 或 ~~~）和单行 inline code。

因此：**退出码 0 只代表「在上述支持范围内没有失效链接」，不代表整份文档的链接全部有效。**

## 哪些链接会被跳过

- 纯锚点：`[文字](#section)`。
- 带 URI scheme 的目标：`https://`、`http://`、`mailto:`、`ftp://` 等。
- 绝对路径：`/etc/passwd`。
- 围栏代码块里的内容。
- 单行 inline code（`` `...` ``）里的内容。

## 安全性

- 只读：扫描前后文件内容不变（有测试覆盖）。
- 不联网、不读根外的文件、不跟随逃出根的符号链接。
- 不输出文档正文、环境变量或异常堆栈；只输出路径、行号、原因码和链接目标原文。
- 不自动修链接。

## 测试

```bash
python3 -m unittest discover -s tooling/docs-links -p 'test_*.py' -v
```

测试全部在临时目录里造自己的仓库，不碰真实 checkout。

## 扫描报告

对当前仓库的实际扫描结果见 [REPORT.md](REPORT.md)。
