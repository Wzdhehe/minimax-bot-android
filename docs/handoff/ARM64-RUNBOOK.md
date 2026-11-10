<!-- SPDX-License-Identifier: AGPL-3.0-only -->
# ARM64执行手册：把编译、来源核验、产品准入分开

适用：feat/t-plugins，2026-11-07；2026-11-10补A/C1/B2实际结果（见文末）。先读[当前状态](../HANDOFF.md)和[下一位合同](NEXT-AGENT.md)。本手册刻意给出停止点；**遇错不要自行取消安全检查或扩大写入范围。**

## 当前到底有什么

- 已用产品基线e9d0beb：API35/x86_64真实MiniMax文字、生成中取消、继续对话通过。不是ARM64。
- ARM64 producer：[build-arm64-source.py](../../dsh-go/tooling/android-node-probe/build-arm64-source.py)，其pin为[node-arm64-source-build.json](../../dsh-go/tooling/android-node-probe/node-arm64-source-build.json)。它产出**候选**，不自动准入产品。
- 独立编译作业：[android-node-arm64-source.yml](../../.github/workflows/android-node-arm64-source.yml)，只在本仓feat/t-plugins执行，使用自己的ARM64缓存和候选artifact名，不改x64的五个权威输入。
- 独立Actions来源检查：[verify-arm64-source-run.py](../../dsh-go/tooling/android-node-probe/verify-arm64-source-run.py)。检查成功也仅得到source-candidate-only收据，不是Android通过或准入开关。
- 当前云机没有可用NDK/clang/ninja/ccache，根盘剩余不足0.2GiB，/tmp剩余约3.6GiB；不应在这里硬塞完整Node源编译。2026-11-07实查9a7cffe最新CI作业annotation仍是计费/支出限制导致未启动，不是源码编译失败。环境会变，以新实查为准。
- prepare-product.py已在76f67a0接通ARM64分支（正反例齐全后），本机已编出未入库的ARM64调试APK。**不能把“产品准入或APK已构建”说成“手机可用或阶段C完成”。**

CI配置复核：初版07c5a34的run42379715185为failure且无build job；actionlint 1.7.7实际指出job级env不能引用runner.temp。已将CCACHE_DIR移入编译step的env，同一actionlint检查退出0，ARM64源码/来源20项通过。**这个初版失败不能归因于计费或Node编译**；修正版是否能分配runner，须另查新run的job/annotation。前述9a7cffe的计费阻塞是另一条已实查记录。

修正版01941e0已实查：run **42380251554**含build job，check **128426174992** 的annotation明确为账户付款/支出限制、job未启动；因此没有ARM64候选artifact或编译结果。不要重复rerun消耗排队，把这条事实交账户所有者或使用下方Ubuntu路径。

## 给Ubuntu agent的可转发任务

下面这段可以原样转发；它只负责长编译，不设计ABI消费代码。

> 仓库Wzdhehe/minimax-bot-android，继续feat/t-plugins，不另开分支。先读AGENTS.md、dsh-go/AGENTS.md、docs/HANDOFF.md和docs/handoff/ARM64-RUNBOOK.md。执行本手册“Ubuntu手工编译A”，仅准备官方工具/钉住的外部源码并运行现有ARM64 producer。**零仓库源码改动、零提交、不得删或改任何pin/校验，不做APK/安装/Release。** 不需要任何模型Key。先报告实际磁盘/CPU/内存与工具，资源不足就给出缺项，不盲清系统目录。编译期间冻结本地HEAD，不能为追远端而merge/rebase；他人可继续往远端推不相干改动。完成后按本手册格式给源码HEAD、工具、退出码、产物路径/大小/SHA和build-info核验结果。失败给第一个真实错误及有限日志路径，不反复重试或改脚本过关。手工产物只算候选，不编造Actions run ID。

## Ubuntu手工编译A

### A0：确认机器，不先下载几GB

从仓库根执行；不要打印完整env或认证信息：

```bash
git status --short
git branch --show-current
git rev-parse HEAD
uname -s
uname -m
nproc
free -h
df -h . /tmp
command -v python3
command -v git
command -v clang
command -v clang++
command -v ninja
command -v ccache
```

要求Linux x86_64；不是Windows、不是Ubuntu ARM64宿主。NDK脚本固定linux-x86_64 host片，不要移除平台拒绝。建议有约30GiB可用磁盘、8GiB内存，以实际源码/对象/3GiB缓存空间评估；这是资源建议，不是“有这些就必定成功”的承诺。若只有几GiB，先停止报告。不得删除用户工程、上游golden、系统SDK或其它agent数据凑空间。

