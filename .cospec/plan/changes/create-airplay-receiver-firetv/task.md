## 实施

- [ ] 1.1 初始化 Android 项目并配置 FireTV 构建设置
  【目标对象】`build.gradle.kts` (project-level)、`settings.gradle.kts`、`gradle.properties`、`gradle/wrapper/gradle-wrapper.properties`、`app/src/main/java/com/airplay/firetv/AirPlayApplication.kt`
  【修改目的】建立支持 Kotlin DSL 的 Android 项目骨架，配置兼容 FireTV 的 SDK 和依赖管理；创建 Application 入口类供全局组件初始化
  【修改方式】使用 Android Gradle Plugin 和 Kotlin 插件创建根项目配置；创建继承 `android.app.Application` 的 `AirPlayApplication` 类
  【相关依赖】Android Gradle Plugin 8.6.1、Kotlin 1.9.23、Gradle Wrapper 8.4+
  【修改内容】
    - 创建 `build.gradle.kts`，应用 Android application 插件，配置 Kotlin Android 插件
    - 创建 `settings.gradle.kts`，定义项目名称和包含模块
    - 创建 `gradle.properties`，配置 JVM 内存参数和 Android 非传递性 R 类
    - 生成 `gradlew` wrapper 文件（版本 8.4+）
    - 创建 `AirPlayApplication.kt`：
      - 继承 `Application`
      - 提供 `lateinit var settingsRepository: SettingsRepository` 实例（在 `onCreate()` 中初始化）
      - 提供 `val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)` 供全局协程使用
      - 在 `onCreate()` 中初始化 Timber 日志
      - 在 `onCreate()` 中注册 Bouncy Castle Provider：`Security.addProvider(BouncyCastleProvider())`，确保 AES-CTR 和 RSA 解密 Cipher 初始化成功

- [ ] 1.2 配置 `app/build.gradle.kts`、AndroidManifest.xml 及 FireTV 专用资源
  【目标对象】`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、`app/src/main/res/values/themes.xml`、`app/src/main/res/drawable/`、`app/src/main/res/mipmap-*`
  【修改目的】配置 FireTV 应用编译参数、TV 入口声明、依赖库，并声明网络和前台服务权限
  【修改方式】在 app 模块中配置 build.gradle.kts 和清单文件
  【相关依赖】`androidx.core:core-ktx:1.13.1`、`androidx.activity:activity-compose:1.9.0`、`androidx.compose.ui:ui:1.6.8`、`androidx.compose.material3:material3:1.2.1`、`androidx.tv:tv-foundation:1.0.0-alpha10`、`androidx.tv:tv-material:1.0.0-alpha10`、`androidx.datastore:datastore-preferences:1.1.1`、`org.bouncycastle:bcprov-jdk18on:1.78.1`、`com.jakewharton.timber:timber:5.0.1`、`androidx.core:core-library-desugaring:2.1.3`
  【修改内容】
    - `build.gradle.kts`：
      - `compileSdk = 35`，`targetSdk = 35`，`minSdk = 25`
      - `applicationId = "com.airplay.firetv"`
      - 启用 `coreLibraryDesugaringEnabled = true`
      - 启用 Jetpack Compose：`composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }`，`buildFeatures { compose = true }`
      - 添加依赖：core-ktx、activity-compose、Compose UI/BOM、TV Foundation/Material、DataStore Preferences、Bouncy Castle（AES-CTR 解密）、Timber（日志）、Core Library Desugaring
    - `AndroidManifest.xml`：
      - 声明 `android.intent.category.LEANBACK_LAUNCHER` 使应用出现在 FireTV 主屏
      - `uses-feature android:name="android.software.leanback" android:required="true"`
      - `uses-feature android:name="android.hardware.touchscreen" android:required="false"`
      - `uses-feature android:name="android.hardware.type.television" android:required="false"`（显式声明 TV 类型，帮助 Amazon Appstore 正确分类）
      - 权限：`INTERNET`、`ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE`、`CHANGE_WIFI_MULTICAST_STATE`、`FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_CONNECTED_DEVICE`、`RECEIVE_BOOT_COMPLETED`
      - 注册 `PhairPlayService`（`android:foregroundServiceType="connectedDevice"`）和 `BootReceiver`（`RECEIVE_BOOT_COMPLETED`）
      - 声明 `AirPlayApplication` 为 `android:name`
      - `android:screenOrientation="landscape"`，`android:configChanges="keyboard|keyboardHidden|navigation|orientation|screenSize"`
      - `<application>` 标签声明 `android:banner="@drawable/banner"`
    - 创建 TV 主题（无 ActionBar 全屏主题）
    - 添加 TV Banner（尺寸 **320dp x 180dp**，对应 xhdpi 960x540 px）和 App Icon (`ic_launcher`)
    - **Banner 品牌标识优化**：在纯色背景上叠加应用名称 "FireTV AirPlay" 文字和 Logo 图标，提升 Amazon Appstore 展示效果

- [ ] 1.3 创建 DataStore 设置持久化模块
  【目标对象】`app/src/main/java/com/airplay/firetv/settings/AppSettings.kt`、`SettingsRepository.kt`
  【修改目的】使用 DataStore Preferences 类型安全存储用户配置（设备显示名称等），支持开机自启后恢复配置
  【修改方式】定义不可变 data class + Repository 模式，提供 `update { it.copy(...) }` API
  【相关依赖】`androidx.datastore:datastore-preferences:1.1.1`、Kotlin Coroutines、AirPlayApplication
  【修改内容】
    - 创建 `AppSettings` data class：字段 `displayName: String`（默认值 "FireTV AirPlay"）、`autoStart: Boolean`（默认值 true）
    - 创建 `SettingsRepository`：
      - 使用 `DataStore<Preferences>` 读写配置
      - 暴露 `val settings: Flow<AppSettings>` 供 UI 订阅
      - 提供 `suspend fun update(transform: (AppSettings) -> AppSettings)` 方法，内部使用 `dataStore.updateData { ... }`
    - 在 `AirPlayApplication.onCreate()` 中初始化 Repository 单例

- [ ] 1.4 创建网络工具类 NetworkUtils
  【目标对象】`app/src/main/java/com/airplay/firetv/util/NetworkUtils.kt`
  【修改目的】为 mDNS 服务广播提供设备 MAC 地址和本机 IP 地址查询
  【修改方式】创建 object 类，使用 Android WifiManager 和网络接口 API 获取信息
  【相关依赖】`android.net.wifi.WifiManager`、`java.net.NetworkInterface`
  【修改内容】
    - 创建 `NetworkUtils` object：
      - `getMacAddress(context): String`：通过 `WifiManager.connectionInfo.macAddress` 获取 MAC 地址；若返回 "02:00:00:00:00:00"（Android 6+ 限制），则遍历 `NetworkInterface.getNetworkInterfaces()` 找到 `wlan0` 或首个非回环接口，读取硬件地址并格式化为大写十六进制字符串（如 `A1B2C3D4E5F6`）
      - `getLocalIpAddress(context): String`：获取当前 WiFi 连接的 IPv4 地址
      - `generatePersistentUuid(macAddress: String): UUID`：基于 MAC 地址生成稳定的 UUID 作为 `pi`（persistent identifier）
    - 处理权限不足和网络不可用的边界情况，返回合理的 fallback 值

- [ ] 2.1 实现 mDNS/Bonjour 服务广播模块（NsdManager）
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/MdnsService.kt`
  【修改目的】让 iOS 设备能在局域网中发现此 FireTV 作为 AirPlay 接收端；使用 Android 原生 NsdManager 无需外部库
  【修改方式】使用 `android.net.nsd.NsdManager` 注册 `_airplay._tcp` 和 `_raop._tcp` 服务
  【相关依赖】`WifiManager.MulticastLock`、SettingsRepository（获取 displayName）、NetworkUtils（获取 MAC 地址和 persistent UUID）
  【修改内容】
    - 创建 `MdnsService` 类，持有 `NsdManager` 和 `WifiManager.MulticastLock` 引用
    - 在 `start(displayName: String)` 中：
      - 获取并持有 `WifiManager.createMulticastLock("airplay_mdns")`
      - 构建 `NsdServiceInfo`：
        - 服务类型 `_airplay._tcp`，端口 `7000`
        - TXT 记录：`deviceid`（MAC 地址）、`features="0x5A7FFFF7,0x1E"`、`model="AppleTV5,3"`、`srcvers="220.68"`、`vv="2"`、`flags="0x4"`、`pi`（持久 UUID）
      - 注册 RAOP 服务：服务名格式为 `<MAC_HEX>@<DisplayName>`，类型 `_raop._tcp`，端口 `7000`
      - 实现 `NsdManager.RegistrationListener`，处理名称冲突（`onServiceRegistered` 回调可能返回改名后的名称）
    - 提供 `stop()` 方法：注销两个服务，释放 MulticastLock
    - 暴露连接状态回调或 StateFlow 供 UI 层观察

