# 功能目标和验收

当前进度（2026-11-07）：[HANDOFF](HANDOFF.md)为最新证据，[NEXT-AGENT](handoff/NEXT-AGENT.md)为开发顺序。下面完整功能项未勾选，表示**该项全部验收尚未完成**，不表示已有代码/子链路不存在；不据此重新实现launcher、Bot服务或UI。

| 产品级子链路 | 现状 |
|---|---|
| 独立UID/原版runtime/正式Flutter入口 | e9d0beb，API35/x86_64产品真实启动并显示Bot；不含DSH rootfs |
| Key与真实模型 | 原生加密存储、连接测试、真实MiniMax文本回复已通过，Key不进guest；其它provider仍需各自验证 |
| 停止与恢复 | 生成中取消及下一条真实回复已通过；首token后停止、逐字增量尚未取证 |
| Bot/模型/附件界面 | 多主Bot、原版美术、创建/编辑、模型独立开关和参数、文件选择/上传已有；五档effort/附件入模未真实验证 |
| ARM64手机 | 有producer候选，尚无已准入full-ICU/产品包/手机执行，是当前主线 |
| 插件/工具/任务/群聊/后台 | 有上游业务或部分UI/协议，不算端到端完成；详见下一位合同 |


用户目标：Android 本机多 Bot，参照官方 MiniMax Bot；runtime 跟 mcode-webui/minimax-code fork（官方 MiniMax Code 为上游）；产品基于 DSH Go（AGPL-3.0）外壳。模型调用使用用户在设置中填写的 Key、Base URL 和模型 ID。无需远程电脑或手机操控；不做传统 Agent 工作台或通用 Mini App 容器。

