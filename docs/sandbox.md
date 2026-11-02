# Android 上的 Agent 隔离设计

> 考前落地与验收命令见 [architecture.md](architecture.md) **T-Sandbox**（含删除 `dsh_file_paths.xml` 的 `external-path`、升 `targetSdk`、`dataExtractionRules` 排除 `minimax_bot_provider`）。下文为设计背景，不代替任务卡。

## 现状

当前 App 是原型：模型交互与文件工具在主应用进程中，文件工具仅允许访问应用私有工作区并要求确认写入；没有实现独立 Agent 沙箱。此前同 UID 的 shell 工具已关闭。Key 在 Android Keystore 保护下存储，但在主应用与引擎没有系统级隔离时，不能把加密存储描述成对同 UID 恶意代码的隔离。

## DSH Go 的实现

[DSH Go](https://github.com/ccch1mneyyy/dsh-Go) 文档说明它在 App 内安装 Ubuntu、Node.js 和 DSH，默认以 PRoot 兼容启动，普通本机使用不需要 Root 或另装 Termux。其[对话指南](https://github.com/ccch1mneyyy/dsh-Go/blob/main/docs/guide/zh/01-chat.md)提供工具审批卡，并有按对话自动允许、完全权限等可选模式；[手机操控指南](https://github.com/ccch1mneyyy/dsh-Go/blob/main/docs/guide/zh/04-phone-control.md)描述另外启用无障碍及可选 Shizuku、Sui、Root 或自连 ADB 的功能。

PRoot 的主要作用是让 Linux 用户态环境在 Android 上兼容运行。它不会单独创建新的 Android UID，也不能仅凭根目录映射就声称进程与应用数据、凭据或网络被系统强隔离。DSH Go 的实际防护还包括 Android 系统应用沙箱、引擎审批和手机操控的权限控制；不能把这些层混为一谈。

## 拟实施的边界

1. **默认隔离执行**：研究 Android `isolatedProcess` 服务或其他可验证的独立 UID 方案，使运行时不直接继承主应用的敏感数据和网络权限。由主应用的受控 broker 提供必要能力；先验证 MiniMax runtime、Node 原生依赖、PRoot 和 Android 后台策略能否在该 UID 正常工作。不能假定现有 PRoot 根目录可直接在 isolated UID 中启动。
2. **工作区**：默认使用专用工作区，不给引擎主应用私有目录。文件通过受控 broker、文件描述符或用户明确选择的文档树提供，限制路径和读写范围，并测试软链接、目录替换及并发访问。Android Storage Access Framework 返回的是 URI，不可假定等同可直接挂载的 POSIX 路径；需要设计导入/导出或同步方案。
3. **网络与密钥**：只有主应用或受控网络 broker 持有 Key，限制请求地址、方法和数据量；不要把凭据作为环境变量传给可执行任意命令的引擎。官方 CLI 自定义 Provider 将 Key 写入明文配置，必须在接入前解决。对隔离 UID 与 Android 网络权限的实际行为需按设备版本验证。
4. **工具和审批**：引擎发出结构化操作请求，由 broker 判断权限并向用户展示完整参数；写文件、执行命令和外部副作用默认逐次询问，拒绝、超时、断线均视为未批准。审批必须由执行边界强制，不能只在提示词或 UI 层声明。引擎若能够绕过 broker 直接执行，审批不能充当安全边界。
5. **系统权限**：当前只开发 Bot，不实现手机操控；不请求无障碍、Root、Shizuku、ADB 或全文件访问等权限。
6. **生命周期和资源**：提供停止按钮，处理 Android 杀进程后的中断状态；限制时间、输出和存储占用。单纯杀父进程不保证杀掉已分离的子进程，真正的进程组和资源限制必须在目标设备上验证。

上述是设计目标，不是当前已实现或已验证的安全保证。先做独立 UID + broker 的最小实测，再决定是移植 WebUI 的直接运行时还是采用其他承载方式。若平台限制使完整 Linux 工具链无法满足该隔离模型，应保留受限工具接口，明确报告不支持，不能悄悄退回同 UID 的任意命令执行。

参考：[Android 应用沙箱](https://source.android.com/docs/security/app-sandbox)、[Android 文档选择器](https://developer.android.com/training/data-storage/shared/documents-files)、[Android isolatedProcess](https://developer.android.com/guide/topics/manifest/service-element#isolated)。

## 工作区路径守卫模块（未接入）

仓库根目录独立模块 `packages/mbot-workspace-guard/`（MIT，自研；**不**放在 `dsh-go/`，不链接 AGPL 实现）提供路径边界检查 API（`createWorkspaceGuard` / `resolveWithinWorkspace`，集中在 `src/index.js`）。当前**尚未**接到任何运行时或工具调用点。

Linux 单测覆盖：工作区内读写成功；拒绝 `..`、绝对路径（如 `/sdcard`、其它 App 目录）、symlink 逃逸、以及 `workspace` 与 `workspace-evil` 同前缀混淆。运行：`cd packages/mbot-workspace-guard && npm test`。

路径守卫不是完整沙箱：不替代 `isolatedProcess`/独立 UID；不能单独防 TOCTOU（检查与 open 之间的目录替换）、硬链接内容策略、或 SAF URI。签名语义待与 `docs/architecture.md` 对齐。
