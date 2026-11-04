<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 用户新增参考：@minimax-web/minimax-code 发行包

2026-11-04收尾，用户两次明确提供 **@minimax-web/minimax-code**，说明WebUI已发包且支持很多能力。下一轮应优先查证发行包及可复用接口，再决定剩余适配，不继续凭印象重造上游功能。**本轮只记录/只读查证，没有安装或切换依赖/pin。**

## 已取得的证据与未知项

- 查询npm官方registry的该精确包名（base metadata及latest）均返回404；未取得版本、dist-tags、integrity、gitHead或exports。**这不是「用户说的包不存在/未发布」的证据**，可能需要准确包页/registry/安装命令。已向用户请求补充，收尾不阻塞于此。
- 当前钉点及本轮读取fork默认分支的release/webui-npm/package.json仍写另一个历史包名@fectivnfy112358/minimax-code-web、0.1.0-preview.3、MIT、mcode-webui bin。该清单列better-sqlite3 12.11.1、node-pty 1.1.0及可选bufferutil/utf-8-validate；**不能把这个旧清单当成用户指定新包的清单**。
- GitHub repos/mcode-webui/minimax-code/releases/latest返回v0.5.5，是旧CLI归档说明，不能拿来给新WebUI包定版本或能力。
- 现行runtime源码pin继续由[UPSTREAM-RUNTIME](../UPSTREAM-RUNTIME.md)与机器常量管理。真实host/Bot验收已经通过，不能为了试新包丢弃既有组件或改main。

## 接手执行顺序（先研究，未验收不升级）

1. 确认用户所指的准确registry/包页/安装命令；记录精确版本、dist-tag、tarball和SSRI/SHA、仓库/source commit。查不到就明确待确认，不猜版本，也不偷偷换另一个同类包。
2. 只读查看tarball文件清单、package.json、exports/bin及文档。先识别是否真的导出可复用runtime库、可信身份注册、provider metadata/config、受控fetch/FS入口、插件市场与Bot/MCP/调度接口；不能因宣传或CLI能启动就判定这些接缝可用。
3. 对照本项目已实现的gateway scope、780资源及real-host probe，写「已可替代/仍需native适配/证据不足」表。若新版已有合适公开接口，优先替代本地私有import接缝，先做小范围兼容验证，不重复造实现。
4. 检查Key/配置存储、任意shell/子进程、FS/网络及native依赖。发行包有桌面SQLite/PTY不等于Android隔离UID可执行；不得用安装脚本/同UID进程启动来绕过限制。
5. 只有来源、包接口和兼容证据明确后，才提出/实施一个独立可回退的feature分支内升级阶段，更新pin/来源/许可证和测试。不得以SSRI或gitHead单独声称编译包与旧source pin逐字节对应。
6. 升级也必须保留native-only真实Key、无网络fallback、独立UID/FD、Main目录能力、不做远控/手机操控/Mini App容器、市场写入政策等边界。至少跑新的真实factory/Bot重开、身份/取消、native模型/Key回归；Android/真实provider另验。

不在本轮收尾执行升级。准确来源未确认时，不让接手模型擅自npm install一个「看起来相似」的包，不编写假接口，不以发行包功能丰富为由宣称Android App完成。