- [ ] 2.2 实现 RTSP 信令服务器模块（两阶段处理）
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/RtspHandler.kt`、`RtspMessages.kt`、`RtspSession.kt`
  【修改目的】处理 iOS 设备发来的 AirPlay RTSP 控制命令；采用两阶段处理避免 BufferedReader 预读二进制 RTP 数据；音视频统一使用 TCP interleaved 传输
  【修改方式】基于 `ServerSocket` + `CoroutineScope(Dispatchers.IO)` 实现，每个客户端在独立协程中处理；单客户端限制（已有连接时返回 503）
  【相关依赖】`kotlinx.coroutines`、AirPlayReceiver 协调器回调接口、RtpInterleaved、SdpParser、AirPlayCrypto
  【修改内容】
    - 创建 `RtspMessages` sealed class / data class：定义 `RtspRequest`（method、uri、headers、body）、`RtspResponse`（statusCode、headers、body）
    - 创建 `RtspSession`：
      - 持有裸 `Socket.inputStream` 和 `outputStream`（不用 `BufferedReader`）
      - 在协程中运行（`Dispatchers.IO`），非 Runnable
      - **Phase 1（文本 RTSP 握手）**：在 `setupCount < 2` 时，逐行读取并解析 RTSP 请求，根据 method 路由处理：
        - `OPTIONS`：返回支持的方法列表
        - `ANNOUNCE`：解析 SDP body，调用 `SdpParser.parse()` 提取 video SPS/PPS、audio 参数（`AudioParams`）、AES 密钥（`rsaaeskey`/`aesiv`）；通过回调通知 AirPlayReceiver
        - `SETUP`：区分 video（interleaved TCP，返回 `Transport: RTP/AVP/TCP;interleaved=0-1`）和 **audio（也返回 interleaved TCP，`Transport: RTP/AVP/TCP;interleaved=2-3`）**；`setupCount` 递增
        - `RECORD`：通知上层开始流传输，返回 `Range: npt=0-`
        - `TEARDOWN`：结束会话，释放资源
        - `GET_PARAMETER` / `SET_PARAMETER` / `PAUSE`
        - `FLUSH`
      - **Phase 2（二进制 RTP 交织读取）**：两个 SETUP 完成后，调用 `RtpInterleaved.readLoop(inputStream, videoCallback, audioCallback)` 持续读取 `$` 标记的交织帧（channel 0=video RTP, 1=video RTCP, 2=audio RTP, 3=audio RTCP）
      - RTSP 响应严格回显 `CSeq`
    - 创建 `RtspHandler`：
      - 在端口 `7000` 监听 `ServerSocket`
      - 使用 `SupervisorJob()` 管理协程作用域，子组件崩溃不影响整体
      - 新连接到达时，检查是否已有活跃连接，若有则直接返回 `503 Service Unavailable`
      - 启动 `RtspSession` 协程处理连接

- [ ] 2.3 实现 RTP over TCP 交织帧解析模块
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/RtpInterleaved.kt`、`RtpPacket.kt`
  【修改目的】解析 RFC 2326 交织格式的 RTP over TCP 帧，提取 H.264 NALU 单元和 AAC 音频帧
  【修改方式】从裸 `InputStream` 读取 `$` 标记帧，解析 RTP header，处理视频单 NALU / FU-A 分片和音频 AAC-hbr 封装
  【相关依赖】VideoDecoder（NALU 输出目标）、AudioDecoder（AAC 输出目标）
  【修改内容】
    - 创建 `RtpPacket` 数据类：解析 RTP 固定头 12 bytes（version、padding、extension、CSRC count、marker bit、payload type、sequence number、timestamp、SSRC），保存 payload 字节数组
    - 创建 `RtpInterleaved` object：
      - `readLoop(inputStream, videoCallback, audioCallback)`：
        - 循环读取：先读 1 byte 的 `$` 标记，再读 1 byte channel id（0=video RTP, 1=video RTCP, 2=audio RTP, 3=audio RTCP），再读 2 bytes BE length
        - 读取 `length` 字节的 payload，封装为 `RtpPacket`
        - channel 0 的视频帧送入 `processVideoRtpFrame()`；channel 2 的音频帧送入 `audioCallback`
        - channel 1 和 3（RTCP）可忽略或可选处理
      - `processVideoRtpFrame(rtpPacket, callback)`：
        - 剥离 12-byte RTP header，获取 NAL type：`payload[0] & 0x1F`
        - **NAL type 1-23（单 NALU）**：直接送入 callback，前面拼接 `0x00 00 00 01` start code
        - **NAL type 28（FU-A 分片）**：解析 `FU indicator` 和 `FU header`，根据 Start/End 标志重组完整 NAL。维护重组缓冲区，Start 时新建缓冲区（NAL header = (FU indicator & 0xE0) | (FU header & 0x1F)），中间分片追加 payload，End 时输出完整 NAL + start code。单帧安全上限 2MB，超限丢弃
        - 时间戳转换：RTP 90kHz 时钟 → 微秒 `ptsUs = rtpTimestamp * 1_000_000L / 90_000L`
      - 音频帧直接通过 `audioCallback(rtpPacket.payload, ptsUs)` 输出，AU-headers 剥离在 AudioDecoder 中处理
    - 定义回调接口：`fun onVideoNal(nalBytes: ByteArray, ptsUs: Long)`、`fun onAudioPacket(audioBytes: ByteArray, ptsUs: Long)`

