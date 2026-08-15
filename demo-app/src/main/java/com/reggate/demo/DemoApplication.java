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
        // 不写死任何策略参数:全部由注册库编译进的 assets/reggate_config.dat 决定。
        // 生命周期守卫与注册入口按钮在 RegGateConfig.build() 中自动启用,无需手动安装。
        RegGateConfig.init(this)
                .mainActivity(MainActivity.class)
                .loadFromConfig()
                .build();
    }
}
