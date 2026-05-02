# FireTV AirPlay Receiver — 测试指南

> 本指南仿照 [PhairPlay 测试方法论](tmp/PhairPlay-claude-setup-phairplay-project-8cpcN/docs/TESTING.md) 设计，针对当前项目（Jetpack Compose TV + Fire TV Leanback Launcher）做了适配。

---

## 快速开始

### 1. 运行全部单元测试（无需真机）

```bash
./gradlew test
```

### 2. 运行单个测试类

```bash
./gradlew test --tests "com.airplay.firetv.airplay.SdpParserTest"
```

### 3. 运行单个测试方法

```bash
./gradlew test --tests "com.airplay.firetv.airplay.SdpParserTest.\`valid video+audio SDP parses audio params correctly\`"
```

### 4. 完整 CI 检查（与 GitHub Actions 一致）

```bash
./gradlew test compileDebugKotlin
```

---

## 测试策略概览

| 层级 | 范围 | 工具 | 是否已在 CI |
|------|------|------|------------|
| **单元测试** | 纯逻辑类（Parser、BitReader、状态机） | JUnit 4 + MockK | ✅ GitHub Actions |
| **集成测试** | 多组件协作（音视频同步、RTSP 会话） | JUnit 4 + 自定义 Fixture | 待补充 |
| **手动测试** | 真机投屏、UI 交互 | iOS 设备 + Fire TV | 无法自动化 |

---

## 单元测试：已规划测试类

### `SdpParserTest` → [`SdpParser.kt`](app/src/main/java/com/airplay/firetv/airplay/SdpParser.kt)

**测试目标**：验证 SDP 解析器正确提取音视频参数，特别是 AAC 音频参数。

| 测试方法 | 验证内容 |
|---------|---------|
| `valid video+audio SDP parses correctly` | 正常音视频 SDP 能解析出 Video Media 和 Audio Media |
| `valid video+audio SDP parses audio params correctly` | `AudioParams` 的 `sampleRate=44100`、`channelCount=2`、`asc` 非空 |
| `audio-only SDP without video parses correctly` | 纯音频 SDP 的 `videoMedia=null` |
| `empty SDP returns empty session` | 空字符串返回无 media 的 `SdpSession` |
| `SDP without media sections has empty medias` | 无 `m=` 行时 `medias` 为空列表 |
| `hexStringToByteArray converts valid hex` | `"1190"` → `[0x11, 0x90]` |
| `hexStringToByteArray rejects odd-length hex` | 奇数长度 hex 返回 `null` |
| `hexStringToByteArray rejects non-hex chars` | 含非法字符返回 `null` |

**测试模式**：Companion object 存放 SDP fixture 字符串，避免每个测试重复构建。

```kotlin
companion object {
    val SDP_VIDEO_AUDIO = """
        v=0
        o=- 0 0 IN IP4 127.0.0.1
        s=AirPlay
        c=IN IP4 127.0.0.1
        t=0 0
        m=video 0 RTP/AVP 96
        a=rtpmap:96 H264/90000
        a=fmtp:96 packetization-mode=1;profile-level-id=640028;sprop-parameter-sets=Z0LAKNoB7xLcBA==,aM48gA==
        m=audio 0 RTP/AVP 96
        a=rtpmap:96 mpeg4-generic/44100/2
        a=fmtp:96 mode=AAC-hbr;config=1190;sizeLength=13;indexLength=3;indexDeltaLength=3
    """.trimIndent()
}
```

---

### `RtpInterleavedTest` → [`RtpInterleaved.kt`](app/src/main/java/com/airplay/firetv/airplay/RtpInterleaved.kt)

**测试目标**：验证 `$` 帧格式的 RTP 交错数据解析，区分视频（channel 0）和音频（channel 2）。

| 测试方法 | 验证内容 |
|---------|---------|
| `valid video RTP frame triggers callback` | channel=0 的数据触发 `onVideoRtpPacket` |
| `valid audio RTP frame triggers callback` | channel=2 的数据触发 `onAudioRtpPacket` |
| `RTCP packets on channel 1 are ignored` | channel=1 不触发任何回调 |
| `RTCP packets on channel 3 are ignored` | channel=3 不触发任何回调 |
| `empty stream returns immediately` | 空 `ByteArrayInputStream` 不报错 |
| `garbage bytes before dollar sign are skipped` | `$` 前的垃圾字节被正确跳过 |
| `invalid length frame is skipped` | 长度字段超出实际数据时跳过该帧 |

