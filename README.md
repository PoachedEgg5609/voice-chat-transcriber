# 语音转文字聊天 / Voice Chat Transcriber

按住说话，松开后文字进入 Minecraft 聊天框。**纯客户端模组**——服务端不需要安装，也不走自定义网络包，识别结果通过原版聊天协议填入或发送。

## 功能

- **四种识别引擎**，在设置界面里循环切换：

  | 引擎 | 说明 |
  | --- | --- |
  | **本地离线（Vosk，默认）** | 完全离线，模型下载包 41.9 MiB，模组内一键下载并做 SHA-256 校验 |
  | **在线 API** | 任意 OpenAI 兼容的 `/v1/audio/transcriptions` 接口 |
  | **讯飞在线听写** | 流式 IAT，带动态修正 `dwa=wpgs` |
  | **FunASR 本地服务** | 中文准确率更高、自动补标点，点一下自动部署（见下） |

- **按住录音、松开识别**：默认按住 `R` 录音、按 `K` 打开设置，都可在原版按键设置里改；
- **识别结果三种投递方式**：填入聊天框待确认（默认）／自动发送／界面被占用时复制到剪贴板；
- **可选麦克风设备**、最长录音时长（手改配置文件）、中英文界面。

## 环境要求

| 项目 | 版本 |
| --- | --- |
| Minecraft | 26.1.2 |
| Fabric Loader | >= 0.19.2 |
| Fabric API | 0.155.2+26.1.2 |
| Java | >= 25 |

## 安装

1. 安装 Fabric Loader 0.19.2 与 **Fabric API 0.155.2+26.1.2**；
2. 把 `voice-chat-transcriber-26.1.2-1.2.1.jar` 和 Fabric API 的 jar 都放进 `.minecraft/mods/`；
3. 进入世界后按 `K` 打开设置。

**只有本地离线模式需要额外一步**：点「下载中文离线模型」，模型会下载到

```text
%LOCALAPPDATA%\voice-chat-transcriber\models\vosk-model-small-cn-0.22
```

并做 SHA-256 校验。下载包 41.9 MiB，解压后约 65 MiB，运行时通常占用约 300 MiB 内存。其余在线引擎填好凭据即可用。

## 在线 API 模式（可选）

在设置中切换到「识别方式：在线 API」，配置 OpenAI 兼容的 `/v1/audio/transcriptions` 端点、模型、语言与 API Key。

本机地址（`localhost` / `127.0.0.1` / `::1`）允许使用 HTTP 且可以不填 Key；**远程地址强制 HTTPS**。

## 讯飞在线听写（可选）

1. 在讯飞开放平台创建应用，并为该应用开通「语音听写（流式版）」；
2. 按 `K` 打开设置，把识别方式切到「讯飞在线听写」；
3. 填写**该应用自己的** `AppID`、`APIKey`、`APISecret` 并保存——不要使用任何示例仓库里公开的测试密钥；
4. 按住录音键说话，松开后模组按 16 kHz / 16-bit / 单声道 PCM 流式上传。

协议为 `wss://iat-api.xfyun.cn/v2/iat` + HMAC-SHA256 鉴权 + 动态修正。默认最长录音 30 秒；讯飞上限 60 秒，需要用 `maxRecordingSeconds` 手动调整（见下）。免费额度以讯飞控制台为准。

## FunASR 本地服务（可选，中文准确率更高）

FunASR 是 Python 侧框架，无法嵌入 Java 模组，因此采用「本机服务 + 模组调用」：**录音只发往本机回环地址，不出本机**。

模组自带启动器与服务脚本，按 `K` → 切到「识别方式：FunASR 本地服务」→ 点「**启动 FunASR 服务**」即可，不需要手动装依赖、放脚本或启服务。脚本会：

