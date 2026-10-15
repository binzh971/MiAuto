# 华为 HiCar 集成说明

本文说明如何把华为 HiCar 协议栈接入 MiAuto。与 ICCOA CarLink 类似，HiCar 的车端
核心协议**不在本仓库内**——它是华为私有组件，需要你自备 SDK dex。

---

## 1. 为什么不能像 ICCOA 那样直接用？

| 维度 | ICCOA CarLink | 华为 HiCar |
|---|---|---|
| 标准属性 | 中国智能网联汽车产业创新联盟开放标准 | 华为私有协议 |
| SDK 来源 | 有公开 demo（智慧车联 APK）可提取 | 仅前装车厂授权，无公开 demo |
| 车端 SDK 包名 | `com.ucar.vehiclesdk.*` / `com.share.connect.*` | 通常 `com.huawei.hicar.*` / `com.huawei.carmode.*` |
| 配对方式 | PIN 码 + 用户确认（不校验证书） | 设备证书双向认证 + 华为账号绑定 |
| 投屏通道 | Miracast RTSP/RTP（AOSP WifiDisplay） | HiCar 私有通道（非 Miracast） |
| 可提取性 | 第三方 APK 可提取 dex | 车机系统分区，需 root 提取 |

**结论**：MiAuto 的 UI 壳、设置、Surface 渲染、BYD 仪表/HUD 链路全部可以复用，
但 HiCar 协议栈本身必须从一台已量产 HiCar 车机的系统分区里提取。

---

## 2. 前置条件

你需要拿到以下东西（从一台带 HiCar 的 Android 车机里提取，通常在 `/system/app/HiCar/`
或 `/system/priv-app/HiCar/` 下）：

| 文件 | 说明 |
|---|---|
| `HiCar.apk` 或拆分出的 `classes*.dex` | HiCar 车端服务主包 |
| `lib/arm64-v8a/*.so` | HiCar 原生库（编解码、通道、加密） |
| `/system/etc/permissions/` 下的 HiCar 权限 XML | HiCar 声明的 signature 级权限 |
| `AndroidManifest.xml`（解包后） | HiCar 注册的 Service / Receiver / Provider 清单 |

> ⚠️ **法律风险**：HiCar SDK 受华为著作权保护，提取并二次分发属于侵权。
> 本文档仅用于你自己车机的个人研究与使用，不要把提取出来的 dex / so 公开分发。

---

## 3. 接入步骤

### 步骤 1：提取 HiCar SDK dex

```bash
# 假设你已经 root 了一台 HiCar 车机，从 /system/priv-app/HiCar/ 拉出 HiCar.apk
# 解包后把所有 classes.dex 合并（或直接用整个 APK 里的 dex）：
unzip -o HiCar.apk -d hicar_extract/

# 把主 dex 复制到 MiAuto 的 libs 目录，命名为 hicar-sdk.dex
cp hicar_extract/classes.dex app/libs/hicar-sdk.dex
# 如果有多 dex（classes2.dex / classes3.dex），需要全部提取并合并，
# 或者改成逐个注入（见步骤 4 的构建脚本改造）
```

### 步骤 2：补齐 HiCar 的原生库

HiCar 的 `.so` 文件不能像 dex 那样后注入，必须走标准 jniLibs 打包：

```bash
mkdir -p app/src/main/jniLibs/arm64-v8a/
cp hicar_extract/lib/arm64-v8a/*.so app/src/main/jniLibs/arm64-v8a/
```

### 步骤 3：对齐 `HiCarManager.java` 里的实际 API

打开 `app/src/main/java/com/miauto/app/HiCarManager.java`，把所有 `TODO(hiCarSdk)`
标记的地方替换成实际 HiCar SDK 的类名和方法调用。需要确认：

1. **SDK 入口类**：是单例 `HiCarAdapter.getInstance()`？还是通过绑定 Service？
2. **初始化接口**：方法签名是 `init(Context, Config, Callback)` 吗？
3. **广播控制**：`startAdvertise()` / `stopAdvertise()` 是否存在？
4. **投屏接口**：`startCast(Surface, w, h)` 还是别的形式？
5. **回调接口**：配对码、连接状态、投屏事件的回调方法签名

> 用 jadx 反编译提取出来的 HiCar.apk，搜索 `Advertise` / `Pair` / `Cast` / `Surface`
> 等关键词定位入口类。参考 ICCOA 的做法（见 `docs/APK_ANALYSIS_ZHL-v1.5.3.md`）。

### 步骤 4：修改 `app/build.gradle.kts`

在文件末尾的 `injectIccoaDex` 任务后面，追加 HiCar dex 注入任务。
结构完全照搬 ICCOA 的，只是文件名换成 `hicar-sdk.dex`：

