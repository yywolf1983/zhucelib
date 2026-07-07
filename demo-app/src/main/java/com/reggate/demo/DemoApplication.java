package com.reggate.demo;

import android.app.Application;
import com.reggate.lib.RegGateConfig;
import com.reggate.lib.RegistrationManager;

/**
 * Demo 应用入口：最简集成注册库。
 * 只需一行代码，库自动读取内置公钥并使用默认配置。
 */
public class DemoApplication extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        RegGateConfig.init(this).mainActivity(MainActivity.class).build();
        new RegistrationManager(this).installLifecycleGuard(this);
    }
}