1. 从 jar 解出到 `config\voice-chat-transcriber\`；
2. 探测机器上的 Python（`py` 启动器 / `PATH` / 常见安装目录都试），**优先复用系统里已装好的环境**，否则在 `funasr-runtime\` 建隔离 venv（不污染系统 Python）；
3. 首次从 ModelScope 下载约 1.1 GiB 模型；
4. 以分离进程启动服务，日志写在 `config\voice-chat-transcriber\logs\funasr-server.log`；
5. **关闭游戏时自动停止本次启动的服务**；你自己手动启动的服务不会被关掉。

### 前置条件

- **一键启动目前只支持 Windows**（依赖 PowerShell 与 Windows 版启动脚本）；其他系统请手动运行 `start-funasr.ps1` 的等价操作。
- 目标机器需要 **Python 3.10–3.14**。下限由依赖自身的 `Requires-Python` 决定：

  | 依赖 | 要求 |
  | --- | --- |
  | `torch` | **>= 3.10**（最严格的一个，决定下限） |
  | `modelscope` / `fastapi` / `uvicorn` / `python-multipart` | >= 3.10 |
  | `funasr` | >= 3.7（不是瓶颈） |

  低于 3.10 时脚本会直接拒绝并说明原因；高于 3.14 不会被拒绝，但若某些依赖还没有对应的预编译包，pip 安装会失败——失败原因会写进 `launcher-trace.txt`。

- `python` **不在 PATH 上也可以**：脚本会依次尝试 `py` 启动器、`python3` / `python`，以及 `%LOCALAPPDATA%\Programs\Python`、`%ProgramFiles%\Python*` 等常见安装位置。

### 启动失败怎么查

日志在 `config\voice-chat-transcriber\logs\funasr-server.log`。**若这份日志与 `models\` 都是空的**，说明失败发生在创建它们之前——看同目录下的 **`launcher-trace.txt`**，它由脚本的第一个动作写入：

| 追踪内容 | 含义 | 处理 |
| --- | --- | --- |
| 文件不存在 | 脚本没被执行（安全软件/组策略拦截 `.ps1`） | 检查杀软设置 |
| `action=...` 后跟 `FATAL: ...` | 见 FATAL 后的具体原因 | 按提示处理 |
| `rejected: ...` 若干行 | 找到了这些解释器但都不可用，每行带原因 | 按原因处理 |
| `python=...` 与 `python-pid=...` | Python 可用、服务已拉起 | 之后看 `funasr-server.log` |
| `pip-output: ...` | 依赖安装失败，末尾是 pip 的原因 | 常见为网络或无预编译包 |
| `venv-output: ...` | 创建隔离环境失败 | 见末尾原因 |

### 依赖装不上时（网络受限的机器）

脚本按顺序尝试多个依赖源：清华 TUNA → 阿里云 → 中科大 → 腾讯云 → PyPI 官方。某个镜像在特定网络里可能完全不可达，其表现是

```text
ERROR: Could not find a version that satisfies the requirement funasr (from versions: none)
```

——这**不代表包不存在**，只是那个源到不了。脚本会把每个源都试一遍，并把每处失败原因记进 `launcher-trace.txt`。

如果所有源都不通，用**离线安装**：

```powershell
# 1) 在能联网的机器上取好 wheel（Python 版本尽量与目标机一致）
python -m pip download torch torchaudio funasr modelscope fastapi uvicorn python-multipart -d .\wheels
# 2) 把 wheels 目录拷到目标机
.\start-funasr.ps1 start -WheelDir <wheels 目录的绝对路径>
```

### 为什么自带服务脚本，而不用官方 `funasr-server`

官方 `funasr-server` 的流水线是**硬编码**的：`paraformer` 别名固定为 SeACo ASR + 1.1 GiB 的 `ct-punc` 标点模型，`--model` 只能选固定别名，`--model-path` 只换 ASR 主干、换不掉标点模型。

体积按 ModelScope 官方文件清单实测（单位 MiB）：

| 部件 | 官方默认 | 本项目选用 | 实测差异 |
| --- | --- | --- | --- |
| 标点模型 | `ct-punc`（`punc_ct-transformer_cn-en-common-vocab471067-large`）**1,131.6** | `ct-punc-c`（`punc_ct-transformer_zh-cn-common-vocab272727`）**282.5** | 输出**逐字相同**，快约 4 倍 |
| ASR 主干 | SeACo Paraformer **952.2** | vanilla Paraformer large **847.7** | 输出**完全相同**，快约 2 倍 |
| 静音检测 | `fsmn-vad` **1.7** | 同左 | — |
| **合计** | **2,085.5 MiB** | **1,131.9 MiB** | **省约 46%** |

因此自带脚本 `funasr_server.py` 直接指定「vanilla paraformer + `fsmn-vad` + `ct-punc-c`」这一组合，标点与识别质量与官方默认逐字一致。

### 模型别名

服务接受以下 `model` 值（也可在设置界面的「FunASR 模型别名」里改）：

- `paraformer`（默认）：vanilla Paraformer large，847.7 MiB；
- `paraformer-seaco`：SeACo 版，952.2 MiB，与 `paraformer` 输出一致、慢约一倍；
- `sensevoice`：SenseVoiceSmall，896.5 MiB。它本身不产出标点，本服务会用 `ct-punc-c` 补上；实测把「达摩院」识别成「打摩院」；
- 也可直接填 ModelScope 模型 id。

模型、VAD、标点都能用 `funasr_server.py --help` 里的参数替换（`--model` / `--vad-model` / `--punc-model`；标点填 `none` 可关闭并省下 282.5 MiB）。

### 手动使用（可选）

不想让模组代管时也可以自己跑（脚本在仓库根目录，jar 里也有一份）：

```powershell
.\start-funasr.ps1 status     # 退出码 0=在跑 1=未跑
.\start-funasr.ps1 start      # 幂等：自举环境 + 分离启动 + 等就绪
.\start-funasr.ps1 stop
.\start-funasr.ps1 log        # 看日志末尾