- [ ] 2.4 实现 H.264 MediaCodec 硬件解码与自定义 SPS 解析器
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/VideoDecoder.kt`
  【修改目的】使用 Android MediaCodec 直接硬件解码 H.264 到 Surface，实现低延迟视频渲染；自定义 SPS 解析器精确提取视频分辨率
  【修改方式】`MediaCodec.createDecoderByType(MIMETYPE_VIDEO_AVC)`，配置 `COLOR_FormatSurface`，`configure(format, outputSurface, null, 0)`
  【相关依赖】`android.media.MediaCodec`、`android.media.MediaFormat`、RtpInterleaved 的 NALU 输出
  【修改内容】
    - 创建 `VideoDecoder` 类：
      - `initialize(surface: Surface, spsBytes: ByteArray, ppsBytes: ByteArray)`：
        - 调用 `parseSpsResolution(spsBytes)` 获取实际宽高
        - 创建 `MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)`
        - 设置 `csd-0`（SPS ByteBuffer）、`csd-1`（PPS ByteBuffer）、`KEY_COLOR_FORMAT` 为 `COLOR_FormatSurface`
        - 创建并配置 MediaCodec，传入 `outputSurface`
        - 启动 `decodeLoop()` 协程
      - `decodeLoop()`：
        - 使用 `MediaCodec` 的 `dequeueInputBuffer` / `queueInputBuffer` 喂入 NALU 数据（带 `0x00000001` start code）
        - 使用 `dequeueOutputBuffer` / `releaseOutputBuffer` 输出到 Surface
        - 根据 `ptsUs` 时间戳传入 `queueInputBuffer` 的 `presentationTimeUs` 参数
        - 处理 `INFO_OUTPUT_FORMAT_CHANGED` 和 `INFO_TRY_AGAIN_LATER`
        - 使用 `MediaCodec.INFO_TRY_AGAIN_LATER` 进行非阻塞轮询，超时 10ms 继续循环
      - `release()`：停止并释放 MediaCodec，取消 decodeLoop 协程
    - 在 `VideoDecoder.kt` 中实现自定义 SPS 解析器作为私有内部类/伴生对象 `SpsBitReader`：
      - `parseSpsResolution(sps: ByteArray): Pair<Int, Int>?`：
        - 创建 `SpsBitReader` 逐 bit 读取 SPS NAL（跳过 `0x00000001` start code）
        - 实现 `readUe()`（无符号指数哥伦布解码）、`readSe()`（有符号指数哥伦布解码）、`readBits(n: Int)`
        - 解析 `profile_idc`、`constraint_set` 标志、`level_idc`、`seq_parameter_set_id`
        - 若 `profile_idc` 为 100/110/122/244/44/83/86，解析 `chroma_format_idc`（支持 4:2:0/4:2:2/4:4:4）及可能的 `separate_colour_plane_flag`、`bit_depth_luma_minus8`、`bit_depth_chroma_minus8`
        - 解析 `log2_max_frame_num_minus4`、`pic_order_cnt_type` 及其相关字段
        - 解析 `pic_width_in_mbs_minus1`、`pic_height_in_map_units_minus1`
        - 处理 `frame_mbs_only_flag`（为 false 时 `pic_height_in_map_units_minus1` 需乘 2）、`frame_cropping_flag`，计算裁剪后实际分辨率
        - 返回 `(width, height)`，解析失败返回 null

- [ ] 2.5 实现 SDP 解析器
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/SdpParser.kt`
  【修改目的】从 RTSP ANNOUNCE 的 SDP body 中提取视频 SPS/PPS、音频 AAC 参数和 AES 加密密钥信息
  【修改方式】字符串逐行解析，正则或 split 提取 `a=fmtp:`、`a=rtpmap:` 属性
  【相关依赖】RtspHandler 的 ANNOUNCE 处理逻辑、AirPlayCrypto（RSA 解密 AES key）、AudioDecoder
  【修改内容】
    - 创建 `SdpParser` object / class：
      - `parse(sdpBody: String): SdpInfo`
      - 提取 `m=video` 行的 payload type
      - 提取 `a=rtpmap` 中的编码格式和时钟频率
      - 提取 `a=fmtp` 中的 `sprop-parameter-sets`（逗号分隔的 base64 字符串，解码为 SPS + PPS 字节数组）
      - 提取 `a=rsaaeskey`（Base64 编码的 RSA 加密 AES key，需调用 AirPlayCrypto 解密）和 `a=aesiv`（Base64 编码的 AES IV）
      - **提取 `m=audio` 行的 AAC 参数**：
        - 从 `a=rtpmap` 解析采样率和通道数（如 `mpeg4-generic/44100/2`）
        - 从 `a=fmtp` 解析 `config=` hex 字符串，解码为 AudioSpecificConfig 字节数组（如 `config=1210` → 2 字节 `[0x12, 0x10]`）
        - 解析 `mode=AAC-hbr` 确认封装格式
      - 新增 `AudioParams` data class：字段 `sampleRate: Int`（采样率）、`channelCount: Int`（通道数）、`audioSpecificConfig: ByteArray`（csd-0 给 MediaCodec）
    - 创建 `SdpInfo` data class，包含 `sps: ByteArray`、`pps: ByteArray`、`aesKey: ByteArray?`、`aesIv: ByteArray?`、`audioParams: AudioParams?`
    - 注意：AES key/iv 为 null 时兼容未加密流，代码路径需显式处理；audioParams 为 null 时兼容纯视频流
    - 错误处理：SDP 格式不合法或缺少必要字段时抛出 `SdpParseException`

