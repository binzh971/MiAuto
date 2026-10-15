# app/libs — runtime dependencies that are not in git

本目录存放**不入库**的预编译私有 SDK，构建时由 Gradle 任务注入 APK。

## `icar-sdk.dex` — ICCOA CarLink 协议 SDK

ICCOA CarLink（小米 CarWith / OPPO Car+ 车联 / vivo Jovi InCar 走的手机侧协议）。
**本文件故意不入库**：它是第三方私有 bytecode blob，不受本项目 AGPL-3.0 许可覆盖。

| 半份 | 文件 | 用途 |
| --- | --- | --- |
| 编译期 | [`app/stubs/icar-sdk-stubs.jar`](../stubs) | 入库，纯签名存根，`compileOnly` 不进 APK |
| 运行期 | `app/libs/icar-sdk.dex` | ~7.1MB，注入为 `classesN.dex` |

构建时 `injectIccoaDex` 任务会把它追加进打包好的 APK，再 zipalign + 重签。
缺少它时构建仍成功，但产出的 APK 不具备 CarWith 能力。

详见 [`docs/ICCOA_SDK_INTEGRATION.md`](../../docs/ICCOA_SDK_INTEGRATION.md)。

---

## `hicar-sdk.dex` — 华为 HiCar 协议 SDK（新增）

华为 HiCar 车端协议栈。**同样不入库**——它是华为私有组件，受华为著作权保护。

| 项 | 值 |
| --- | ---
| 文件路径 | `app/libs/hicar-sdk.dex` |
| 来源 | 从带 HiCar 的前装车机系统分区提取（见 [`docs/HICAR_INTEGRATION.md`](../../docs/HICAR_INTEGRATION.md)） |
| 编译期存根 | 需要自己生成 `app/stubs/hicar-sdk-stubs.jar`（反编译 HiCar.apk 后抽签名） |
| 注入任务 | `injectHiCarDex`（结构与 `injectIccoaDex` 一致） |
| 原生库 | HiCar 的 `.so` 必须放到 `app/src/main/jniLibs/arm64-v8a/`，不能后注入 |

**CI 构建默认不带它**。Release APK 需要在本地把 `hicar-sdk.dex` 放进来再构建。

> ⚠️ 法律风险：HiCar SDK 受华为著作权保护，提取后仅可用于个人研究，
> 不要把包含 HiCar dex 的 APK 公开发布。

---

## 两个 SDK 同时存在时的注意事项

1. **dex 编号**：`injectIccoaDex` 和 `injectHiCarDex` 都按「下一个空闲 classesN.dex」
   编号，注意执行顺序——ICCOA 先注入，HiCar 后注入，避免编号冲突。
2. **BLE 广播互斥**：两个协议都抢 BLE Advertiser，同一时间只能跑一个。
   建议在设置里做协议切换，而不是同时广播两个协议。
3. **WiFi P2P 互斥**：两个协议都要建 WiFi Direct 组，同时跑会互相抢占。
4. **权限叠加**：HiCar 需要的 signature 级权限和 ICCOA 有重叠但不完全一样，
   最终 Manifest 要把两边的权限都列全。