原repo tracked干净才fetch和merge --ff-only到最新feat/t-plugins，保留未跟踪报告。不能快进就停止，不reset/stash。若缺工具，可用该机器允许的官方apt/Android SDK安装流程准备clang-18、ninja、ccache、**NDK27.3.13750724**，记录版本；无权限则报告，不改项目target/依赖来绕过。

### A1：准备路径与原始源码

路径为示例，须由机器上的实际位置替换。不要设置HOME/CODEX_HOME等系统变量。所有临时/源码/产物目录须属于本次任务且有充足空间。

```bash
MBOT_REPO="$PWD"
MBOT_MOBILE=/path/to/new/nodejs-mobile-arm64
MBOT_NDK=/path/to/android-sdk/ndk/27.3.13750724
MBOT_ARM64_ARCHIVE=/path/to/new-output/node-full-icu-arm64.zip
```

MBOT_MOBILE必须是外部、全新目录，不在repo源码里；MBOT_ARM64_ARCHIVE必须不存在。NDK目录应有source.properties和toolchains/llvm/prebuilt/linux-x86_64。**不要复制Windows NDK到Linux，也不要用Linux/glibc Node库冒充Android库。**

若无原始checkout，先读pin：

```bash
python3 - <<'PY'
import json
from pathlib import Path
p=json.loads(Path('dsh-go/tooling/android-node-probe/node-arm64-source-build.json').read_text())
for k in ('mobileRepository','mobileCommit','nodeCommit','upstreamTree','nodeTree','version','ndkRevision','abi','intl'):
    print(k, p[k])
PY
```

按该仓库/commit检出公共RockinChaos/nodejs-mobile及其node子模块，不按“最新tag”猜。可使用：

```bash
git clone --filter=blob:none --no-checkout https://github.com/RockinChaos/nodejs-mobile "$MBOT_MOBILE"
MBOT_MOBILE_COMMIT=$(python3 -c 'import json; print(json.load(open("dsh-go/tooling/android-node-probe/node-arm64-source-build.json"))["mobileCommit"])')
git -C "$MBOT_MOBILE" checkout --detach "$MBOT_MOBILE_COMMIT"
git -C "$MBOT_MOBILE" submodule update --init --depth 1 -- node
```

若远端拒绝深度子模块检出旧commit，报告具体错误，按Git正常获取该钉点对象；不能改nodeCommit或切最新Node。检出后node commit/upstreamTree、scripts/build.py和versions.py摘要都由现有producer再次检查。

### A2：只读preflight

```bash
python3 dsh-go/tooling/android-node-probe/build-arm64-source.py \
  --mobile-source "$MBOT_MOBILE" --ndk "$MBOT_NDK" \
  --output "$MBOT_ARM64_ARCHIVE" --check-only
```

唯一成功含义：打印`ARM64 source and toolchain preflight passed; no binary built`且退出0。它**没有编译库**。非0就停，按下面错误表处理，不进入A3。

### A3：真实编译

先记住`git rev-parse HEAD`与tracked状态。此后直到完成不得merge/rebase/改producer的四个输入；编译器会在末尾复查来源，改变HEAD会拒绝产物。远端有新提交不需要立刻合入本地编译工作树。

```bash
python3 dsh-go/tooling/android-node-probe/build-arm64-source.py \
  --mobile-source "$MBOT_MOBILE" --ndk "$MBOT_NDK" \
  --output "$MBOT_ARM64_ARCHIVE" --jobs 2
```

不要用`|| true`、删报错或管道吞掉退出码。若需要重定向日志，用本机安全路径并单独记录真实退出码；不要把完整编译env写日志。构建可能持续数小时，观察有限进度并报告，不能仅因没有新日志就杀掉再重开。ccache是编译缓存，不是已授权二进制；不因命中缓存跳源码/ELF/配置校验。

成功必须是进程退出0，末尾`Built unadmitted full-ICU ARM64 candidate`，新归档存在且算出SHA256。producer已核对源/index树、Android ARM64共享配置、full-ICU、Node ABI137、真实ICU/Unicode版本、目标ELF及库/配置摘要。**不要重置.build里的staged mobile patches来让git看起来干净**：原始checkout须干净，预备Node工作树的已暂存补丁树却正是验证对象，HEAD本来仍可能指向未打补丁的上游。

### A4：只读报告，不提交产物

只给：

