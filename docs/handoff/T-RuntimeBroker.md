# T-RuntimeBroker（HANDOFF 第 2 项，部分完成，2026-11-03）

## 已落地（仅 host/Node 侧）

- `dsh-go/runtime/minimax-bot-host/model-broker.mjs`：host 侧模型代理。runtime/插件 → host loopback 代理（runtime token）→ native broker（loopback，host token）→ provider。host 不持有 provider Key；错误只用稳定错误码，不拷贝上游正文；runtime 断开时向 broker 传递取消。
- `runtime-descriptor.mjs`：可信 runtime descriptor（入口路径、钉点 commit、loopback 端口、会话 token），写入宿主私有目录并要求 0600；拒绝工作区内、symlink、全局可读或私有根之外的 descriptor；不经 argv/环境变量传递。`buildRuntimeEnv` 用白名单过滤环境变量，provider Key 类变量不会传给 runtime。
- `native-broker-double.helper.mjs`：**仅测试用**的 native broker 与上游 provider 替身，实现与真实 native broker 相同的线协议（`POST /v1/model/stream`，Bearer 会话 token，SSE `data:` 帧，`[DONE]` 结束）。
- 测试：`model-broker.test.mjs`、`runtime-descriptor.test.mjs`。测试中的 Key 均为假值。

## 验证

- `cd dsh-go/runtime/minimax-bot-host && node --test`：**46 passed / 0 failed**（原 35 个 + 新增 11 个）。

## 未完成（下一位接着做）

1. **native 侧 Kotlin broker**：在 Android 主进程实现 `/v1/model/stream`（AndroidKeyStore 解密 Key，只注入上游请求，SSE 透传，取消时关闭上游连接），协议以 `native-broker-double.helper.mjs` 为准。
2. 宿主真实打包/启动钉点 runtime 并写入 descriptor；配置/模型同步。
3. 真实模型流与取消的端到端验证（目前只有 host 侧替身测试）。
4. 没有 Gradle 完整构建、没有真机，均未验证。

## 说明

本轮用 mcode 自动开发时，第一次运行在 10:03 后无文件输出、约 50 分钟未按 `--timeout` 退出，被取消；补跑一轮也未产出。以上文件为已写出并测试通过的部分，按用户要求先提交，后续交给其他工具继续。
