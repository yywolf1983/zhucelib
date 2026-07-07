package com.reggate.lib;

import android.app.Application;

/**
 * 提供绝对控制权的 Application 基类。
 *
 * <b>使用方式:</b>
 * <pre>
 * public class MyApp extends RegGateApplication {
 *     @Override
 *     public void onCreate() {
 *         // 必须在 super.onCreate() 之前初始化配置
 *         RegGateConfig.init()
 *             .publicKey("MIIBIjANBgkqhkiG9w0BAQEFAAO...")
 *             .mainActivity(MainActivity.class)
 *             .build();
 *         super.onCreate();
 *     }
 * }
 * </pre>
 *
 * <b>控制权机制:</b>
 *   1. 安装生命周期守卫:每次 Activity 启动时强制校验注册状态
 *   2. 未注册/过期则终止进程,无法绕过
 *   3. 提供业务级断言 ensureRegistered()
 *
 * <b>剥离方式:</b>
 *   1. 宿主 Application 改回继承 Application
 *   2. 移除 RegGateConfig.init(...) 调用
 *   3. Manifest 改回原启动 Activity
 */
public class RegGateApplication extends Application {

    private RegistrationManager manager;

    @Override
    public void onCreate() {
        super.onCreate();
        try {
            manager = new RegistrationManager(this);
            manager.installLifecycleGuard(this);
        } catch (IllegalStateException e) {
            // RegGateConfig 未初始化,进程将在首次 Activity 启动时被终止
        }
    }

    public RegistrationManager getRegistrationManager() {
        if (manager == null) {
            throw new IllegalStateException("RegGateConfig 未初始化,请在 super.onCreate() 之前调用 RegGateConfig.init()");
        }
        return manager;
    }

    public void ensureRegistered() {
        if (manager != null) {
            manager.ensureRegistered();
        }
    }
}