- [ ] 2.6 实现 AirPlay RSA/AES 解密工具模块（AirPlayCrypto）
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/AirPlayCrypto.kt`
  【修改目的】AirPlay 协议使用固定 RSA 密钥对加密 AES 流密钥，需要 RSA 私钥解密获取 AES key，供后续 AES-CTR 音视频解密
  【修改方式】使用 Bouncy Castle 库加载 AirPlay 固定 RSA 私钥（PEM 格式），执行 RSA 解密；提供 AES-CTR 解密流封装
  【相关依赖】`org.bouncycastle:bcprov-jdk18on`、SdpParser、RtpInterleaved
  【修改内容】
    - 创建 `AirPlayCrypto` object：
      - 内嵌 AirPlay 协议固定 RSA 私钥（PEM 字符串常量，PhairPlay 项目中的已知密钥）
      - `decryptAesKey(encryptedKeyBytes: ByteArray): ByteArray`：
        - 使用 Bouncy Castle `PEMParser` 解析私钥
        - 初始化 `RSAEngine` + `PKCS1Encoding`，私钥解密 `encryptedKeyBytes`
        - 返回 16 字节 AES key
      - `createAesCtrCipher(aesKey: ByteArray, aesIv: ByteArray)`：创建 AES/CTR/NoPadding Cipher 实例，用于后续音视频 payload 解密（RtpInterleaved 或 AudioPlayer 调用）
    - 所有加密操作在 `Dispatchers.Default` 协程中执行，避免阻塞 IO 线程
    - 错误处理：解密失败时抛出 `CryptoException`，上层记录日志并尝试无解密路径（兼容非加密流）

- [ ] 2.7 实现 NTP/Timing 处理模块（TimingHandler）
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/TimingHandler.kt`
  【修改目的】AirPlay 协议要求接收端在端口 6002 监听 NTP 时间同步请求，确保发送端和接收端的时钟同步，实现音视频同步播放
  【修改方式】基于 `DatagramSocket` + `CoroutineScope(Dispatchers.IO)` 实现简易 NTP 服务器，回复 NTP 时间戳
  【相关依赖】`kotlinx.coroutines`、AirPlayReceiver
  【修改内容】
    - 创建 `TimingHandler` 类：
      - 在 UDP 端口 `6002` 监听 `DatagramSocket`
      - 在协程中循环接收 NTP 请求数据包（64 bytes，SNTP 格式）
      - 解析客户端发送的 originate timestamp（T1）
      - 构建 NTP 回复包：填充 receive timestamp（T2）= 当前系统时间 NTP 格式，transmit timestamp（T3）= 当前系统时间 NTP 格式
      - 将回复包发送回客户端地址
      - 提供 `start()` 和 `stop()` 方法，stop 时关闭 socket 并取消协程
    - 使用 `SupervisorJob()` 确保 TimingHandler 崩溃不影响其他协议组件
    - 边界处理：socket 创建失败（端口被占用）时记录错误并上报到 AirPlayReceiver 状态机
