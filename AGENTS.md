# 开发入口

开始前阅读 `docs/HANDOFF.md`（最新续写分支/缺口）、`docs/DEVELOPMENT.md`、`docs/feature-parity.md` 和 `docs/sandbox.md`；修改 `dsh-go/` 时还须遵守该目录的 `AGENTS.md`。这些文档记录已实现能力、验证范围和未完成项，不要仅根据提交标题判断产品状态。

目标为 Android 本机多 Bot，参照 Grok Bot 布局。手机固定竖屏，平板自适应展开。用户配置 MiniMax Key、Base URL 和模型。无需远控、手机操控、云环境或通用 Agent 工作台。Mini App 是另一种可视化程序，暂不适配；MiniMax Code 插件市场是独立需求。官方 Bot 的业务源码可以内置复用。

`dsh-go/` 是当前产品，根目录 `app/` 是早期独立 Java 原型。2026-11-07的已验产品基线为e9d0beb：API35/x86_64上独立UID Node + 官方mcode/Bot、真实MiniMax文本与取消后继续对话通过，Flutter正式入口已换芯，不依赖DSH/PRoot rootfs。ARM64手机链尚未完成；具体HEAD、证据来源和边界以docs/HANDOFF.md为准。不得将宿主机协议测试、模拟模型或上游已有功能描述成Android设备功能已验证，也不要重复实现已经接通的launcher/换芯。

保留上游来源和许可证；尽量保持 `dsh-go/runtime/vendor/minimax-bot/` 快照不变，将适配层放在其外部。PRoot 是兼容层，不能当作安全沙箱；不要恢复同 UID 任意代码执行作为临时实现。密钥、凭据和用户数据不得提交或记录在诊断输出中。

用户明确要求每个可验证阶段及时 commit 并 push 到私有仓库，同时更新开发交接文档。按最新架构卡和交接继续功能分支链，保持 main/dev/integration 不变，不再按早期 main 推送要求操作。只暂存明确路径，保留其他开发者的工作，不强制推送，不创建 PR 或发布 APK。使用者对提交推送的授权覆盖子目录的默认限制。

验证入口见 `docs/DEVELOPMENT.md` 和 `.github/workflows/bot-host.yml`。实际执行过哪些检查、失败原因和未验证环境须记录清楚；完整 Android 构建和真机验证不能用宿主机测试替代。
