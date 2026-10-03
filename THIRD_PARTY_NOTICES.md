# Third-party notices

This mod embeds the Vosk Java API 0.3.45, Copyright Alpha Cephei Inc., under the Apache License 2.0. Vosk itself requires JNA at runtime; on Minecraft 26.1.2 that dependency is already provided by the game (`net.java.dev.jna:jna`), so it is not bundled here.

The optional `vosk-model-small-cn-0.22` speech model is downloaded directly from Alpha Cephei and is also listed under the Apache License 2.0. The model is not redistributed inside this repository or mod JAR.

- Project: https://github.com/alphacep/vosk-api
- Models: https://alphacephei.com/vosk/models
- License: https://www.apache.org/licenses/LICENSE-2.0

## FunASR local service (optional)

The mod can launch a local speech service built on FunASR. Nothing from the following projects is redistributed: the launcher installs the Python packages from PyPI into a private virtual environment on the user's own machine, and the models are downloaded from ModelScope on demand.

| Component | Role | License |
| --- | --- | --- |
| [FunASR](https://github.com/modelscope/FunASR) | ASR / VAD / punctuation toolkit | Apache License 2.0 |
| [Paraformer](https://modelscope.cn/models/iic/speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch) (`speech_paraformer-large_asr_nat-zh-cn-16k-common-vocab8404-pytorch`) | speech recognition backbone | Apache License 2.0 |
| [FSMN-VAD](https://modelscope.cn/models/iic/speech_fsmn_vad_zh-cn-16k-common-pytorch) (`speech_fsmn_vad_zh-cn-16k-common-pytorch`) | voice activity detection | Apache License 2.0 |
| [CT-Transformer](https://modelscope.cn/models/iic/punc_ct-transformer_zh-cn-common-vocab272727-pytorch) (`punc_ct-transformer_zh-cn-common-vocab272727-pytorch`, alias `ct-punc-c`) | punctuation restoration | Apache License 2.0 |
| [ModelScope](https://github.com/modelscope/modelscope) | model download client | Apache License 2.0 |
| [PyTorch](https://github.com/pytorch/pytorch) | inference runtime | BSD-3-Clause |
| [FastAPI](https://github.com/fastapi/fastapi) / [Uvicorn](https://github.com/encode/uvicorn) | local HTTP endpoint | MIT / BSD-3-Clause |

Optional alternate models offered by the service (`sensevoice`, `paraformer-seaco`) carry the same Apache License 2.0 terms and are likewise downloaded from ModelScope, never redistributed.

## iFLYTEK

The iFLYTEK WebSocket protocol integration was informed by the MIT-licensed IflySdk project by withsalt and the official iFLYTEK streaming IAT documentation. No bundled test credentials are included.

- Reference project: https://github.com/withsalt/IflySdk
- License: https://github.com/withsalt/IflySdk/blob/master/LICENSE
- API documentation: https://www.xfyun.cn/doc/asr/voicedictation/API.html

IflySdk license notice:

MIT License

Copyright (c) 2019 WithSalt

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
