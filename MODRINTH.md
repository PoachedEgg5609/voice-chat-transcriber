# 语音转文字聊天 / Voice Chat Transcriber

按住说话，松开后文字进入 Minecraft 聊天框。**纯客户端模组**——服务端不需要安装，也不走自定义网络包，识别结果通过原版聊天协议填入或发送。

## 功能

- **四种识别引擎**，在设置界面里循环切换：
  - **本地离线（Vosk，默认）**：完全离线，模型下载包 41.9 MiB，模组内一键下载并做 SHA-256 校验
  - **在线 API**：任意 OpenAI 兼容的 `/v1/audio/transcriptions` 接口
  - **讯飞在线听写**：流式 IAT，带动态修正
  - **FunASR 本地服务**：中文准确率更高、自动补标点，点一下自动部署（见下）
- **按住录音、松开识别**：默认按住 `R` 录音、按 `K` 打开设置，都可在原版按键设置里改
- **识别结果三种投递方式**：填入聊天框待确认（默认）／自动发送／界面被占用时复制到剪贴板
- **可选麦克风设备**，中英文界面

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

**只有本地离线模式需要额外一步**：点「下载中文离线模型」，模型会下载到 `%LOCALAPPDATA%\voice-chat-transcriber\models\` 并做 SHA-256 校验。下载包 41.9 MiB，解压后约 65 MiB。其余在线引擎填好凭据即可用。

## FunASR 本地服务（可选，中文准确率更高）

FunASR 是 Python 侧框架，无法嵌入 Java 模组，因此采用「本机服务 + 模组调用」：**录音只发往本机回环地址，不出本机**。

模组自带启动器与服务脚本，按 `K` → 切到「识别方式：FunASR 本地服务」→ 点「**启动 FunASR 服务**」即可，不需要手动装依赖、放脚本或启服务。脚本会：

1. 从 jar 解出到 `config\voice-chat-transcriber\`；
2. 探测机器上的 Python（`py` 启动器 / `PATH` / 常见安装目录都试），**优先复用系统里已装好的环境**，否则建隔离 venv（不污染系统 Python）；
3. 首次从 ModelScope 下载约 1.1 GiB 模型；
4. 以分离进程启动服务，日志写在 `config\voice-chat-transcriber\logs\funasr-server.log`；
5. **关闭游戏时自动停止本次启动的服务**；你自己手动启动的服务不会被关掉。

**前置条件**：一键启动目前只支持 **Windows**，且目标机器需要 **Python 3.10–3.14**（下限由 `torch` 的 `Requires-Python >= 3.10` 决定）。`python` 不在 PATH 上也可以，脚本会扫描常见安装位置。

### 模型体积是优化过的

官方 `funasr-server` 把 `paraformer` 别名硬编码为 SeACo ASR + 1.1 GiB 的 `ct-punc` 标点模型。按 ModelScope 官方文件清单实测（单位 MiB）：

| 部件 | 官方默认 | 本项目选用 | 实测差异 |
| --- | --- | --- | --- |
| 标点模型 | `ct-punc` **1,131.6** | `ct-punc-c` **282.5** | 输出**逐字相同**，快约 4 倍 |
| ASR 主干 | SeACo Paraformer **952.2** | vanilla Paraformer large **847.7** | 输出**完全相同**，快约 2 倍 |
| 静音检测 | `fsmn-vad` **1.7** | 同左 | — |
| **合计** | **2,085.5 MiB** | **1,131.9 MiB** | **省约 46%** |

本项目自带 [funasr_server.py](funasr_server.py) 直接指定「vanilla paraformer + `fsmn-vad` + `ct-punc-c`」：标点与识别质量与官方默认**逐字一致**，体积省约 46%。

实测参考（Windows / CPU）：首次启动含下载约 2–3 分钟；模型已缓存时启动约 45–130 秒；模型就绪后 13.4 秒多句音频识别 0.33–0.51 秒。

### 依赖装不上时（网络受限的机器）

脚本按顺序尝试多个依赖源：清华 TUNA → 阿里云 → 中科大 → 腾讯云 → PyPI 官方。某个镜像在特定网络里可能不可达（表现为 `Could not find a version that satisfies the requirement funasr (from versions: none)`，**不代表包不存在**）。若全部不通，用离线安装：

```powershell
# 1) 在能联网的机器上取好 wheel（Python 版本尽量与目标机一致）
python -m pip download torch torchaudio funasr modelscope fastapi uvicorn python-multipart -d .\wheels
# 2) 把 wheels 目录拷到目标机
.\start-funasr.ps1 start -WheelDir <wheels 目录的绝对路径>
```

### 启动失败怎么查

日志在 `config\voice-chat-transcriber\logs\funasr-server.log`。若该日志与 `models\` 都是空的，说明失败发生在创建它们之前——看同目录下的 **`launcher-trace.txt`**，它由脚本的第一个动作写入：

| 追踪内容 | 含义 |
| --- | --- |
| 文件不存在 | 脚本没被执行（安全软件/组策略拦截 `.ps1`） |
| `action=...` 后跟 `FATAL: ...` | 见 FATAL 后的具体原因 |
| `rejected: ...` 若干行 | 找到了这些解释器但都不可用，每行带原因 |
| `python=...` 与 `python-pid=...` | Python 可用、服务已拉起 |
| `pip-output: ...` | 依赖安装失败，末尾是 pip 的原因 |

### 手动使用（可选）

```powershell
.\start-funasr.ps1 status     # 退出码 0=在跑 1=未跑
.\start-funasr.ps1 start      # 幂等：自举环境 + 分离启动 + 等就绪
.\start-funasr.ps1 stop
.\start-funasr.ps1 log

# 常用参数
.\start-funasr.ps1 start -Port 8321 -CpuOnly -ModelCache D:\funasr-models
.\start-funasr.ps1 start -Lazy                 # 先监听、首次请求时才建模型
.\start-funasr.ps1 start -WheelDir D:\wheels   # 离线安装依赖
```

默认**预加载**：服务先把 ASR 与标点模型都建好再开始监听，所以 `/health` 报「ready」时是真的能立刻识别。服务默认绑 `127.0.0.1:8321`——**它没有鉴权，请勿改成 `0.0.0.0`。**

## 隐私

只有按住录音键时才采集麦克风。本地离线与 FunASR 模式只在本机处理录音；在线 API 模式会把该次录音发到所选服务商；讯飞模式发送 PCM 流。**Minecraft 服务器不会收到语音音频。**

开启「自动发送」后识别结果会直接进入聊天并发出，不再经过确认——在多人服务器上请留意。

API Key、讯飞凭据以明文保存在 `.minecraft/config/voice-chat-transcriber.json`，请勿分享该文件。

## 第三方组件

- **Vosk Java API 0.3.45**，Apache License 2.0（已打进 jar）
- **`vosk-model-small-cn-0.22`**，Apache License 2.0，由用户在设置中按需下载，不在仓库或 jar 内分发
- **FunASR / Paraformer / FSMN-VAD / CT-Transformer**，Apache License 2.0，由用户按需下载到本机
- 讯飞 WebSocket 协议实现参考了 MIT 许可的 IflySdk，不含任何测试凭据

详见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

## 许可

[MIT](LICENSE)
