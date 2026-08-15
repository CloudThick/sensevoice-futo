# SenseVoice × FUTO 语音输入：最小原型

这是一个**实验性 Android 工程**，目标是验证：

- FUTO Keyboard 继续负责键盘；
- 长按空格/系统语音入口切到这个 Voice IME；
- 本地使用 SenseVoice Small INT8 做识别；
- 中文强制 `zh`、英文强制 `en`，避免短句被误判成日语；
- `useInverseTextNormalization = true`；
- 识别结果再做一层非常保守的本地清理：去掉独立的“嗯/呃/额/呣/唔”，合并明显连续重复；
- 不做云端上传，不做 LLM 语义改写。

## 为什么这个结构可行

FUTO 官方 Voice Input 本身同时提供 `android.speech.action.RECOGNIZE_SPEECH` Activity 和一个 `InputMethodService`。这个原型也同时实现这两种入口，因此后续可以测试哪一种和 FUTO 的“系统语音输入”衔接最顺。

sherpa-onnx 官方 Kotlin API直接支持 `OfflineSenseVoiceModelConfig(language=..., useInverseTextNormalization=true)`。

## 构建

1. 用 Android Studio 打开本目录。
2. 让 Android Studio 安装 Android SDK 35（如果提示）。
3. 第一次 Build 时，Gradle 会自动下载官方 `sherpa-onnx-1.13.5.aar`。
4. Build → Build APK(s)。
5. 安装 APK。

## 手机上第一次设置

1. 打开 `SenseVoice Voice Input`。
2. 点“允许麦克风”。
3. 点“下载 SenseVoice 模型”，约 240 MB；下载/解压完成后识别完全离线。
4. 点“打开系统输入法设置”，启用 `SenseVoice Voice Input`。
5. 先用应用内“测试语音识别”验证。
6. 再到 FUTO 的语音设置里尝试选择/使用系统语音输入。

## 当前原型的已知限制

- VAD 目前只是简单 RMS 静音检测，不如 sherpa 官方 Silero VAD 稳定；这是原型阶段刻意简化的部分。
- 文本清理只做保守规则，不会理解“明天下午……不对，是明天上午”这类语义改口。
- 还没有做漂亮 UI、模型下载断点续传、错误恢复和电池优化。
- 尚未在真实 FUTO + Android 设备上完成端到端验证。

## 下一步如果原型能跑通

1. 把简单 RMS VAD 换成 sherpa-onnx 的 Silero VAD。
2. 增加“口头语清理强度”设置。
3. 增加轻量级自我纠正规则，必要时再考虑小型本地 LLM。
4. 处理中文/英文 subtype 与 FUTO 当前语言的自动联动。