- repo精确HEAD与tracked是否改动，source目录commit/tree，NDK/clang等实际版本；
- preflight和真实build的退出码、起止时间；
- 归档本地路径/大小/SHA256；build-info中version、target、source commit/tree、modules/ICU/Unicode、full-ICU配置和producer commit/inputs是否匹配；
- ELF AArch64/Android目标、动态依赖与LOAD对齐的实际核验结果；未做的写未验证；
- 首个失败、有限日志位置、下一最小动作。

不上传APK/Release，不提交ZIP/.so/.node/日志/env/Key；产物如需转移由主agent安排私有渠道，不能发带签名下载URL到文档。手工build没有Actions run ID，只能作为候选交主agent核验，不冒充下节成功收据。

## GitHub Actions路径B

只有计费/runner实际可用才尝试，不重复排无从启动的队列。新workflow会在自身或ARM64相关producer输入变化的feat/t-plugins推送时触发，也支持该分支workflow_dispatch；受仓库/分支双重限制，不跑main。

成功记录须是：正确workflow、正确分支/commit、唯一build job completed/success、对应唯一未过期candidate artifact。进度状态artifact不是候选库；cache保存成功不是Node编译成功。

```bash
GITHUB_REPOSITORY=Wzdhehe/minimax-bot-android GITHUB_REF_NAME=feat/t-plugins \
  python3 dsh-go/tooling/android-node-probe/verify-arm64-source-run.py "$MBOT_ARM64_RUN_ID"
```

只有真实ARM64 run ID可用，不能套x64成功run41826964330。验证器会对比本地与producer的七个权威输入（workflow、两份pin、两份build脚本、两份配方测试），校验run/job/artifact来源、完整分页、过期/重复/大小及GitHub摘要。成功输出候选收据，不下载库、不改产品准入。

**outerArtifactSha256是GitHub外层artifact ZIP的摘要**；gh run download会自动解外层，得到的node-full-icu-arm64.zip是内层，libnode.so又是内层成员，三者SHA不能混用。之后的消费者仍须核验内层归档、build-info、producer精确输入、headers/config和目标库。任何人自行传一个--source-build-sha不构成授权。

Windows CRLF会触发精确字节拒绝，按DEVELOPMENT已有git cat-file原始字节临时导出流程处理；不要改验证器或把生产摘要改成工作树值。若权威输入真正变化，应重新生产，不套旧产物。

## 常见失败：必须保持什么

| 报错/现象 | 正确动作 | 禁止动作 |
|---|---|---|
| GitHub job未启动、计费限制 | 记录run/job/annotation；换有资源的Ubuntu手工路径或等账户修复 | 写成编译失败/通过；反复排队或调整源码“修CI” |
| Missing tool / NDK错误 | 准备真实指定宿主/版本，或报缺项 | symlink别的编译器冒充、改NDK pin、Windows.cmd经shell执行 |
| source commit/tree/recipe不符 | 核查checkout、下载/字节、pin原始值 | 重算pin来迎合当前下载、删校验、git reset掉staged补丁 |
| producer输入尚未提交或HEAD漂移 | 保留现场；重新选冻结提交/独立工作树做新attempt | 在长编译中继续merge，让收据指向别的输入 |
| 输出目录已存在 | 保留旧attempt，明确改名/换全新目录后另试 | rmtree覆盖未知产物、把半包当成功 |
| 无空间/OOM/超时 | 记录真实资源/进度；保存缓存，交主agent处理 | 以旧lib/无Intl发行版补齐、伪造成功退出 |
| 只有x64库或Linux arm64库 | 拒绝ARM64 Android候选 | 仅改目录名/ABI标签、把glibc addon打进APK |
| prepare-product拒ARM64（B2之后） | 查真实输入：审阅记录、归档SHA、headers、Intl/SQLite/FD元数据、ELF/ABI | 直接删闸门或把验证标志手改true；用夹具库或x64输入凑过 |

## 消费者代码任务C：交强agent，不与A混改

A/B成功以后按[NEXT-AGENT阶段B/C](NEXT-AGENT.md)做。**SQLite固定ABI选择与输出隔离已经有代码，不要再造一份**；先补Node来源消费与对应基础输入，再做产品Gradle装配，最后才设备验收。

### C0：SQLite已经落地的准确接口