```kotlin
// 在 injectIccoaDex 任务定义之后追加：
val hiCarSdkDex = file("libs/hicar-sdk.dex")
val hiCarSdkDexBytes: ByteArray = if (hiCarSdkDex.exists()) hiCarSdkDex.readBytes() else ByteArray(0)

val injectHiCarDex = tasks.register("injectHiCarDex") {
    group = "build"
    description = "Appends the HiCar SDK dex into APKs and re-signs"
    onlyIf { hiCarSdkDex.exists() }
    doLast {
        // 与 injectIccoaDex 逻辑完全一致，只是把 sdkDexBytes 换成 hiCarSdkDexBytes
        // 注意：要在 ICCOA dex 注入之后跑，避免 classesN.dex 编号冲突
        // ……（复制 injectIccoaDex 的逻辑，替换变量名）
    }
}

afterEvaluate {
    tasks.matching {
        it.name in setOf("assembleGithubDebug", "assembleGithubRelease")
    }.configureEach {\n        finalizedBy(injectHiCarDex)  // 在 ICCOA 之后追加 HiCar
    }
}
```

### 步骤 5：在 `AndroidManifest.xml` 注册 HiCar 组件

参考你从 HiCar.apk 解包出来的 AndroidManifest.xml，把里面的 `<service>` /
`<receiver>` / `<provider>` 全部复制到 MiAuto 的 `AndroidManifest.xml` 的
`<application>` 标签内。**重点**：

- HiCar 的 BLE 广播 Service（必须，否则手机扫不到）
- HiCar 的连接管理 Service
- HiCar 声明的所有 `<uses-permission>`（特别是 signature 级权限，
  普通应用拿不到时会静默失败——和 ICCOA 一样的坑）

在现有 ICCOA 服务声明的后面加：

```xml
<!-- ======================================================================
     HiCar 车端 SDK 必需组件（根据你提取的 HiCar.apk 清单对齐）
     ====================================================================== -->
<!-- TODO(hiCarManifest): 把下面的占位 Service 名替换成实际 HiCar 包名 -->
<service
    android:name="com.huawei.hicar.service.HiCarConnectService"
    android:exported="false"
    tools:ignore="Instantiatable" />
<service
    android:name="com.huawei.hicar.service.HiCarBleService"
    android:exported="false"
    tools:ignore="Instantiatable" />
```

### 步骤 6：让 UI 层能切换协议

在 MainActivity 或设置页里，根据 `HiCarManager.isProtocolAvailable()`
决定是否显示 HiCar 选项。如果要同时跑两个协议（ICCOA + HiCar 同时广播），
需要注意：

- **BLE 广播资源冲突**：两个协议都抢 BLE Advertiser，同一时间只能跑一个
- **WiFi P2P / SoftAP 冲突**：两个协议都要建 WiFi 组，也不能同时跑
- 建议做成**协议切换**而不是同时支持：用户在设置里选「ICCOA / HiCar」，
  重启后只起一个协议栈

---

## 4. 验证清单

按顺序做，每一步通过再做下一步：

- [ ] `hicar-sdk.dex` 放到 `app/libs/`，构建时日志能看到「已附加 classesN.dex」
- [ ] App 启动不崩溃（HiCar SDK 的类能被 BaseDexClassLoader 加载）
- [ ] `HiCarManager.isProtocolAvailable()` 返回 true
- [ ] 启动后 logcat 能看到 HiCar SDK 的初始化日志
- [ ] BLE 广播起来了（用 nRF Connect 扫，能看到 HiCar 的 Service UUID）
- [ ] HiCar 手机（华为/荣耀）能在「车联」设置里发现这台车机
- [ ] 配对码弹窗出现，输入后能建立连接
- [ ] Surface 上能看到手机画面
- [ ] 触控回传正常（点车机屏幕，手机有反应）

---

## 5. 已知坑（与 ICCOA 一样的，先记着）

1. **Manifest 服务漏注册 = 静默失效**：dex 里有类但 Manifest 没声明 Service，
   系统起不来，logcat 不报错，表现为「手机扫不到车机」。
2. **权限不足 = API 静默失败**：HiCar 需要的 `BLUETOOTH_PRIVILEGED` /
   `NETWORK_SETTINGS` / `TETHER_PRIVILEGED` 等 signature 级权限，普通应用
   装上去是拿不到的——MiAuto 必须装成系统应用（或用车机的签名）才能拿到。
3. **init-once**：HiCar SDK 内部通常也是进程一次性初始化，重复 init 会导致
   BLE 状态残留。HiCarManager 已经做了 `sdkInitialized` 守门，不要去掉。
4. **配对码重播**：手机关掉配对弹窗后不会自己再弹，必须「停-启」广播才能
   重新叫起来。HiCarManager 的 30 秒看门狗已经实现了这个逻辑。

---

## 6. 法律与合规

- HiCar 是华为的注册商标与私有协议，本项目与华为无任何隶属关系
- 提取 HiCar SDK 仅用于个人车机的研究用途，不要公开发布包含 HiCar dex 的 APK
- 分发时如果要附带 HiCar 能力，必须获得华为的正式授权