- [ ] 3.1 实现顶层 AirPlay 协议协调器（AirPlayReceiver）
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/AirPlayReceiver.kt`
  【修改目的】作为协议栈顶层协调器，管理 mDNS、RTSP、Timing、VideoDecoder、AudioDecoder、AudioPlayer 各子组件的生命周期和错误恢复
  【修改方式】使用 `SupervisorJob()` + `CoroutineScope` 封装所有子组件，任一子组件崩溃后自动重启
  【相关依赖】MdnsService、RtspHandler、TimingHandler、VideoDecoder、AudioDecoder、AudioPlayer
  【修改内容】
    - 创建 `AirPlayReceiver` 类：
      - 内部使用 `SupervisorJob()` 创建协程作用域，确保子组件独立失败隔离
      - `start(settings: AppSettings, surface: Surface?)` 初始化顺序：
        - 若 `surface` 为 null，仅启动 TimingHandler（端口 6002）→ RtspHandler（端口 7000）→ MdnsService（广播发现）
        - 若 `surface` 已就绪，同时准备好 VideoDecoder 的初始化上下文
      - 提供 `setOutputSurface(surface: Surface?)` 方法：
        - 当 Activity 的 Surface 就绪时（`SurfaceHolder.Callback.surfaceCreated()`）调用此方法
        - 若已收到 ANNOUNCE（SPS/PPS 已缓存），立即初始化 VideoDecoder 并传入 surface
        - 若尚未收到 ANNOUNCE，仅缓存 surface，等待后续 ANNOUNCE 到达时自动初始化
        - 当 surface 变为 null（如 Activity 销毁）时，释放当前 VideoDecoder，停止解码但保持协议栈运行
      - 各子组件通过回调接口与 Receiver 通信：
        - RtspHandler ANNOUNCE 回调 → 调用 SdpParser 解析 SDP → 若 surface 已就绪，初始化 VideoDecoder（传入 SPS/PPS + Surface）；**若 audioParams 存在，初始化 AudioDecoder 和 AudioPlayer**
        - RtspHandler RECORD 回调 → 通知 Decoder 开始解码，更新状态为 Streaming
        - RtspHandler TEARDOWN 回调 → 释放 VideoDecoder、AudioDecoder、AudioPlayer，停止流，更新状态为 Connected/Idle
        - RtpInterleaved 视频 NALU 回调 → 喂入 VideoDecoder
        - **RtpInterleaved 音频 RTP 回调 → 喂入 AudioDecoder → AudioPlayer 播放 PCM**
        - TimingHandler 错误回调 → 记录日志，不影响整体（SupervisorJob 隔离）
      - `stop()`：按相反顺序停止各组件（MdnsService → RtspHandler → TimingHandler → VideoDecoder → AudioDecoder → AudioPlayer），释放所有资源
      - 暴露 `connectionState: StateFlow<ConnectionState>` 供 UI 观察（Idle / Discovering / Connected / Streaming / Error）
    - 创建 `ConnectionState` sealed class：`Idle`、`Discovering`、`Connected`、`Streaming`、`Error(val message: String)`
    - 创建 `ActiveConnection` data class：记录客户端 IP、会话开始时间、当前播放状态
    - 错误恢复：当 RtspHandler 或 MdnsService 异常终止时，SupervisorJob 确保其他组件继续运行；Receiver 在 `launch` 中捕获异常并尝试在 3 秒后自动重启失败组件（最多重试 3 次，之后进入 Error 状态）
    - 错误恢复：当 RtspHandler 或 MdnsService 异常终止时，SupervisorJob 确保其他组件继续运行；Receiver 在 `launch` 中捕获异常并尝试在 3 秒后自动重启失败组件（最多重试 3 次，之后进入 Error 状态）

- [ ] 3.2 实现前台服务（PhairPlayService）与启动控制
  【目标对象】`app/src/main/java/com/airplay/firetv/service/PhairPlayService.kt`、`ServiceController.kt`、`BootReceiver.kt`
  【修改目的】将 AirPlay 协议栈运行在 Android ForegroundService 中，确保应用在后台或 TV 主屏时服务不被杀死；支持开机自启
  【修改方式】继承 `Service`，在 `onCreate` 中启动协议栈，绑定通知保持前台状态；ServiceController 提供 start/stop/restart API；设计 LocalBinder 供 Activity 绑定通信
  【相关依赖】AirPlayReceiver、SettingsRepository、Android 通知系统、BOOT_COMPLETED 权限
  【修改内容】
    - 创建 `PhairPlayService`（`android:foregroundServiceType="connectedDevice"`）：
      - 内部创建 `LocalBinder` 内部类：继承 `Binder()`，提供 `fun getService(): PhairPlayService` 和 `fun getAirPlayReceiver(): AirPlayReceiver` 供绑定客户端调用
      - `onBind(intent): IBinder`：返回 `LocalBinder` 实例
      - `onCreate()`：从 Application 获取 SettingsRepository 读取配置，初始化 `AirPlayReceiver`
      - `onStartCommand()`：根据 intent action（START / STOP）启动或停止协议栈
      - 启动时创建前台通知（NotificationChannel + Notification），显示服务名称和状态
      - `onDestroy()`：停止 AirPlayReceiver，释放资源
      - 暴露 `serviceState: StateFlow<ServiceState>` 供 UI 层订阅
    - 创建 `ServiceController` object：
      - `start(context)`：构建显式 Intent（action = START）调用 `context.startForegroundService(intent)`
      - `stop(context)`：发送 STOP action Intent
      - `restart(context)`：先 stop 后 start
      - `bindService(context, conn: ServiceConnection)`：构建显式 Intent 调用 `context.bindService(intent, conn, Context.BIND_AUTO_CREATE)`
    - 创建 `BootReceiver`（`BroadcastReceiver`）：
      - 接收 `BOOT_COMPLETED` 广播
      - 读取 SettingsRepository 的 `autoStart` 设置，若为 true 则调用 `ServiceController.start()`
      - 使用 `goAsync()` 确保异步 I/O 完成，但注意 PendingResult 10 秒限制，DataStore 读取需快速完成
    - 创建 `ServiceState` sealed class：`Stopped`、`Starting`、`Running`、`Stopping`、`Error(val reason: String)`

- [ ] 4.1 实现 Jetpack Compose TV 主界面（闲置等待 + 全屏视频播放）
  【目标对象】`app/src/main/java/com/airplay/firetv/ui/MainActivity.kt`、`screens/WaitingScreen.kt`、`screens/StreamingScreen.kt`、`viewmodel/StreamingViewModel.kt`
  【修改目的】提供 FireTV 适配的 UI：闲置时显示设备名称和 AirPlay 等待提示，投屏时全屏渲染视频
  【修改方式】使用 Jetpack Compose + `androidx.tv.material3` 构建 TV 适配界面；SurfaceView 通过 `AndroidView` 嵌入 Compose；通过 ServiceConnection 绑定 PhairPlayService 获取 AirPlayReceiver 引用
  【相关依赖】AirPlayReceiver 的 connectionState、ServiceController、SettingsRepository、VideoDecoder 的 Surface、PhairPlayService.LocalBinder
  【修改内容】
    - `MainActivity.kt`：
      - 在 `AndroidManifest.xml` 中声明 `android:launchMode="singleTask"`，确保从 Fire TV 主屏反复进入不会创建多个 Activity 实例
      - `setContent { AirPlayApp() }`，使用 TV Material3 主题（`MaterialTheme` + TV 适配颜色/排版）
      - `WindowCompat.setDecorFitsSystemWindows(window, false)` 实现全屏
      - 创建 `ServiceConnection` 实现类：在 `onServiceConnected()` 中通过 `LocalBinder.getAirPlayReceiver()` 获取 Receiver 引用，收集 `connectionState` Flow
      - 在 `onStart()` 中调用 `ServiceController.bindService()` 绑定到 PhairPlayService；在 `onStop()` 中 `unbindService()`
      - 若服务未运行，调用 `ServiceController.start()` 确保协议栈启动
      - 监听 `connectionState`，状态为 `Streaming` 时显示 StreamingScreen，否则显示 WaitingScreen
      - 遥控器按键处理：在 Activity `onKeyDown` 或 Compose `Modifier.onKeyEvent` 中拦截 `KEYCODE_DPAD_CENTER`（播放/暂停）、`KEYCODE_DPAD_LEFT/RIGHT`（快退/快进）、`KEYCODE_MEDIA_PLAY_PAUSE`、`KEYCODE_MEDIA_FAST_FORWARD`、`KEYCODE_MEDIA_REWIND`、`KEYCODE_BACK`（返回等待界面）
    - `WaitingScreen.kt`：
      - 全屏居中显示：应用 Logo、当前 AirPlay 服务名称（来自 SettingsRepository）
      - 提示文字："等待 AirPlay 连接..." / "从 iPhone 或 iPad 的控制中心选择此设备进行投屏"
      - 底部显示当前网络状态（IP 地址，来自 NetworkUtils）
      - 使用 TV 适配的大字体和高对比度颜色
    - `StreamingScreen.kt`：
      - 使用 `AndroidView(factory = { SurfaceView(context) })` 创建 SurfaceView，获取其 `SurfaceHolder`
      - 在 `SurfaceHolder.Callback.surfaceCreated()` 中将 `Surface` 传递给 `AirPlayReceiver`（通过 `receiver.setSurface(surface)` 或重新启动协议栈）
      - Surface 全屏填充，无边距（`Modifier.fillMaxSize()`）
      - 播放控制 overlay（可显示/隐藏）：底部进度条、播放/暂停按钮、当前时间/总时长
      - Overlay 支持遥控器 D-Pad 导航，焦点元素有明确高亮效果（`Modifier.border` 或 `MaterialTheme.colorScheme.primary` 背景）
      - 控制栏自动隐藏（3秒无操作后淡出），按任意遥控器键重新显示
    - `StreamingViewModel.kt`：
      - 使用 `ViewModel` 管理 UI 状态
      - 收集 `connectionState` 和 `playbackInfo`
      - 处理用户按键事件转发到 AirPlayReceiver（如 pause、seek、stop）
      - 管理 overlay 显示/隐藏的计时器逻辑（3秒自动隐藏）

- [ ] 4.2 创建设置界面（SettingsScreen）
  【目标对象】`app/src/main/java/com/airplay/firetv/ui/screens/SettingsScreen.kt`
  【修改目的】允许用户配置 AirPlay 设备名称、开关机自启等设置；支持遥控器 Back 键关闭和 TV 软键盘输入
  【修改方式】Jetpack Compose TV 列表布局，使用 `LazyColumn` + TV 适配的 ListItem；添加 `BackHandler` 拦截系统 Back 事件
  【相关依赖】SettingsRepository、ServiceController（重启服务使配置生效）
  【修改内容】
    - 创建设置列表项：
      - "设备名称"：点击弹出文本输入对话框（或 Leanback 风格的自定义对话框），输入新名称后保存到 DataStore
      - "开机自启"：Toggle 开关，保存到 DataStore
      - "重启服务"：点击后调用 ServiceController.restart() 使名称变更生效
      - "版本信息"：显示应用版本号
    - 每项设置使用 TV 适配的焦点样式，选中/聚焦时有明显的缩放或边框高亮效果
    - **遥控器 Back 键处理**：在 Composable 顶部添加 `BackHandler(onBack = onDismiss)`，拦截系统 Back 键事件，按遥控器 Back 键时关闭设置面板
    - **TV 软键盘友好输入**：`TextField` 配置 `keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done)`，确保 Fire TV 软键盘（Leanback IME）正确弹出，Done 操作关闭键盘
    - 设备名称修改后，通过 `ServiceController.restart()` 重新注册 mDNS 服务使新名称生效

- [ ] 5.1 构建验证和 TV 适配检查
  【目标对象】整个项目构建流程
  【修改目的】验证项目可以正常编译打包为 APK，通过 Android Lint TV 应用检查
  【修改方式】使用 Gradle 构建命令，运行 Android Lint
  【相关依赖】Gradle build system、Android Lint
  【修改内容】
    - 执行 `./gradlew assembleDebug` 确保编译通过
    - 运行 `./gradlew lint` 检查 TV 应用相关警告（如缺少 `LEANBACK_LAUNCHER`、缺少 TV Banner、触摸特性声明错误等）
    - 检查 ProGuard/R8 规则（如需 minifyEnabled）中保留 DataStore、Bouncy Castle、MediaCodec、AudioTrack、NsdManager 相关类
    - 验证生成的 APK 包含所有必需资源（banner、mipmap、主题）
    - 提供 `adb install` 安装说明和 TV 遥控器操作说明

## 音频支持实现任务（扩展）

> 以下为在原有视频接收端基础上追加的音频解码与播放实现任务。iOS 投送 YouTube 等应用时，音频流通过 AirPlay 协议同步传输，需完整实现 AAC 解码和 PCM 播放才能达到正常音视频体验。

- [ ] 6.1 修改音频 SETUP 响应为 TCP Interleaved
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/RtspSession.kt`
  【修改目的】将音频传输从 UDP 统一为 TCP interleaved，使音视频共用同一个 TCP 连接，简化接收端实现
  【修改方式】修改 `handleSetup()` 中 audio 分支的 Transport header 响应
  【相关依赖】RtpInterleaved（已支持 channel 2/3 读取）
  【修改内容】
    - 在 `handleSetup()` 的 audio 分支中，将响应从 `RTP/AVP/UDP;unicast;client_port=6001;server_port=6001` 改为 `RTP/AVP/TCP;interleaved=2-3`
    - iOS 设备收到该响应后会通过同一个 TCP 连接发送音频 RTP（channel 2）和音频 RTCP（channel 3）
    - 确保 `setupCount` 递增逻辑不变，video 和 audio 各一次 SETUP 后进入 Phase 2