| 项目 | x86_64（默认，保持兼容） | arm64-v8a |
|---|---|---|
| prepare-sqlite参数 | 不传--abi，或--abi x86_64 | --abi arm64-v8a |
| Android26 triple | x86_64-linux-android26 | aarch64-linux-android26 |
| Node config target_arch / OS | x64 / android | arm64 / android |
| 准备根（相对android-node-probe） | build/ | build/abi/arm64-v8a/ |
| 必需输入 | 该准备根的node-headers/、node-headers.json、verified-jni/x86_64/libnode.so、verified-assets/基础probe | 对应ARM64准备根及verified-jni/arm64-v8a/libnode.so，不能引用左列 |
| 输出 | 准备根/sqlite-probe/、verified-jni/x86_64/libmbot_sqlite_probe.so及verified-assets内许可/记录 | 对应ARM64准备根，完全不写左列 |

脚本保持钉住归档、安全解包、Node24.22.2头清单/摘要、V8 ABI布局、原defines与esbuild版本检查；另核headers的OS/target_arch和libnode/新addon的ELF64小端共享对象/machine。Windows用真实clang.exe加唯一固定target，Linux用NDK的目标wrapper；缺目标libc++_shared则拒绝。ELF头检查只是架构防混装，**不能单独证明Android来源、full-ICU或合法producer**。

现有26项SQLite宿主/输入测试使用小型假库和mock编译器，验证两个宿主的argv、ABI目录隔离及篡改拒绝；11项backup宿主和6项原Node宿主回归通过。当前云机无NDK，**本轮未实际重编x64/ARM64 addon**；不得把单测打印的Prepared当原生库编译证据。x64 Windows之前编译的产物仍是此前版本，旧设备结果不自动覆盖新准备脚本。

### C1：下一位只补Node消费者，不能直接开产品开关

允许优先动：android-node-probe下新ARM64候选消费者及其测试；如确需复用prepare-probe.py的纯bundle函数，先读调用方，保持其默认x64路径/来源行为。**不改**旧x64五个权威producer输入、vendor、UI、Key/broker、UID/备份状态机或prepare-product的ARM64拒绝。

必须先完成：

1. 选择真实成功的ARM64 producer/私有产物来源。Actions必须由现有verify-arm64-source-run校验，手工产物则先独立审阅并记录准确来源；没有成功产物就停在这里，不用夹具库冒充。
2. 区分外层artifact ZIP、内层node-full-icu-arm64.zip及libnode三个摘要。读取前验证可信来源绑定的真实字节，不接受用户随便填一个source-build-sha就授权。不默认信任归档内自己声称的producer字段。
3. 限定安全解包与预算：拒绝重复/别名路径、越界、反斜杠、链接、非普通成员及畸形/超量zip；只向全新ARM64目录写允许清单。失败不能覆盖或污染x64已验输入。
4. 对照当前ARM64 pin与真实producer：mbotArm64Producer精确commit/四输入、mbotSourceBuild配方、唯一arm64 slice、Node ABI137、full-ICU实际版本、headers/config/library摘要和AArch64/Android/API26；保留许可。不能把x64的mbotSourceBuild直接改abi字段。
5. 正确建立上表ARM64 Node头/manifest/native库/基础probe资产，再调用现有prepare-sqlite --abi arm64-v8a。此阶段不需要新增通用Agent执行器，不需要真实模型Key。
6. 添加正反例：来源/commit/recipe/headers/config/ABI/库/zip中任一篡改都拒绝；缺Intl/ICU/FD基础资产不接产品；失败不改x64。补Linux/Windows路径与CRLF输入说明。确认旧来源5项、原Node宿主6项、SQLite26项、backup11项仍通过。

**C1停止点（历史，2026-11-10已完成）**：可信ARM64 Node输入和真正NDK编译的addon各自可核验后，已另开B2接通prepare-product。不要在同一任务里同时改consumer、原安全边界和UI。

允许路径要在具体卡里列明。不得顺手改x64的旧五个权威输入、vendor快照、Key边界、UID/Binder/backup/文件保护或UI；不把候选库接进产品后就称手机通过。arm64 flavor已经存在只表示构建配置，不表示引擎输入可用。原有x64产品与20项ARM64源码/来源测试、5项x64来源测试应继续通过。

最终要分别报告：**候选编译、来源授权、消费者准备、APK构建、设备启动、真实模型**。缺哪一步就留在哪一步，不按“做了很多代码”推算完成百分比。

## 2026-11-10实际结果：A候选与C1消费者

- **A**：手工候选已编出并复核（见[NEXT-AGENT阶段A](NEXT-AGENT.md)），审阅结论在[node-arm64-candidate.json](../../dsh-go/tooling/android-node-probe/node-arm64-candidate.json)。producer四输入以后若改变，消费者会拒绝此候选，必须重新生产与审阅，不改记录迎合。
- **C1**：从repo根，提交审阅记录后：

