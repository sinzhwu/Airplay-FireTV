# 变更：创建 FireTV AirPlay 音视频接收端应用

## 原因
FireTV 设备原生不支持接收 iOS 设备的 AirPlay 投屏，用户需要一款轻量级应用让 iOS 用户能够将视频通过 AirPlay 协议投放到 FireTV 上播放。

## 变更内容
- **新建完整的 Android 项目**：使用 Kotlin + Jetpack Compose 构建，目标平台为 FireTV（Android TV），最小 SDK 25（兼容 Fire TV Stick 4K）。
- **实现 AirPlay 协议服务端**（参考 PhairPlay 的协议栈设计）：
  - **mDNS/Bonjour 服务广播**：使用 Android 原生 `NsdManager` 注册 `_airplay._tcp` 服务（端口 7000），伪装为 AppleTV5,3 提高兼容性，无需外部 mDNS 库
  - **RTSP 信令服务器**：基于 `ServerSocket` + Kotlin Coroutines (`Dispatchers.IO`) 实现两阶段处理 —— 文本 RTSP 握手阶段 → 二进制 RTP 交织读取阶段，避免 `BufferedReader` 预读问题
  - **RTP over TCP 交织帧接收**：使用 RFC 2326 §10.12 交织格式（`$` + channel + length + payload），通过 `InputStream` 直接读取，支持视频（channel 0/1）和音频（channel 2/3）交织流；单 NALU 和 FU-A 分片重组
  - **H.264 MediaCodec 硬件解码**：直接 `MediaCodec` 解码到 `Surface`，无中间 BufferCopy，附带自定义 SPS bitstream 解析器提取实际分辨率
  - **AAC-LC MediaCodec 硬件音频解码 + AudioTrack 播放**：接收 AAC-LC 编码的音频 RTP 流，使用 `MediaCodec` 硬件解码为 PCM，通过 `AudioTrack` 以 STREAM 模式输出到扬声器；解析 SDP 中的 `config=` 获取 AudioSpecificConfig 作为解码器 csd-0
  - **音视频同步基础**：基于 RTP timestamp 分别转换视频（90kHz）和音频（44.1kHz/48kHz）时间戳，通过 MediaCodec 和 AudioTrack 的 `presentationTimeUs` 实现基础同步
- **前台服务生命周期管理**：`ForegroundService` (`foregroundServiceType="connectedDevice"`) 承载协议栈，确保应用在后台时 AirPlay 服务不被系统杀死
- **Jetpack Compose TV UI**：
  - 闲置状态：显示 AirPlay 服务名称和连接等待提示
  - 投屏状态：`SurfaceView` 全屏覆盖渲染解码视频，播放控制 overlay 支持遥控器操作
- **设置持久化**：使用 `DataStore Preferences` 类型安全存储设备名称等配置
- **FireTV 适配**：
  - AndroidManifest.xml 声明 `LEANBACK_LAUNCHER`、`leanback` required、`touchscreen` not required、TV `uses-feature` 类型声明
  - D-Pad / 遥控器焦点导航和按键映射
  - SettingsScreen 支持遥控器 **Back 键** 关闭面板（`BackHandler`），TextField 配置 TV 软键盘友好输入
  - TV Banner 品牌标识优化（在 320x180dp banner 上添加应用名称和 Logo）
  - 横屏锁定、`configChanges` 防重建
- **最小 SDK**：`minSdk = 25`（兼容 Fire TV Stick 4K），启用 Core Library Desugaring 支持 Java 8+ API

## 影响
- **受影响的规范**：AirPlay 1/2 视频投屏接收、FireTV 应用分发
- **受影响的代码**：
  - `app/build.gradle.kts`: FireTV 编译配置、DataStore、Desugaring 依赖
  - `app/src/main/java/com/airplay/firetv/AirPlayApplication.kt`: Application 入口、全局协程作用域、Timber 日志、Bouncy Castle Provider 注册
  - `app/src/main/java/com/airplay/firetv/util/NetworkUtils.kt`: MAC 地址 / IP / UUID 查询工具
  - `app/src/main/java/com/airplay/firetv/airplay/MdnsService.kt`: NsdManager mDNS 注册
  - `app/src/main/java/com/airplay/firetv/airplay/RtspHandler.kt`: RTSP 服务器 + 两阶段处理
  - `app/src/main/java/com/airplay/firetv/airplay/RtpInterleaved.kt`: RTP over TCP 交织帧解析
  - `app/src/main/java/com/airplay/firetv/airplay/VideoDecoder.kt`: MediaCodec 硬解 + 自定义 SPS 解析器
  - `app/src/main/java/com/airplay/firetv/airplay/SdpParser.kt`: SDP 解析（SPS/PPS、AES 密钥提取）
  - `app/src/main/java/com/airplay/firetv/airplay/AirPlayCrypto.kt`: RSA 私钥解密 + AES-CTR 加解密
  - `app/src/main/java/com/airplay/firetv/airplay/TimingHandler.kt`: NTP 时间同步服务器（端口 6002）
  - `app/src/main/java/com/airplay/firetv/airplay/AirPlayReceiver.kt`: 协议栈顶层协调器、SupervisorJob 生命周期管理（音视频组件统一协调）
  - **新增** `app/src/main/java/com/airplay/firetv/airplay/AudioDecoder.kt`: MediaCodec AAC-LC 硬件音频解码器（含 AAC-hbr AU-headers 剥离）
  - **新增** `app/src/main/java/com/airplay/firetv/airplay/AudioPlayer.kt`: AudioTrack PCM 音频播放器（STREAM 模式、MEDIA usage）
  - `app/src/main/java/com/airplay/firetv/service/PhairPlayService.kt`: ForegroundService 协议生命周期
  - `app/src/main/java/com/airplay/firetv/service/ServiceController.kt`: Service 启动 / 停止 / 重启 / 绑定封装
  - `app/src/main/java/com/airplay/firetv/service/BootReceiver.kt`: 开机自启广播接收器
  - `app/src/main/java/com/airplay/firetv/settings/`: DataStore 设置持久化
  - `app/src/main/java/com/airplay/firetv/ui/`: Jetpack Compose TV UI + SurfaceView 全屏覆盖
  - `app/src/main/java/com/airplay/firetv/ui/screens/SettingsScreen.kt`: 添加 BackHandler 支持遥控器 Back 键关闭，TextField 键盘选项优化
  - `app/src/main/res/drawable/banner.xml`: TV Banner 品牌标识（应用名称 + Logo）
  - `app/src/main/AndroidManifest.xml`: TV 应用入口、前台服务、权限声明、TV `uses-feature` 声明