- [ ] 6.2 增强 SDP 解析器提取 AAC 音频参数
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/SdpParser.kt`
  【修改目的】从 SDP 的 `m=audio` 部分提取 AAC 解码所需的采样率、通道数和 AudioSpecificConfig
  【修改方式】在 SdpParser 中新增 `AudioParams` 数据类和解析方法
  【相关依赖】AudioDecoder（需要 AudioParams 初始化）
  【修改内容】
    - 新增 `AudioParams` data class：
      - `sampleRate: Int` — 采样率（如 44100、48000）
      - `channelCount: Int` — 通道数（如 2 = 立体声）
      - `audioSpecificConfig: ByteArray` — 2 字节（常见）或更多字节的 ASC，作为 MediaCodec csd-0
    - 新增 `parseAudioParams(sdpMedia: SdpMedia): AudioParams?` 方法：
      - 从 `a=rtpmap:XX mpeg4-generic/44100/2` 解析采样率和通道数
      - 从 `a=fmtp:96 mode=AAC-hbr;config=1210;...` 解析 `config=` hex 字符串为字节数组
      - `config=1210` 示例：Byte 0 = `0x12`（audioObjectType=1 AAC-LC, samplingFrequencyIndex=2=44100Hz），Byte 1 = `0x10`（channelConfiguration=2=stereo）
    - 在 `SdpInfo` 中新增 `audioParams: AudioParams?` 字段
    - 错误处理：缺少 audio 媒体行或解析失败时返回 null，兼容纯视频流

- [ ] 6.3 实现 AudioDecoder（MediaCodec AAC-LC 硬件解码）
  【目标对象】**新增** `app/src/main/java/com/airplay/firetv/airplay/AudioDecoder.kt`
  【修改目的】将 AAC-LC 编码的音频 RTP payload 解码为 PCM 数据，供 AudioTrack 播放
  【修改方式】仿照 VideoDecoder 设计，使用 `MediaCodec.createDecoderByType(MIMETYPE_AUDIO_AAC)` 创建硬件解码器
  【相关依赖】`android.media.MediaCodec`、`android.media.MediaFormat`、SdpParser.AudioParams、AirPlayCrypto（如需 AES-CTR 解密）
  【修改内容】
    - 创建 `AudioDecoder` 类：
      - `initialize(audioParams: AudioParams)`：
        - 创建 `MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount)`
        - 设置 `csd-0` 为 `ByteBuffer.wrap(audioParams.audioSpecificConfig)`
        - 调用 `MediaCodec.createDecoderByType(MIMETYPE_AUDIO_AAC)` 创建解码器
        - `configure(format, null, null, 0)` 后 `start()`
        - 启动 `decodeLoop()` 协程（在 `Dispatchers.Default` 上运行）
      - `decode(aacFrame: ByteArray, ptsUs: Long)`：
        - 若存在 AES key/iv，先调用 `AirPlayCrypto.createAesCtrCipher()` 解密 payload（去除 12-byte RTP header 后解密）
        - **剥离 AAC-hbr AU-headers**：AAC-hbr mode 下每帧有 4 字节 AU-header（2 bytes AU-headers-length + 2 bytes AU-size + AU-index），需剥离后才能喂给 MediaCodec
        - 使用 `dequeueInputBuffer` / `queueInputBuffer` 喂入 AAC 数据，传入 `ptsUs` 作为 `presentationTimeUs`
      - `decodeLoop()`：
        - 使用 `dequeueOutputBuffer(timeoutUs=0)` 非阻塞轮询输出
        - 获取解码后的 PCM `ByteBuffer`，复制为 `ByteArray`
        - 调用 `onPcmOutput(pcmData, presentationTimeUs)` 回调
        - 调用 `releaseOutputBuffer` 释放输出 buffer
      - `release()`：停止并释放 MediaCodec，取消 decodeLoop 协程
    - 定义回调接口：`fun onPcmOutput(pcmData: ByteArray, ptsUs: Long)`

- [ ] 6.4 实现 AudioPlayer（AudioTrack PCM 播放）
  【目标对象】**新增** `app/src/main/java/com/airplay/firetv/airplay/AudioPlayer.kt`
  【修改目的】将 AudioDecoder 输出的 PCM 数据通过 Android AudioTrack 播放出来
  【修改方式】使用 `AudioTrack` 以 `MODE_STREAM` 创建，配置 `AudioAttributes.USAGE_MEDIA`
  【相关依赖】`android.media.AudioTrack`、`android.media.AudioFormat`、`android.media.AudioAttributes`、AudioDecoder 的 PCM 输出回调
  【修改内容】
    - 创建 `AudioPlayer` 类：
      - `initialize(sampleRate: Int, channelCount: Int)`：
        - `channelConfig` = 若 `channelCount == 2` 则为 `AudioFormat.CHANNEL_OUT_STEREO`，否则 `CHANNEL_OUT_MONO`
        - `encoding` = `AudioFormat.ENCODING_PCM_16BIT`
        - `bufferSize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, encoding) * 2`
        - 创建 `AudioTrack`：
          - `AudioAttributes.Builder().setUsage(USAGE_MEDIA).setContentType(CONTENT_TYPE_MUSIC).build()`
          - `AudioFormat.Builder().setSampleRate(sampleRate).setEncoding(encoding).setChannelMask(channelConfig).build()`
          - `bufferSize`、`MODE_STREAM`、`AudioManager.AUDIO_SESSION_ID_GENERATE`
        - 调用 `audioTrack.play()` 开始播放
      - `write(pcmData: ByteArray)`：
        - 调用 `audioTrack.write(pcmData, 0, pcmData.size)` 写入 PCM 数据
        - 处理 write 返回值（实际写入的字节数），若不足可记录日志
      - `stop()`：调用 `audioTrack.stop()` 停止播放
      - `release()`：调用 `audioTrack.release()` 释放资源
    - 边界处理：
      - 若 AudioTrack 初始化失败（如采样率不支持），记录错误并通知上层
      - 播放过程中出现 `ERROR_DEAD_OBJECT` 等错误时，尝试重新初始化

- [ ] 6.5 集成音频组件到 AirPlayReceiver 生命周期
  【目标对象】`app/src/main/java/com/airplay/firetv/airplay/AirPlayReceiver.kt`
  【修改目的】将 AudioDecoder 和 AudioPlayer 纳入协议栈统一生命周期管理，确保连接、播放、断开时正确初始化和释放
  【修改方式】在 AirPlayReceiver 的回调方法中增加音频组件的创建、使用和销毁逻辑
  【相关依赖】AudioDecoder、AudioPlayer、SdpParser.SdpInfo、AirPlayCrypto
  【修改内容】
    - 在 `AirPlayReceiver` 中新增字段：
      - `private var audioDecoder: AudioDecoder? = null`
      - `private var audioPlayer: AudioPlayer? = null`
    - ANNOUNCE 回调修改：
      - 解析 SDP 后，若 `sdpInfo.audioParams != null`：
        - 创建 `AudioDecoder(audioParams)`，设置 `onPcmOutput` 回调为 `{ pcmData, ptsUs -> audioPlayer?.write(pcmData) }`
        - 创建 `AudioPlayer(audioParams.sampleRate, audioParams.channelCount)`
      - 若 AES key/iv 存在，保存供 AudioDecoder 解密使用
    - `onAudioFrameReceived(data: ByteArray, ptsUs: Long)` 修改：
      - 从空方法改为调用 `audioDecoder?.decode(data, ptsUs)`
      - 若存在 AES key，先解密 payload（去除 RTP header 后的部分）再送入解码器
    - TEARDOWN / 断开连接修改：
      - 将 `releaseVideoDecoder()` 重命名为 `releaseDecoders()` 或新增 `releaseAudioComponents()`
      - 依次调用 `audioDecoder?.release()`、`audioPlayer?.release()`，置为 null
    - `stop()` 修改：
      - 确保音频组件在 VideoDecoder 之后、MdnsService 之前释放
    - 错误处理：音频组件初始化或播放失败时记录日志，不影响视频播放（SupervisorJob 隔离）

- [ ] 6.6 基础音视频同步
  【目标对象】`RtpInterleaved.kt`、`AudioDecoder.kt`、`VideoDecoder.kt`
  【修改目的】确保音频和视频在播放时基本同步，避免明显的口型不对齐
  【修改方式】利用 RTP timestamp 转换为微秒时间戳，通过 MediaCodec 和 AudioTrack 的 presentationTimeUs 实现基础同步
  【相关依赖】TimingHandler（NTP 端口 6002，可选用于精确同步）
  【修改内容】
    - 视频时间戳转换（已在 RtpInterleaved 中实现）：`ptsUs = rtpTimestamp * 1_000_000L / 90_000L`（90kHz 时钟）
    - 音频时间戳转换（新增）：`ptsUs = rtpTimestamp * 1_000_000L / sampleRate`（采样率时钟，通常为 44100Hz）
    - AudioDecoder 中传入 `queueInputBuffer(presentationTimeUs = ptsUs)`，MediaCodec 内部处理时序
    - AudioTrack 以 STREAM 模式播放，天然具有较低延迟；MediaCodec 的音频解码延迟（priming frames，约 100-300ms）为系统固有延迟
    - 观察实际播放时的 A/V 偏移：
      - 若音频明显超前/滞后，可在 AirPlayReceiver 中维护一个小的 jitter buffer
      - 记录最近几帧的音频/视频 pts 差值，必要时对 AudioTrack 进行微调（如 pause/resume 或调整 buffer 大小）
    - **进阶方案**（后续迭代）：
      - 利用 TimingHandler 的 NTP 往返计算精确的网络延迟和时钟偏移
      - 实现 jitter buffer 平滑网络抖动
      - 根据视频帧率动态调整音频播放速度

- [ ] 6.7 音频功能构建验证
  【目标对象】整个项目构建流程（含音频模块）
  【修改目的】验证新增音频模块后项目仍可正常编译，无新增 Lint 警告
  【修改方式】Gradle 构建和 Lint 检查
  【相关依赖】Gradle build system、Android Lint
  【修改内容】
    - 执行 `./gradlew assembleDebug` 确保编译通过（新增 AudioDecoder.kt、AudioPlayer.kt 无编译错误）
    - 运行 `./gradlew lint` 确认无新增警告
    - 验证 ProGuard/R8 规则中保留 AudioTrack、MediaCodec（音频类型）相关类
    - 功能验证清单（手动测试）：
      - iOS 设备通过 AirPlay 连接到 FireTV
      - 播放 YouTube 视频，确认画面和声音同时输出
      - 检查不同采样率（44100Hz / 48000Hz）的兼容性
      - 测试加密流（YouTube 通常为加密）和非加密流的音频播放
      - 断开连接后重新连接，确认音频组件正确释放和重建
