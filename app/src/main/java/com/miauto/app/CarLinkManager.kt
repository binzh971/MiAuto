package com.miauto.app

import android.view.Surface

/**
 * 车机互联协议抽象接口。
 *
 * UI 层（MainActivity / IcocaProjectionActivity / 设置页）只面向此接口编程，
 * 不关心底层是 ICCOA CarLink（小米CarWith/OPPO Car+/vivo Jovi）还是华为 HiCar。
 *
 * 实现类：
 * - [IccoaManager]：ICCOA CarLink 协议（现有）
 * - [HiCarManager]：华为 HiCar 协议（新增骨架）
 *
 * 新增协议时只需实现本接口并在 [CarLinkManagerFactory] 注册，UI 层零改动。
 */
interface CarLinkManager {

    /** 协议事件回调，由 UI 层实现。 */
    interface Listener {
        fun onLog(msg: String)
        /**
         * 连接状态变化
         * 0 = DISCONNECTED 断开
         * 1 = CONNECTING 连接中
         * 2 = CONNECTED 已连接
         * 3 = ESTABLISHED 会话已建立
         */
        fun onConnectionState(state: Int)
        /** 车机屏显示配对码时回调。 */
        fun onPinCode(pinCode: String, carDeviceName: String)
        /** 手机画面开始投到 Surface 时回调。 */
        fun onCastStarted()
    }

    /** 设置车机屏幕物理尺寸（用于协商视频分辨率）。 */
    fun setScreenSize(w: Int, h: Int)

    /** 注入投屏 Surface（Activity 的 surfaceChanged 回调里调用）。 */
    fun setSurface(surface: Surface?)

    /** 启动协议栈：初始化 + 开始广播。权限由调用方负责。 */
    fun start()

    /** 彻底停止：断开会话 + 停止广播。 */
    fun stop()

    /** 手动刷新配对码（立即重播广播，不等看门狗周期）。 */
    fun refreshPairing()

    /** 仅断开当前会话，保持广播（手机可立即重连）。 */
    fun disconnectSession()

    /** 最近一次下发的配对码。 */
    fun getLastPinCode(): String?

    /** 协议显示名，如 "ICCOA CarLink" / "华为 HiCar"。 */
    fun getProtocolName(): String

    /**
     * 当前协议栈是否可用（SDK dex 是否已注入）。
     * 不可用时 UI 应隐藏该协议选项，避免点击后无反应。
     */
    fun isProtocolAvailable(): Boolean
}