# 常用参数
.\start-funasr.ps1 start -Port 8321 -CpuOnly -ModelCache D:\funasr-models
.\start-funasr.ps1 start -Lazy          # 先监听、首次请求时才建模型（首次会卡很久）
.\start-funasr.ps1 start -WheelDir D:\wheels   # 离线安装依赖
```

默认是**预加载**：服务先把 ASR 与标点模型都建好再开始监听，所以 `/health` 报「ready」时是真的能立刻识别。脚本默认绑 `127.0.0.1:8321`；**服务没有鉴权，请不要改成 `0.0.0.0`。**

### 实测参考

| 场景 | 耗时 |
| --- | --- |
| 首次启动（装依赖 + 下载约 1.1 GiB 模型） | 约 2–3 分钟 |
| 模型已缓存时的启动 | 约 45–130 秒（取决于磁盘） |
| 13.4 秒多句中文音频（热态） | 0.33–0.51 秒 |
| 4.2 秒中文音频（热态） | 约 0.19 秒 |

数字来自本机 CPU 实测（Windows，无独显）。该引擎在 Java 侧复用「在线 API」的 multipart 传输（`file` / `model` / `language` / `response_format=json`），并额外加了两点限制：**服务地址必须是回环地址**（`127.0.0.1` / `localhost` / `::1`），否则拒绝录音；模型别名与端点存在 `funasrModel` 与 `funasrEndpoint` 字段，与 OpenAI 配置互不干扰。

## 配置

配置保存在 `.minecraft/config/voice-chat-transcriber.json`。几个只在文件里能改的字段：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `maxRecordingSeconds` | 30 | 单次录音上限，钳制在 3–120；讯飞另有 60 秒硬顶 |
| `microphone` | 空 | 留空用系统默认采集设备；可填设置界面显示的设备名 |
| `engine` | `offline` | `offline` / `openai` / `iflytek` / `funasr` |
| `funasrEndpoint` | `http://127.0.0.1:8321/v1/audio/transcriptions` | 点「启动 FunASR 服务」时会自动对齐端口 |
| `prewarm` | `true` | 服务就绪后立即预热 |

API Key 与讯飞凭据以**明文**保存在该文件里，请勿分享。

## 隐私说明

只有按住录音键时才采集麦克风。本地离线与 FunASR 模式只在本机处理录音；在线 API 模式会把该次录音发到你所选的服务商；讯飞模式发送 PCM 流。**Minecraft 服务器不会收到语音音频。**

开启「自动发送」后识别结果会直接进入聊天并发出，不再经过你确认——在多人服务器上请留意。

## 构建

需要 JDK 25：

```powershell
$env:JAVA_HOME = "<JDK 25 的路径>"
gradle clean build --no-daemon --console=plain
```

仓库内没有 `gradlew`，请用本机 Gradle 9.x 发行版；若在 PowerShell 里调用，注意 Gradle 的退出码常被 stderr 提示污染成 1，**判断成功要看 `BUILD SUCCESSFUL`**。

`check` 会跑两个回归套件（都不需要启动游戏）：

- `logicTest`：WAV 头、multipart 编码、中文空格归一化、FunASR 请求与响应形态；
- `iflytekProtocolTest`：讯飞签名（含固定时间戳的期望值）与动态修正语义。

另有需要外部输入的手动任务：`offlineEngineSmokeTest`（`-PofflineModelPath=`）、`jnaEncodingRegressionTest`、`iflytekHandshakeSmokeTest`（`-PiflytekConfigPath=`）。

## 第三方组件

- **Vosk Java API 0.3.45**，Apache License 2.0（已打进 jar）；
- **`vosk-model-small-cn-0.22`**，Apache License 2.0，由用户在设置中按需下载，不在仓库或 jar 内分发；
- **FunASR / Paraformer / FSMN-VAD / CT-Transformer**，Apache License 2.0，由用户按需下载到本机；
- 讯飞 WebSocket 协议实现参考了 MIT 许可的 IflySdk，不含任何测试凭据。

详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可

[MIT](LICENSE)