**测试模式**：使用 `ByteArrayInputStream` 模拟网络字节流，`buildInterleavedFrame()` 私有 helper 构建合成帧。

```kotlin
private fun buildInterleavedFrame(channel: Int, payload: ByteArray): ByteArray {
    val header = byteArrayOf(
        '$'.code.toByte(),
        channel.toByte(),
        (payload.size shr 8).toByte(),
        (payload.size and 0xFF).toByte()
    )
    return header + payload
}
```

---

### `AudioPlayerTest` → [`AudioPlayer.kt`](app/src/main/java/com/airplay/firetv/airplay/AudioPlayer.kt)

**测试目标**：验证 `AudioPlayer` 生命周期安全和参数校验。注意：`AudioTrack` 是 Android 平台类，**无法在 JVM 单元测试中真实初始化**。

| 测试方法 | 验证内容 |
|---------|---------|
| `play before initialize returns silently` | 未 `initialize()` 时调用 `play()` 不崩溃 |
| `pause before initialize returns silently` | 未 `initialize()` 时调用 `pause()` 不崩溃 |
| `release before initialize returns silently` | 未 `initialize()` 时调用 `release()` 不崩溃 |
| `double release does not crash` | 两次 `release()` 不崩溃 |
| `initialize with invalid sample rate throws` | 不支持采样率（如 12345）抛出异常 |
| `initialize with channel count 0 throws` | 通道数为 0 抛出异常 |

**测试模式**：利用 `@Before` 创建实例，测试**前初始化安全**（pre-init safety）—— 这是当前代码中已实现的关键防御逻辑。

---

### `AudioDecoderTest` → [`AudioDecoder.kt`](app/src/main/java/com/airplay/firetv/airplay/AudioDecoder.kt)

**测试目标**：验证 `AudioDecoder` 生命周期和输入校验。`MediaCodec` 无法在 JVM 中实例化。

| 测试方法 | 验证内容 |
|---------|---------|
| `decode before initialize returns silently` | 未 `initialize()` 时调用 `decode()` 不崩溃 |
| `release before initialize returns silently` | 未 `initialize()` 时调用 `release()` 不崩溃 |
| `stripAuHeader extracts raw AAC from RFC 3640 frame` | 正确剥去 AU-header，返回原始 AAC 数据 |
| `stripAuHeader returns null for too-short frame` | 长度不足以容纳 AU-header 时返回 `null` |

---

### `VideoDecoderSpsTest` → [`VideoDecoder.kt`](app/src/main/java/com/airplay/firetv/airplay/VideoDecoder.kt)（`SpsBitReader`）

**测试目标**：验证 SPS 比特流解析器的位级操作和分辨率提取。

| 测试方法 | 验证内容 |
|---------|---------|
| `readBit returns single bits` | 读取 `[0b1010_0000]` 得到 `1, 0, 1, 0` |
| `readBits returns multi-bit values` | `readBits(4)` 读取 `[0b1010_0000]` 得到 `10` |
| `readUE decodes zero` | `0b1_0000000` → `0` |
| `readUE decodes small value` | `0b010_00000` → `1` |
| `readUE decodes large value` | `0b0000101_0` → `5` |
| `parse returns correct resolution for 720p` | 合成 1280×720 SPS → `width=1280, height=720` |
| `parse returns correct resolution for 1080p` | 合成 1920×1080 SPS → `width=1920, height=1080` |
| `parse handles frame cropping` | 含 crop 参数的 SPS 计算裁剪后分辨率 |
| `parse returns null for empty SPS` | 空 `ByteArray` 返回 `null` |
| `parse returns null for single-byte SPS` | 单字节 SPS 返回 `null` |

**测试模式**：使用私有 `SpsBitWriter` helper class 在测试中动态构建合成 SPS 数据，避免硬编码二进制 magic number。

