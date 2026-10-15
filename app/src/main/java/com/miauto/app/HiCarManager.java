package com.miauto.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

/**
 * 华为 HiCar 协议管理器（骨架 / 模板）。
 *
 * <p>本类负责 HiCar 协议栈的生命周期：初始化 → BLE 广播 → 配对 → 投屏 Surface → 回调转发。
 * 结构与 {@link IccoaManager} 对齐，便于 UI 层用 {@link CarLinkManager} 接口统一调度。
 *
 * <p><b>★ 重要：本类是骨架，不能直接编译运行。</b>
 * HiCar 车端 SDK（{@code hicar-sdk.dex}）是华为私有组件，不在本仓库内。
 * 你需要从一台带 HiCar 的前装车机系统里提取 HiCar 车端服务的 dex / so / 资源，
 * 然后按 {@link docs/HICAR_INTEGRATION.md} 的说明把包名、类名、API 调用替换成实际 SDK 的。
 *
 * <p>HiCar 与 ICCOA 的主要差异：
 * <ul>
 *   <li>发现：HiCar 走 BLE 广播 + WiFi Aware / WiFi Direct，Service UUID 不同</li>
 *   <li>鉴权：HiCar 有华为账号绑定 + 设备证书双向认证，比 ICCOA 的 PIN 码用户确认更严格</li>
 *   <li>投屏：HiCar 走自己的通道（不是 Miracast RTSP），视频解码在 SDK 内部完成</li>
 *   <li>包名：HiCar SDK 通常在 {@code com.huawei.hicar.*} / {@code com.huawei.carmode.*}</li>
 * </ul>
 */
public class HiCarManager implements CarLinkManager.Listener /* 内部转发用 */ {

    private static final String TAG = "MiAuto/HiCar";

    /** 协议显示名。 */
    public static final String PROTOCOL_NAME = "华为 HiCar";