**落点与许可证（已拍板）：** 接受 AGPL 开源；保留 DSH Go 外壳、只换核心；新代码可直接改 `dsh-go/`，根下 `packages/` 已有模块可继续用；分发 APK 须提供 AGPL 源码。Runtime **跟** [mcode-webui/minimax-code](https://github.com/mcode-webui/minimax-code) fork（MIT，`97818b2`），插件管理复用其插件市场、plugin-management 与 plugin-system；官方 MiniMax-AI/minimax-code 为上游，更新自行合并。考前实现与验收以 [architecture.md](architecture.md) 任务卡为准（T-Runtime → T-Sandbox → T-Settings → **T-Providers** → T-Usage → T-Schedule → T-Plugins）；本清单为产品全量目标，不代替任务卡。插件 Android 安装策略与定时 F.7 档位**仍未定**。

参考钉死点：MiniMax Bot 插件 [`0f2183f14dd8b8ebffcd08ba42ad719a53a5f5b8`](https://github.com/MiniMax-AI/MiniMax-Code-MiniApps/tree/0f2183f14dd8b8ebffcd08ba42ad719a53a5f5b8/plugins/minimax-ai/minimax-bot)；DSH Go 外壳 [`c1c0abb00907efdf48dcb443581686b67597c5a2`](https://github.com/ccch1mneyyy/dsh-Go/tree/c1c0abb00907efdf48dcb443581686b67597c5a2)；runtime（WebUI fork）[`97818b2`](https://github.com/mcode-webui/minimax-code/tree/97818b2)。**runtime 钉点的权威记录是 [UPSTREAM-RUNTIME.md](UPSTREAM-RUNTIME.md)**（含 commit、日期、tree、进入 bundle 的文件指纹与升钉流程），机器可读真值源在 `dsh-go/runtime/minimax-bot-host/upstream-runtime-pin.mjs`。官方上游合并点在合并时记录。所有条目在针对本项目完成实现和验证前保持未完成，不能因 DSH Go 或上游有功能就视为已接入。

- [x] 上游runtime钉点与升钉流程：钉点文件、文档、干净checkout的commit/tree、实际bundle输入四方校验；6个必需契约文件核SHA256，其余输入逐一对比钉住树的blob，23项pin测试及契约smoke通过。
- [x] 完整fork宿主**构建图检查**：真实createWebuiRuntimeHost/session-ports及官方Bot服务编入partial bundle，2,124上游输入逐blob验证、3项集成测试通过；CI已接入。该项仅记录早期partial构建图；后续完整产品/资源与启动证据见上表，不证明插件市场可用，见[构建范围](UPSTREAM-RUNTIME.md)。
- [x] canonical资源与真实Linux宿主验收（组件）：780个原资源逐blob/inventory校验，完整图/资源4项通过；真实fork factory/gateway/session/SQLite及原Bot JSON服务冷启动/重开、鉴权/创建/持久化读回1项通过，无session桩。**没有模型turn或Android证明**，见[T-RuntimeHost](handoff/T-RuntimeHost.md)。
- [ ] 本机安装、校验、启动、停止与升级 MiniMax Code 运行时（mcode-webui/minimax-code fork）；处理异常中断和进程清理。
- [x] better-sqlite3 12.11.1 Android x86_64验收：内存数据库、四普通FD VFS的WAL/并行只读/两次新PID读回已API26/35通过。Main固定文件owner已验证；后续完整FS/online backup已有API35/x86_64产品与组件证据；ARM64/真机仍缺，见[T-Persistence](handoff/T-Persistence.md)。
- [x] SDK→模型FD流式适配（组件）：四类真实pi-ai provider（含Google async scope）、工具事件、背压、取消及错误不重试宿主验证通过；动态JNI/Binder路由已API26/35通过。后续正式产品Anthropic通路已有真实文本验收，其他协议及流式设备范围仍须区分，见T-RuntimeBroker。
- [x] Main原子发布/替换组件与隔离Node文件commit：a49d79b在API26/35各25项通过，含不覆盖/并发/提交未知、Node快照/两PID及Key/数据库回归；不是完整runtime FS，见[T-RuntimeFS](handoff/T-RuntimeFS.md)。
- [x] Main显式授权的有界读取/大小及Node JNI：585f082在API26/35各30项通过，含实际Node读回、openat目录cap、父目录替换与输出close/deadline。stage创建/清理FD改造已编译，31项设备待CI；仍缺完整FS/原模块/launcher，见[T-RuntimeFiles](handoff/T-RuntimeFiles.md)。
- [x] native Key broker 的四协议文本流与取消、Key边界测试；API26/35各十项Keystore/隔离UID/Unix FD/IPC仪器用例通过。仅组件基线，不证明真实模型或完整runtime；见 [T-RuntimeBroker](handoff/T-RuntimeBroker.md)。

- [ ] 将官方 MiniMax Bot 的业务逻辑作为内置 Bot 服务接入上述 runtime：启动、持久化、会话恢复、审批和问答；不实现通用 Mini App 容器。
- [ ] 创建、编辑、删除长期 Bot：名字、角色、指令、头像、模型及推理设置；Chief Bot 管理团队。
- [ ] 一对一对话、消息流、会话历史、附件、图片/音视频/文件卡片和隔离预览。
- [ ] 群聊、成员管理、@ 提及、回复模式、接力和群组记录。
- [ ] Bot 间派发、结果回传、批次去重与不确定状态处理。
- [ ] 后台 helper、任务队列、并发限制、状态、停止、结果及审批。
- [ ] Bot 与团队记忆、查看与编辑、上下文预算、修订冲突、日志和隐私处理。
- [ ] 每个 Bot 设置内创建、查看、修改、删除自动任务；Bot 经 mbot/mcode MCP 自行设置同源任务，UI 不另建一套 scheduler。到点执行与通知按下方「定时任务验收档位」验收（真实 runtime/设备接入仍待完成）。
- [ ] 每 Bot 的使用量、任务列表、筛选、未读、消息操作、表情回应、外观和设置。
- [ ] 设置页信息架构落地：账号与模型、插件、通知、后台运行、工作区与存储、外观、关于（见 [architecture.md](architecture.md) **T-Settings** / `packages/mbot_settings`）；自动任务在各 Bot 设置内，无手机操控/远控入口。
- [ ] 页面过渡、轻量骨架及减少动态效果支持；主要新页面与消息流优化已落地，widget 测试通过，Android 帧耗时/输入/滚动验收待做，见 [UI handoff](handoff/UI-Motion.md)。
- [ ] 单一私有总工作区，项目放子目录，Bot 可绑定项目并共享协作；总根路径守卫已有，项目管理与 runtime 目录绑定待实现，cwd 不等于访问授权。
- [ ] 账号与模型组：用量查询（5h/周限额、积分；见 **T-Usage**，复用 webui `UsageQuotaClient`）。
- [ ] 通知：App 内总开关与分类开关（至少含定时任务、Bot 消息/完成、出错或打断等可裁剪类别）+ 对应 Android 通知渠道；可深链到系统渠道设置。
- [ ] 后台运行：检测通知权限、电池优化是否忽略等状态，展示说明，并提供跳转系统设置入口（含可选厂商文案）；**只做检测 + 跳转引导，不做保活黑科技**（不隐蔽多进程拉活、不超范围保活）。
- [ ] 插件市场：浏览、搜索、来源、版本和权限说明；本机安装、更新、启停、卸载、出错恢复，以及与 MiniMax Code 插件协议的兼容性验证；不将 Mini App 作为插件列出或安装。**官方插件包格式的 `deliveryTargets` 仅允许 `desktop` / `cloud`，没有 `android`**——产品 UI 与验收须明示此限制；**Android 安装策略仍未定**，不能假定市场包默认可在 Android 分发。
- [ ] Key / Base URL 配置、模型切换和失败处理的完整多协议验收；native存储和Anthropic产品真实文本已过，密钥不应由Agent直接读取。
- [ ] 独立执行边界、受控文件和网络、逐次审批、停止与资源限制；PRoot 仅用于兼容，不视作安全隔离。
  - T-Sandbox：严格工作区守卫已接入宿主文件接口和媒体路由；Android 存储/备份源码已收紧。完整 Android 构建与 `/sdcard` 仪器验收未运行；隔离Node/Key/模型FD/受限SQLite已在API26/35验证，完整runtime、工具审批和授权撤销仍缺，详见 [handoff](handoff/T-Sandbox.md)，本条保持未完成。
- [ ] 仅本机：无远程连接、无手机操控及其权限；无第三方品牌和不允许复用的美术素材；**分发 APK 满足 AGPL-3.0 源码提供义务**。
- [ ] 验证手机竖屏、各尺寸平板横屏/竖屏、折叠/多窗口；真实设备与真实 MiniMax 模型端到端测试。

## 界面与布局

手机以用户提供的参考图为方向：深色会话列表、主 Bot 形象、不同 Bot 和群组、最近消息与时间、搜索，以及进入单个 Bot 后的消息流和固定输入栏。使用原创名称与图形，不复制 Grok 的商标或受限素材。

平板使用窗口宽度自适应：紧凑窗口单列，中等窗口优先保证会话可读性，宽窗口展开会话列表与聊天，可按空间展示详情。设备方向按 Android 最小屏宽区分：小于 600 dp 视为手机，限制竖屏；600 dp 及以上允许系统旋转。折叠设备、分屏和厂商系统的实际行为需要真机检验。

## 定时任务验收档位（候选，待拍板）

Android 的后台执行和定时能力受系统限制。官方 MiniMax Bot 在进程内以约 30s 间隔轮询调度，**服务进程不在时不会触发**。下列档位是验收候选，**尚未选定**；选型取决于用户对架构报告 **F.7**（定时「死后是否补跑」/是否引入 Alarm 等）的拍板。文档与 UI **绝不能**写成「App 能在进程被杀后保证到点执行」，除非已实现并验证相应机制且用户明确选择该档。

| 候选档位 | 标准（实现并验证后才可勾选对应验收） |
|---|---|
| **A. 进程存活** | 前台服务/引擎在；到点约 30s 内 fire；通知权限开启时送达 |
| **B. 进程被杀** | UI 与文档均写明「不会执行」；可选显示「有 N 个任务可能错过」；**禁止**宣称已补跑，除非真做了 Alarm/拉起并成功跑完且用户选定该增强 |
| **C. 引导** | 未关电池优化等时设置页显示「需设置」并可跳转；不自动反复弹系统框骚扰 |

在 F.7 拍板前，对外说明不得超出已核实的上游行为（进程内轮询、进程死后不触发），也不得把任一候选档位写成已定产品承诺。是否增加「死后尝试拉起或仅发未能执行通知」属可选增强，须单独决策。