```kotlin
private class SpsBitWriter {
    private val bits = mutableListOf<Int>()
    fun writeBits(n: Int, value: Int) { /* ... */ }
    fun writeUe(value: Int) { /* ... */ }
    fun toByteArray(): ByteArray { /* ... */ }
}
```

---

### `AirPlayReceiverTest` → [`AirPlayReceiver.kt`](app/src/main/java/com/airplay/firetv/airplay/AirPlayReceiver.kt)

**测试目标**：验证接收器状态机、RTSP 回调分发、生命周期管理。

**难点**：`AirPlayReceiver` 依赖 `RtspHandler`、`MdnsService`、`VideoDecoder`、`AudioDecoder`、`AudioPlayer` 等 Android 平台组件。

**解决方案**：使用 MockK 模拟 `Context` 和 `MdnsService`，使用 Testable 子类暴露 `internal`/`private` 方法。

| 测试方法 | 验证内容 |
|---------|---------|
| `start registers mDNS service` | `start()` 后 `MdnsService.start()` 被调用 |
| `onAnnounce parses SDP and stores session` | `onAnnounce()` 后 `sdpSession` 非空 |
| `onTeardown releases all components` | `onTeardown()` 后 `videoDecoder` 和 `audioComponents` 被释放 |
| `stop unregisters mDNS service` | `stop()` 后 `MdnsService.stop()` 被调用 |

```kotlin
class TestableAirPlayReceiver(context: Context) : AirPlayReceiver(context) {
    // 暴露内部状态用于断言
    fun getSdpSession(): SdpSession? = sdpSession
    fun isVideoDecoderInitialized(): Boolean = videoDecoder != null
}
```

---

### `RtspSessionTest` → [`RtspSession.kt`](app/src/main/java/com/airplay/firetv/airplay/RtspSession.kt)

**测试目标**：验证 RTSP 请求解析和响应生成。

| 测试方法 | 验证内容 |
|---------|---------|
| `parseRtspRequest extracts method uri and headers` | 正确解析 `ANNOUNCE rtsp://...` |
| `handleOptions returns public methods` | `OPTIONS` 响应包含 `ANNOUNCE, SETUP, RECORD, TEARDOWN` |
| `handleAnnounce without body returns error` | 无 body 的 `ANNOUNCE` 返回 400 |
| `handleRecord without announce returns 455` | 未 `ANNOUNCE` 先 `RECORD` 返回 455 Method Not Valid |

**测试模式**：使用 `ByteArrayInputStream` 提供 RTSP 请求文本，捕获 `BufferedOutputStream` 输出验证响应。

---

### `RtpPacketTest` → [`RtpPacket.kt`](app/src/main/java/com/airplay/firetv/airplay/RtpPacket.kt)

**测试目标**：验证 RTP 包头解析。

| 测试方法 | 验证内容 |
|---------|---------|
| `parse extracts version payload type and timestamp` | 标准 RTP 包头正确解析 |
| `parse returns null for too-short data` | 少于 12 字节返回 `null` |
| `parse handles marker bit` | Marker bit (M=1) 正确识别 |

---

### `ServiceControllerTest` → [`ServiceController.kt`](app/src/main/java/com/airplay/firetv/service/ServiceController.kt)

**测试目标**：验证 Service 启动/停止的 Intent 构造。

| 测试方法 | 验证内容 |
|---------|---------|
| `start sends intent with correct action` | `start()` 发送 action 为 `"com.airplay.firetv.ACTION_START"` 的 Intent |
| `stop sends intent with correct action` | `stop()` 发送 action 为 `"com.airplay.firetv.ACTION_STOP"` 的 Intent |
| `start includes settings extras` | Intent extras 包含 `AppSettings` 序列化数据 |

```kotlin
@Test
fun `start sends intent with correct action`() {
    val slot = slot<Intent>()
    every { context.startService(capture(slot)) } returns mockk()
    
    controller.start(AppSettings())
    
    assertEquals("com.airplay.firetv.ACTION_START", slot.captured.action)
}
```

---

## 什么不能单元测试（以及为什么）