    private final Context appCtx;
    private final CarLinkManager.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());

    // ---- HiCar SDK 入口（占位，需替换为实际 SDK 类）----
    // TODO(hiCarSdk): 把下面这些占位类型替换成实际 HiCar SDK 的类：
    //   - HiCarAdapter → 实际 SDK 的单例门面（类似 ICCOA 的 UCarAdapter）
    //   - IHiCarInitCallback → 初始化回调接口
    //   - IHiCarConnectCallback → 连接状态回调
    //   - IHiCastCallback → 投屏回调
    // 当前先用 Object 占位，保证骨架可编译；接入真实 SDK 后改回具体类型。
    private Object hicarAdapter = null;

    private Surface surface = null;

    // ---- 状态机（与 IccoaManager 对齐）----
    private volatile boolean sdkInitialized = false;
    private volatile boolean sdkReady = false;
    private volatile boolean castStarted = false;
    private volatile boolean sessionActive = false;
    private volatile String lastPinCode = null;

    /** 配对码看门狗周期（与 ICCOA 一致：30 秒重播一次把手机弹窗叫回来）。 */
    private static final long AUTO_REFRESH_INTERVAL_MS = 30_000L;

    /** 会话结束后重新广播的延时。 */
    private static final long RE_ADVERTISE_DELAY_MS = 1500L;

    private static final int CAST_RETRY_MAX = 20;
    private int castRetry = 0;

    private int screenW = 1920, screenH = 720;

    private final Runnable reAdvertiseRunnable = () -> {
        if (sdkReady) {
            log("会话结束 → HiCar 重新广播");
            reAdvertise();
        }
    };

    private final Runnable autoRefreshRunnable = () -> {
        if (!sdkReady) return;
        if (sessionActive || castStarted) {
            scheduleAutoRefresh();
            return;
        }
        log("★ HiCar 自动刷新：" + (AUTO_REFRESH_INTERVAL_MS / 1000) + "秒未连接 → 重播广播");
        reAdvertise();
        scheduleAutoRefresh();
    };

    public HiCarManager(Context ctx, CarLinkManager.Listener listener) {
        this.appCtx = ctx.getApplicationContext();
        this.listener = listener;
    }

    // ================================================================
    // CarLinkManager 接口实现
    // ================================================================

    @Override
    public void setScreenSize(int w, int h) {
        screenW = w;
        screenH = h;
    }

    @Override
    public void setSurface(Surface s) {
        this.surface = s;
        castRetry = 0;
        tryStartCast();
    }

    @Override
    public void start() {
        log("=== 启动（HiCar）===");
        if (sdkInitialized && hicarAdapter != null) {
            reAdvertise();
            return;
        }
        // TODO(hiCarSdk): 初始化 HiCar SDK：
        //   hicarAdapter = HiCarAdapter.getInstance();
        //   HiCarConfig config = buildHiCarConfig();
        //   hicarAdapter.init(appCtx, config, initCallback);
        //
        // 当前仅标记为不可用，避免空指针。接入真实 SDK 后删除下面这行。
        log("⚠ HiCar SDK 未注入（libs/hicar-sdk.dex 不存在），本协议不可用");
    }

    @Override
    public void stop() {
        log("=== HiCar 停止 ===");
        // TODO(hiCarSdk):
        //   if (hicarAdapter != null) {
        //       ((HiCarAdapter)hicarAdapter).pauseCast();
        //       ((HiCarAdapter)hicarAdapter).disconnect();
        //       ((HiCarAdapter)hicarAdapter).stopAdvertise();
        //   }
        main.removeCallbacks(reAdvertiseRunnable);
        main.removeCallbacks(autoRefreshRunnable);
        sessionActive = false;
        castStarted = false;
        sdkReady = false;
        lastPinCode = null;
    }

    @Override
    public void refreshPairing() {
        log("=== HiCar 手动刷新配对码 ===");
        if (!sdkReady) {
            log("刷新失败：HiCar SDK 未就绪");
            return;
        }
        reAdvertise();
        scheduleAutoRefresh();
    }

    @Override
    public void disconnectSession() {
        log("=== HiCar 断开当前会话（保持广播）===");
        // TODO(hiCarSdk):
        //   if (hicarAdapter != null) {
        //       ((HiCarAdapter)hicarAdapter).pauseCast();
        //       ((HiCarAdapter)hicarAdapter).disconnect();
        //   }
        sessionActive = false;
        castStarted = false;
        lastPinCode = null;
        scheduleReAdvertise();
        scheduleAutoRefresh();
    }

    @Override
    public String getLastPinCode() {
        return lastPinCode;
    }

    @Override
    public String getProtocolName() {
        return PROTOCOL_NAME;
    }

    @Override
    public boolean isProtocolAvailable() {
        // TODO(hiCarSdk): 检查 hicar-sdk.dex 是否已注入。
        // 可用 Class.forName("com.huawei.hicar.HiCarAdapter") 探测关键类是否存在。
        try {
            Class.forName("com.huawei.hicar.HiCarAdapter");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    // ================================================================
    // 内部：广播 / 投屏 / 状态机
    // ================================================================

    private void reAdvertise() {
        if (hicarAdapter == null) return;
        // TODO(hiCarSdk):
        //   ((HiCarAdapter)hicarAdapter).stopAdvertise();
        //   sdkReady = true;
        //   castStarted = false;
        //   lastPinCode = null;
        //   doAdvertise();
        log("reAdvertise: HiCar SDK 未接入，空操作");
    }

    private void doAdvertise() {
        // TODO(hiCarSdk): ((HiCarAdapter)hicarAdapter).startAdvertise();
        tryStartCast();
    }

    private void tryStartCast() {
        if (!sdkReady || castStarted || hicarAdapter == null) return;
        Surface s = surface;
        if (s == null || !s.isValid()) {
            if (castRetry < CAST_RETRY_MAX) {
                castRetry++;
                main.postDelayed(this::tryStartCast, 500);
            }
            return;
        }
        castRetry = 0;
        // TODO(hiCarSdk):
        //   boolean ok = ((HiCarAdapter)hicarAdapter).startCast(s, screenW, screenH);
        //   castStarted = ok;
        //   if (ok) main.post(listener::onCastStarted);
        log("tryStartCast: HiCar SDK 未接入，空操作");
    }

    private void scheduleReAdvertise() {
        main.removeCallbacks(reAdvertiseRunnable);
        main.postDelayed(reAdvertiseRunnable, RE_ADVERTISE_DELAY_MS);
    }

    private void scheduleAutoRefresh() {
        main.removeCallbacks(autoRefreshRunnable);
        main.postDelayed(autoRefreshRunnable, AUTO_REFRESH_INTERVAL_MS);
    }

    // ================================================================
    // HiCar SDK 回调（接入真实 SDK 时在此桥接）
    // ================================================================

    /**
     * HiCar SDK 初始化成功回调。
     * 接入时把 SDK 的 onInitSuccess() 接到这里。
     */
    private void onHiCarInitSuccess() {
        log("★ HiCar onInitSuccess()");
        sdkReady = true;
        sdkInitialized = true;
        main.post(this::doAdvertise);
        main.post(this::scheduleAutoRefresh);
    }

    /**
     * HiCar 下发配对码。
     * 接入时把 SDK 的 onPinCode / onPairRequest() 接到这里。
     */
    private void onPinCode(String pinCode, String deviceName) {
        lastPinCode = pinCode;
        log("★ HiCar onPinCode=" + pinCode + " device=" + deviceName);
        scheduleAutoRefresh();
        main.post(() -> listener.onPinCode(pinCode, deviceName));
    }

    /**
     * HiCar 连接状态变化。
     * newState: 0=断开 1=连接中 2=已连接 3=已建立会话
     */
    private void onConnectStateChanged(int oldState, int newState) {
        log("HiCar onConnectStateChanged " + stateName(oldState) + " -> " + stateName(newState));
        if (newState == 0 && oldState != 0) {
            sessionActive = false;
            lastPinCode = null;
            castStarted = false;
            scheduleReAdvertise();
            scheduleAutoRefresh();
        } else if (newState == 2 || newState == 3) {
            sessionActive = true;
            main.removeCallbacks(reAdvertiseRunnable);
        }
        final int st = newState;
        main.post(() -> listener.onConnectionState(st));
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        try { listener.onLog(msg); } catch (Throwable ignored) { }
    }

    private static String stateName(int s) {
        switch (s) {
            case 0: return "DISCONNECTED";
            case 1: return "CONNECTING";
            case 2: return "CONNECTED";
            case 3: return "ESTABLISHED";
            default: return "STATE_" + s;
        }
    }
}
