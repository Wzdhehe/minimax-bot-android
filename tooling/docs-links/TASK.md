<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 给 M3.1 Flash：文档相对链接检查器

状态：外部agent已78c8293交付，主agent860563e验收补强，文档CI41784617417通过。以下原任务约定保留供追溯；无需重复实现。TASK.md由主agent维护。

## 仓库与边界

- 仓库：`Wzdhehe/minimax-bot-android`，只在现有 `feat/t-plugins` 分支续写，不另开分支。
- 先读根 `AGENTS.md`。只新增或修改 `tooling/docs-links/` 内的交付文件。
- 不修改 App、runtime、vendor、工作流、仓库其他文档、依赖或许可证文件。主 agent 负责后续 CI 接入和 HANDOFF 更新。
- 不动 main/dev/integration，不 force push、不改已推送历史、不创建 PR/发布；不提交密钥、日志、缓存、`__pycache__` 或生成物。新文件加 `SPDX-License-Identifier: AGPL-3.0-only` 头。
- 用 Python 3 标准库即可；不安装依赖，不访问网络，不启动 Android/Flutter/Node 或上游项目。

## 要解决的问题

开发文档很多，文件移动后可能留有失效的相对文件链接。做一个只读检查器，找出这类链接，方便后续接手者定位资料。不是完整 Markdown 解析器，不验证标题锚点或远程网址。

## 交付文件

1. `tooling/docs-links/check_links.py`：检查脚本。
2. `tooling/docs-links/test_check_links.py`：使用 unittest + 临时目录的测试。
3. `tooling/docs-links/README.md`：用法、退出码、支持范围和明确不支持的语法。
4. `tooling/docs-links/REPORT.md`：对当前仓库 README.md 与 docs/ 的实际扫描命令、输出、已有问题；不要为了消除报告而修改目录外文件。

## 最小功能

入口约定：

```bash
python3 tooling/docs-links/check_links.py --root . README.md docs
python3 -m unittest discover -s tooling/docs-links -p 'test_*.py' -v
```

- `--root` 指定仓库根；后续参数是相对该根的 Markdown 文件或目录，目录递归扫描 `.md`。无后续参数时扫描 `README.md` 和 `docs`。
- 只支持单行内联 `[文字](relative/file.md)`、`![图片](images/demo.png)` 及尖括号目标 `[文字](<relative file.md>)`。无需支持 reference-style、目标含未转义括号、带 title 的链接或多行链接；README 明确限制，避免误称支持完整 CommonMark。
- 路径相对**链接所在文件的目录**解析。去掉 `#fragment` / `?query` 后检查文件或目录是否存在；支持 `%20` 等 URL 编码。
- 忽略纯 `#anchor`、远程/其他 scheme 链接（http/https/mailto 等）、绝对路径。忽略 fenced code block 和单行 inline code 中的示例链接。
- 相对路径解析后若逃出 `--root`（包括 symlink 逃出），报 `outside_repository`，不要去读取或扫描根之外的文件。
- 结果按源文件/行号排序，输出源文件相对路径、行号、目标相对链接与固定原因（例如 `missing_target`）。不要输出文档正文、环境变量或完整异常堆栈。
- 退出码：0=支持范围内无问题；1=发现失效/越界链接；2=输入或读取错误。只读，不自动修链接。

保持实现小而直接，避免引入通用 Markdown 框架或扩展任务范围。

## 验收

测试至少覆盖：

- 正确链接按所在文档目录解析（不是工作目录）。
- 缺失目标：正确源行号、固定错误码、非零退出。
- URL/纯锚点忽略；片段、query、编码路径与尖括号路径按约定处理。
- fenced/inline code 中的示例不误报。
- 相对路径或 symlink 逃出根时拒绝；不扫描外部目标。
- 扫描和测试不修改输入文件。

先运行 unittest，再扫描真实仓库。真实文档如已有坏链接，可如实记录并返回 1；不要吞掉错误来伪造通过，不要求本卡顺便清理所有历史文档。

## 提交和回报

工作开始前 fetch/pull 最新 `feat/t-plugins`；只暂存上面列出的明确交付路径。完成后 commit 并 push 到同一分支。若远端前进，先 fetch、正常 merge 远端功能分支后再推；禁止 force push 或重写已推送提交。目录外出现冲突时不要改动其他 agent 的代码，回报冲突即可。

向用户回报：提交 SHA、GitHub 目录链接、实际执行命令与输出、已有文档问题及语法限制。没有跑的检查写未运行。不要修改总 HANDOFF，主 agent 会验收并记录。