```bash
python3 dsh-go/tooling/android-node-probe/prepare-arm64-node.py \
  --ndk "$MBOT_NDK" --archive "$MBOT_ARM64_ARCHIVE"
python3 dsh-go/tooling/android-node-probe/prepare-sqlite.py --abi arm64-v8a \
  --ndk "$MBOT_NDK" --esbuild "$MBOT_ESBUILD_EXECUTABLE" --archive "$MBOT_SQLITE_ARCHIVE"
python3 -m unittest discover -s dsh-go/tooling/android-node-probe -p 'test_arm64_node_consumer.py' -v
```

2026-11-10云端Linux x86_64实测（C1）：两条准备命令均EXIT0；输出build/abi/arm64-v8a/verified-jni/arm64-v8a下libnode（`d46110d0…`）、NDK libc++_shared（API21 ident，按预编运行库允许≤26）、真实编出的libmbot_node_probe.so（95,608字节，`864156b8…21a4`）、libmbot_sqlite_probe.so（2,170,624字节，`8e8eb0ca…78ff`，36条defines，SQLite3.53.2）；readelf为AArch64、API26、LOAD 0x4000。node-validation.json：requiresIntl/requiresSqlite/requiresFdVfs均true，admittedForProduct=false。这些准备产物不入库。

- **模拟器边界**：云机/dev/kvm对box用户不可用（emulator-check accel实测无权限），仅有API35 x86_64镜像/AVD；软件模拟只可回归x64，**不能产生ARM64设备证据**。

### 2026-11-10实际结果：B2产品准入与调试APK

- **prepare-backup.py**对x86_64与arm64-v8a均EXIT0；arm64-v8a的libmbot_sqlite_backup.so为AArch64、API26、LOAD 0x4000。产物不入库。
- **prepare-product.py --abi arm64-v8a**与`--verify-only`均EXIT0（76f67a0）。闸门只信已提交审阅记录；admittedForProduct必须仍为false。真实拒绝：混入x64库、误传x64归档SHA、缺FD元数据、陈旧头文件。x64重跑prepare-product时，除prepare-product.py自身源摘要外，产品资产/库清单与接通前基线相同。
- 测试：`python3 -m unittest discover -s dsh-go/tooling/mobile-runtime-bundle -p 'test_product*.py'` → 35项OK；android-node-probe全目录149项OK（6项既有skip）。
- **ARM64调试APK**（本机，未入库）295,445,843字节，SHA256 `6331421c307d1c4a592072d49ef8e1279c56bbd26bb1ee2ce54eb0bb48f8622f`：仅arm64-v8a；26项product-inputs（引擎/JS/许可）全部命中；全部`.so` stored且在APK内16KiB对齐；extractNativeLibs=false（useLegacyPackaging=false）；无MANAGE_EXTERNAL_STORAGE；库均为AArch64。
- **重编x64调试APK** 290,520,571字节，SHA256 `9e37a9ee4e1598bffb095da80509244240117c22384fca01f2e6dda21715d7d5`；lib/与assets/mbot/与接通前基线APK（498a151构建，290,520,567字节）逐项摘要相同。
- **x64软解模拟器**（API35，无KVM）安装该x64包Success。设备时钟：17:39:16 Background started FGS Allowed；17:40:21 ForegroundServiceDidNotStartInTimeException，随后MiniMaxBotRuntime: bot_assets_manifest_verified并拉起IsolatedBotService；17:41:01主进程ANR；17:41:18 IsolatedBotService“failed to complete startup”后被杀。**未见bot_ready**。与此前软解FGS超时同类：**不写成启动回归通过，也不写成产品回归失败**，不能当ARM64设备证据。
- **阶段D跟进（2026-11-10）**：已确认上述 FGS 超时是产品启动顺序问题并合入修复（`ForegroundStartGate` 等到主 Looper 空闲再 `startForegroundService`）。同机同模拟器新包：19:18:01 `bot_fgs_requested`，19:18:03 `bot_fgs_foreground`，无 FGS 超时、无本包 ANR；IsolatedBotService 以独立 UID `u0i2` 启动。仍未见 bot_ready（19:19:33 `bot_runtime_start_timeout`）。不能把阶段 D 写成启动全流程通过或阶段 C 完成。

### 下一张卡：阶段C（用户指定的ARM64真机）

按[NEXT-AGENT阶段C](NEXT-AGENT.md)：仅用户指定设备，先只读确认序列号/ABI/API/boot；install -r同应用ID，不清数据/wipe。记录源码HEAD、APK大小/SHA、ready与真实UID。无该设备则保持“仅编译/打包，设备未验证”。
