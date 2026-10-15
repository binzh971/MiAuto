package com.miauto.app;

import android.content.Context;

/**
 * 协议工厂：根据用户选择创建对应的 {@link CarLinkManager} 实例。
 *
 * UI 层（MainActivity / IcocaProjectionActivity）只依赖此工厂和 {@link CarLinkManager}
 * 接口，不直接 new 具体协议类。新增协议时只需在此注册。
 */
public class CarLinkManagerFactory {

    public static final String PROTOCOL_ICCOA = "iccoa";
    public static final String PROTOCOL_HICAR = "hicar";

    /**
     * 创建协议管理器。
     *
     * @param protocol  {@link #PROTOCOL_ICCOA} 或 {@link #PROTOCOL_HICAR}
     * @param ctx       上下文
     * @param listener  事件回调
     * @return 协议管理器；若指定协议不可用（SDK 未注入），回退到 ICCOA。
     */
    public static CarLinkManager create(String protocol, Context ctx, CarLinkManager.Listener listener) {
        if (PROTOCOL_HICAR.equals(protocol)) {
            HiCarManager hicar = new HiCarManager(ctx, listener);
            if (hicar.isProtocolAvailable()) {
                return hicar;
            }
            // HiCar SDK 不可用时静默回退到 ICCOA
        }
        // 默认 ICCOA（现有 IccoaManager 后续可改为 implements CarLinkManager）
        // 现在先直接返回 IccoaManager，由调用方做类型适配。
        // TODO: 等 IccoaManager 改造完成实现 CarLinkManager 接口后，这里直接 new IccoaManager。
        return new IccoaManagerAdapter(ctx, listener);
    }

    /**
     * 临时适配器：让现有的 {@link IccoaManager}（带自己的 Listener 接口）
     * 也能通过 {@link CarLinkManager} 接口被调用。
     *
     * 等 IccoaManager 本身改造完成后，这个适配器就可以删掉。
     */
    private static class IccoaManagerAdapter implements CarLinkManager {
        private final IccoaManager inner;

        IccoaManagerAdapter(Context ctx, CarLinkManager.Listener listener) {
            this.inner = new IccoaManager(ctx, new IccoaManager.Listener() {
                @Override public void onLog(String msg) { listener.onLog(msg); }
                @Override public void onConnectionState(int state) { listener.onConnectionState(state); }
                @Override public void onPinCode(String pinCode, String carDeviceName) {
                    listener.onPinCode(pinCode, carDeviceName);
                }
                @Override public void onCastStarted() { listener.onCastStarted(); }
                @Override public void onNavigation(com.ucar.vehiclesdk.UCarCommon.NavigationInfo info) {
                    // TODO: 桥接到 BYD 仪表/HUD
                }
            });
        }

        @Override public void setScreenSize(int w, int h) { inner.setScreenSize(w, h); }
        @Override public void setSurface(android.view.Surface s) { inner.setSurface(s); }
        @Override public void start() { inner.start(); }
        @Override public void stop() { inner.stop(); }
        @Override public void refreshPairing() { inner.refreshPairing(); }
        @Override public void disconnectSession() { inner.disconnectSession(); }
        @Override public String getLastPinCode() { return inner.getLastPinCode(); }
        @Override public String getProtocolName() { return "ICCOA CarLink"; }
        @Override public boolean isProtocolAvailable() { return true; }
    }
}