| 组件 | 原因 | 替代验证方式 |
|------|------|-------------|
| `MediaCodec` (音视频解码) | Android 平台类，JVM 中无法实例化 | 手动测试真机投屏；使用 `MediaCodecList` 检查编解码器可用性 |
| `AudioTrack` (音频播放) | Android 平台类，需要音频硬件 | 手动测试；检查 `AudioTrack` 初始化返回值 |
| `NsdManager` (mDNS) | Android 平台类，需要网络栈 | 手动测试 iOS 发现设备；Logcat 观察注册/注销日志 |
| `Surface` / `SurfaceView` (视频渲染) | 需要图形硬件和窗口系统 | 手动测试屏幕显示；截图对比 |
| Jetpack Compose UI | 需要 Android Runtime 和窗口 | 使用 Compose UI 测试框架（`compose-ui-test`）在 emulator 上运行 |
| 完整的 RTSP/TCP 网络交互 | 需要真实 Socket 和时序 | 集成测试或手动端到端测试 |

---

## 手动测试场景（端到端验证）

### 场景 1：应用启动

**前置条件**：Fire TV 已连接 Wi-Fi，安装 APK

**步骤**：
1. 从 Leanback Launcher 点击应用图标启动
2. 观察 WaitingScreen 是否正常显示等待文字
3. 查看 Logcat：`AirPlayReceiver started` 日志出现

**预期结果**：应用启动无崩溃，mDNS 服务注册成功

---

### 场景 2：mDNS 发现

**前置条件**：Fire TV 和 iOS 设备在同一 Wi-Fi

**步骤**：
1. 在 iOS 设备上打开控制中心 → 屏幕镜像
2. 查看可用设备列表

**预期结果**：列表中出现 Fire TV 设备名称

---

### 场景 3：视频投屏（YouTube）

**前置条件**：mDNS 发现成功

**步骤**：
1. iOS 设备打开 YouTube App
2. 播放任意视频，点击 AirPlay 图标选择 Fire TV
3. 观察 Fire TV 屏幕

**预期结果**：
- 视频画面正常显示
- 音频同步播放
- 无明显卡顿或延迟（< 200ms）

---

### 场景 4：音频同步验证

**前置条件**：视频投屏进行中

**步骤**：
1. 播放含明确口型的视频（如新闻播报）
2. 观察嘴唇动作与声音的匹配度

**预期结果**：音视频同步误差 < 100ms（人眼无法察觉）

---

### 场景 5：断开重连

**前置条件**：投屏进行中

**步骤**：
1. iOS 设备点击停止镜像
2. 等待 Fire TV 回到 WaitingScreen
3. 再次发起镜像

**预期结果**：
- 断开时 Fire TV 正确释放资源（无内存泄漏 Log）
- 重连后正常播放

---

### 场景 6：长时间稳定性

**前置条件**：投屏正常

**步骤**：
1. 连续播放 30 分钟视频
2. 期间不定期暂停/播放/快进

**预期结果**：无崩溃、无内存持续增长（`adb shell dumpsys meminfo` 观察）、音视频持续同步

---

## GitHub Actions CI 集成

测试已集成到 [`.github/workflows/build.yml`](.github/workflows/build.yml)：

```yaml
- name: Run Unit Tests
  run: ./gradlew test

- name: Compile Debug Kotlin
  run: ./gradlew compileDebugKotlin
```

---

## 添加新测试的步骤

1. **确定测试目标**：哪个类、哪个方法、什么行为
2. **选择测试模式**：
   - 纯逻辑（Parser/BitReader）→ 直接实例化测试
   - 含 Android 依赖 → MockK mock `Context`
   - `internal`/`private` 方法 → Testable 子类
3. **构建合成数据**：Companion object fixture 或 private helper
4. **命名测试方法**：使用反引号包裹描述性名称
   ```kotlin
   @Test
   fun `invalid hex string returns null`() { }
   ```
5. **遵循 Arrange-Act-Assert**：
   ```kotlin
   // Arrange
   val parser = SdpParser()
   
   // Act
   val result = parser.parse(SDP_VIDEO_AUDIO)
   
   // Assert
   assertNotNull(result.audioMedia)
   ```
6. **运行验证**：`./gradlew test --tests "ClassName.methodName"`
