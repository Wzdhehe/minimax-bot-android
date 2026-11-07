<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 本机任务：产品表单、模型参数、创建与附件验收

本卡以远端 `feat/t-plugins` 最新提交为准。不要另起分支、不动 main/dev/integration、不 force、不 PR、不发布APK。只准备/构建/设备验收，本卡不授权修改源码、pin、依赖、校验器或共享文档。报告仍可只留本机；失败请给原始错误与实际HEAD，不自行降级为旧包。

1. `git status` 确认已跟踪源码无改动，`git fetch origin feat/t-plugins` 后 `git merge --ff-only origin/feat/t-plugins`。若不能快进就停并报告，不 reset/stash/覆盖。本机未跟踪报告保留。
2. 记录精确构建HEAD、tracked工作树状态。已有旧产品准备物包含旧JS，**不能只跑flutter build**。重新构建下列partial/mobile后prepare-product，全部闸门照常执行。
3. 工具链沿用本机实测路径。下列PowerShell命令在仓库根运行；`$stageDir` 必须是新目录（若存在换新后缀，不覆盖）。只复用已授权full-ICU及已经准备的SQLite/backup；本卡不重编Node。

```powershell
$stageDir = 'D:/android-setup/tmp/mbot-forms-attachments-new'
$esbuild = 'dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js'
node dsh-go/runtime/minimax-bot-host/build-runtime-bundle.mjs --source D:/scratch/minimax-code --esbuild-module $esbuild --out-dir "$stageDir/partial"
node dsh-go/tooling/mobile-runtime-bundle/build.mjs D:/scratch/minimax-code "$stageDir/partial" $esbuild "$stageDir/mobile"
python dsh-go/tooling/mobile-runtime-bundle/prepare-product.py --abi x86_64 --ndk D:/android-sdk/ndk/27.3.13750724 --mobile-bundle "$stageDir/mobile" --source-build-sha cd8823cf323e0c7216902a9a2dfb4a50542a99f85ffe21e4a2fa99e3436e9747
cd dsh-go/app
flutter build apk --debug --target-platform android-x64 --flavor x64
```

若本机bundler依赖尚未安装，回仓库根 `npm ci --ignore-scripts --prefix dsh-go/tooling/mobile-runtime-bundle`；该node_modules现已ignore，不提交。每一步检查退出码，任何输入/来源/摘要拒绝都停并报告，不能使用旧产物凑过关。源checkout必须仍是钉点97818b2，不切换上游版本。

4. 报APK路径/大小/SHA256；只安装 `emulator-5554`，API35/x86_64，applicationId `io.github.ccch1mneyyy.dsh_app.mbot`。只 `install -r`，不清数据、不wipe、不卸载其它App、不碰别的设备。记录真实bot_ready、UID及是否有崩溃。新JS必须重新准备，否则闸门会拒绝。
5. **验产品页面，不再重复跑整套独立组件probe**：

- 添加供应商是有分区、留白和滚动的独立页；键盘打开时可到达所有字段和保存。新建模型进入另一个独立页，分别填写context/output、推理等级和图片/PDF/视频/音频能力；保存后重新进入仍为服务端/native保存值。
- 可用无Key的专用验收供应商及两个专用模型，不能读取/复用电脑Agent或其它App的Key。列表每个模型独立开关；关一个不动另一个，默认模型关闭后不继续标默认。再启用并直接点模型行编辑，Key仍留空保留、不回显旧Key。不要点“测试连接”或冒充真实模型调用。
- 新建Bot实际检查：头像预览/指令/模型/推理强度/助手/主Bot/头像选择。可建专用临时Bot，设为第二个主Bot并保存，返回首页验证两个真实星标；不要取消Chief的最后管理员身份。截图和说明区分“控件存在”与“实际保存后重读”。
- 聊天输入框左侧加号可唤起系统文件选择器。可以自行准备不敏感的小txt/PNG测试文件，让系统选择器选中；实际上传后出现文件名预览、可移除。取消选择不能发请求或多出空附件。不要配置宽存储权限。
- 没有用户本人在App内配置Key时，附件可只验上传与移除，不点发送制造无Key模型回合；不要将上传成功说成图像理解/真实对话成功。已有Key也不要输出值/截图明文或读取prefs/descriptor/env。
- 没有“询问/完全允许”档位。自动工具批准的实际效果需要真实模型触发权限询问才能验；本卡没有此条件就写未验证，不能因为看不到权限页就说已全权操作手机。

6. 报告保留精确HEAD、tracked状态、每步退出码、APK摘要、设备/UID、截图路径、通过/失败/未测项。不要发完整配置、环境、descriptor或带凭据日志。报告/APK留本机，不提交；无需改HANDOFF或任务卡。

**边界**：这仍是x86_64产品包；ARM64/真机/真实provider、图像理解、完整插件能力、工具执行等没有本卡证据就明确未验证。不用旧组件58/23绿灯替代本产品结果。
