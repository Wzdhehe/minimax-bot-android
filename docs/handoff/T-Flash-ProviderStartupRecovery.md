<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# 本机小卡：供应商启动与 endpoint 恢复

目标仅复查 d21681e 报告的两个失败点。旧表单、头像、附件、23/58组件suite不重跑。仍只用 feat/t-plugins，不改源码/pin/依赖/权限/校验，不 force、不 PR、不发布APK、不清数据、不wipe。

1. 确认已跟踪源码无未提交改动，fetch并merge --ff-only最新远端，记录精确HEAD。不能快进就报告，不reset/stash。保留原来的本机报告。
2. FD读取白名单在JS bundle内，**仅flutter build不够**。沿用上一卡的源checkout/NDK/esbuild/已授权full-ICU/SQLite/backup，新建暂存目录依次build partial、build mobile、prepare-product，再build产品x64 APK。原生输入未变，无需重编Node或重跑来源生产作业；必须让产物闸门检查通过，不能复用旧JS或手改manifest摘要。

```powershell
$stageDir = 'D:/android-setup/tmp/mbot-provider-recovery-new'
$esbuild = 'dsh-go/tooling/model-sdk-probe/node_modules/esbuild/lib/main.js'
node dsh-go/runtime/minimax-bot-host/build-runtime-bundle.mjs --source D:/scratch/minimax-code --esbuild-module $esbuild --out-dir "$stageDir/partial"
node dsh-go/tooling/mobile-runtime-bundle/build.mjs D:/scratch/minimax-code "$stageDir/partial" $esbuild "$stageDir/mobile"
python dsh-go/tooling/mobile-runtime-bundle/prepare-product.py --abi x86_64 --ndk D:/android-sdk/ndk/27.3.13750724 --mobile-bundle "$stageDir/mobile" --source-build-sha cd8823cf323e0c7216902a9a2dfb4a50542a99f85ffe21e4a2fa99e3436e9747
cd dsh-go/app
flutter build apk --debug --target-platform android-x64 --flavor x64
```

暂存目录存在就换新后缀，不覆盖；每步检查退出码，失败即停。依赖node_modules可以复用，不重新npm安装或删缓存。

3. 只 install -r 到 emulator-5554，确认API35/x86_64/applicationId io.github.ccch1mneyyy.dsh_app.mbot。报告APK大小与SHA256。首次安装重启正常，之后测试恢复**不能靠force-stop或重新安装**。
4. 通过UI创建/复用专用无Key供应商和一个简单模型：保存后等待真实bot_ready与独立UID，关闭设置回到首页应恢复。若此处仍失败，保留确切HEAD/失败阶段码/是否准备了新JS并立即回报，不重复旧三组对照或扩大源码排查。
5. 基础通过后，把同一模型设置为context=1000000/output=128000、推理等级high+medium及图片/PDF/视频/音频，保存后应再次ready。新建Bot页必须从**guest实际模型目录**看到该模型与对应推理条目；只打开检查后取消，不用再建Bot。不得以native设置页回显代替这项。
6. 停用该供应商保存，仍应ready；再启用仍ready。每次关闭设置返回首页，不应一直保留“无法加载Bot服务”。若出现旧横幅，点一次“重新连接”应取得新native endpoint并恢复；等待期间应有进度提示且不能用旧连接创建/发送。无需反复点或force-stop。
7. 没有用户本人在App内配置真实Key就不点“测试连接”、不发送模型消息。不要读取/复用其它App、电脑Agent或env里的Key。没有模型回合就仍记真实provider/对话/自动审批未验证。
8. 报告仅给精确HEAD、tracked状态、各步退出码、APK摘要、四次ready/UID及模型目录/恢复的结果与少量截图路径。不dump descriptor/prefs/env、不打印Key。报告留本机，不提交；失败项如实写出，不自行改Core或跳过检查。

云端已得：旧FD读取三新正例全部descriptor_invalid，修后通过；真实完整bundle的四种host启动与basic重启过、无真实模型调用。此证据不替代本卡Android验证，也不扩成ARM64/真机/完整插件结论。
