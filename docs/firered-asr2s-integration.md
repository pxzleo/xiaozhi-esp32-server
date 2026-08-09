# FireRedVAD 与 FireRedASR2-AED 接入说明

服务端支持按现有 VAD/ASR 模型配置机制选择 FireRedVAD 和
FireRedASR2-AED。两者均为可选模型，不会改变现有 SileroVAD 与
SenseVoiceSmall 默认配置，也不会在依赖或模型缺失时静默回退。

## 安装源码与依赖

在 `main/xiaozhi-server` 目录执行：

```bash
python -m pip install -r requirements-firered.txt
git clone https://github.com/FireRedTeam/FireRedASR2S.git models/FireRedASR2S
git -C models/FireRedASR2S checkout 4e7d9aaf4482a47cec1724807026b9b151926eb5
modelscope download --model xukaituo/FireRedASR2-AED \
  --local_dir models/FireRedASR2S/pretrained_models/FireRedASR2-AED
modelscope download --model xukaituo/FireRedVAD \
  --local_dir models/FireRedASR2S/pretrained_models/FireRedVAD
```

这里固定到已完成接口核对的官方源码提交，避免官方 `main` 后续变更造成接口漂移。
项目当前使用 Python 3.10、PyTorch 2.2.2 和 NumPy 1.26.4，因此不要直接安装
FireRedASR2S 包或执行其 `pyproject.toml` 中的全量依赖升级。服务端通过
`source_dir` 直接加载独立的 `fireredvad`/`fireredasr2` 子包，可选依赖文件只补充
本项目缺少的运行库，并保留项目中已安装的兼容新版 `kaldiio`、`sentencepiece` 和
`soundfile`。部署前仍需用真实音频验证该组合与当前 CUDA 驱动的兼容性。

## 智控台配置

部署 manager-api 数据库迁移后，在“模型配置”中会出现：

- VAD：`FireRedVAD流式语音活动检测`
- ASR：`FireRedASR2-AED语音识别`

保持默认源码和模型目录时无需修改路径。然后在智能体配置中分别选择
`FireRedVAD` 和 `FireRedASR2AED`。

FireRedVAD 的 `min_silence_duration_ms` 以毫秒配置，服务端会按官方 10ms
帧移换算。实机默认使用官方 `min_speech_frame=8`（连续 80ms）和
`min_silence_duration_ms=700`。起点门槛过高会因自然语音中短暂的概率跌落而持续无法
触发；结尾静音更短则容易把自然停顿切成两轮。升级迁移先识别首版 8/500 默认组合，
最终修正为 8/700。修正迁移还会比对上一版迁移时间，只更新由上一版在 60 秒内写入的
20/700 默认组合，不覆盖用户后来主动保存的相同数值。manager-api 会在 Liquibase 完成后、重建服务端
配置缓存前定向失效 FireRedVAD 模型缓存，确保 Redis 中的旧默认值不会遮蔽迁移后的
数据库配置。

FireRedASR2-AED 默认使用 CPU。启用 GPU 前先确认显存余量；`use_gpu=true` 但
CUDA 不可用时服务端会明确拒绝初始化。`use_half=true` 只在 GPU 模式下有意义。

## 简化配置文件模式

不使用 manager-api 时，可以在 `data/.config.yaml` 中覆盖选择：

```yaml
selected_module:
  VAD: FireRedVAD
  ASR: FireRedASR2AED
```

## 验收日志

启动后应出现以下两条初始化日志：

```text
FireRedVAD 初始化完成
FireRedASR2-AED 初始化完成
```

每轮识别在 INFO 日志中记录统一的 `ASR处理结果`、音频时长、墙钟耗时和 RTF；
同时记录 PCM RMS、峰值、直流偏移、削波比例和零采样比例。这些质量指标不改变识别
决策，也不包含原始音频内容。
FireRed 成功识别还会记录模型返回的 RTF。相同 FireRed 配置在同一进程中共享模型，
后续连接的初始化日志应显示 `cache_hit=True`，避免每次连接重新加载 checkpoint。
FireRedVAD 会记录语音起止点；连续 5 秒未触发起点时记录最高模型概率、PCM RMS、阈值
和连续帧门槛，用于区分静音、麦克风音量过低与门槛过严。
为避免旧 checkpoint 残留并叠加占用显存，同一进程不支持热切换到另一组 FireRed AED
参数；修改模型目录、GPU、半精度或解码参数后必须重启 `xiaozhi-server`。
验收时应同时比较端点等待、
短句/歌名识别正确率、首尾截断率以及 P50/P95 总延迟；FireRedVAD 是流式的，
FireRedASR2-AED 仍在一句话结束后做整句识别。

安装完两个真实模型后，在 `main/xiaozhi-server` 执行真实冒烟门禁：

```bash
FIRERED_SOURCE_DIR=models/FireRedASR2S \
FIRERED_ASR_MODEL_DIR=models/FireRedASR2S/pretrained_models/FireRedASR2-AED \
FIRERED_VAD_MODEL_DIR=models/FireRedASR2S/pretrained_models/FireRedVAD/Stream-VAD \
FIRERED_USE_GPU=false \
python -m unittest tests.test_firered_real_smoke
```

该测试直接加载固定版本官方源码和真实 checkpoint，使用官方中文样例依次验证流式
VAD 起止点和 AED 非空转写。缺少环境变量时测试会明确标记为 skipped，不会把 fake
单元测试误报为真实模型验收。
