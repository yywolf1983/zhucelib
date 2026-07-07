package com.reggate.demo;

import android.app.Application;
import com.reggate.lib.RegGateConfig;
import com.reggate.lib.RegistrationManager;

/**
 * Demo 应用入口：初始化注册库配置并安装生命周期守卫。
 * 公钥硬编码在此，对应 keys/test_priv.pem 的公钥。
 */
public class DemoApplication extends Application {

    private static final String TEST_PUBLIC_KEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAyfm1lRbTqZto6fTrSF8f9DYcV/XmzBrARdkc2tgPG7w/ZoVSguonCCDcGG8McRc59MI8NUPhm1tVi6yaJB79cFo5FG5MR2gwnNqZEXGn7Yht6jgwewrAEiu4YyDyof2zTehTz+5rSPUKq8ehSMqcmkIrNylEmVq9n+Id2EJSELRzgQWfsq0V6ZuE/Lt1b4+FyTW2YJZK3nZKJ15f0M6QtFzMSy45DBOb2ZcIcZjREDGu2hKbCzazWg1zQ1p1J4TVAbqI+JquGKTn1RGCOAGQg5y5rp0Ieug/zPvrIwaQBqDbiHfxJ6B0SdLUD6yWwL0clYkYSswcQkZQO4s9s+qNNQIDAQAB";

    @Override
    public void onCreate() {
        super.onCreate();
        RegGateConfig.init()
                .publicKey(TEST_PUBLIC_KEY)
                .mainActivity(MainActivity.class)
                .build();
        new RegistrationManager(this).installLifecycleGuard(this);
    }
}
