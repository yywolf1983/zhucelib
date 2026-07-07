# 注册库集成指南

本文档详细说明如何将 `registration-lib` 集成到你的 Android 项目中。

## 前置准备

### 1. 生成 RSA 密钥对

```bash
./generate_keys.sh
```

生成的文件：
- `keys/reggate_priv.pem` - 私钥（注册机使用）
- `keys/reggate_pub.pem` - 公钥（参考）
- `keys/reggate_pub_base64.txt` - Base64 公钥

### 2. 配置公钥

将 `keys/reggate_pub_base64.txt` 的内容替换到：

```
registration-lib/src/main/res/raw/reggate_pub_key.txt
```

**注意**：公钥会被编译到 AAR 中，确保使用你自己生成的公钥。

## 集成步骤

### 步骤一：引入依赖

在宿主 App 的 `build.gradle`（模块级）中添加：

```gradle
dependencies {
    implementation project(':registration-lib')
}
```

如果你的项目是独立的，需要先将 `registration-lib` 作为模块导入：

```gradle
// settings.gradle
include ':registration-lib'
project(':registration-lib').projectDir = new File('../registration-lib')
```

### 步骤二：初始化配置

在你的 `Application` 类中添加初始化代码：

```java
import com.reggate.lib.RegGateConfig;
import com.reggate.lib.RegistrationManager;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        
        // 初始化注册库配置
        RegGateConfig.init(this)
            .mainActivity(MainActivity.class)  // 必填：注册通过后跳转的主界面
            .build();
        
        // 安装生命周期守卫（每次 Activity 启动时校验）
        new RegistrationManager(this).installLifecycleGuard(this);
    }
}
```

### 步骤三：修改启动入口

在 `AndroidManifest.xml` 中修改启动 Activity：

```xml
<!-- 原来的主 Activity，移除 MAIN 和 LAUNCHER intent-filter -->
<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.DEFAULT" />
    </intent-filter>
</activity>

<!-- 添加注册入口（置于主界面之前） -->
<activity android:name="com.reggate.lib.RegistrationGateActivity">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

### 步骤四：（可选）覆盖默认配置

```java
RegGateConfig.init(this)
    .mainActivity(MainActivity.class)
    .trialDays(14)                           // 试用 14 天（默认 7 天）
    .promptTiming(RegGateConfig.PromptTiming.FIRST_LAUNCH)  // 仅首次启动弹框
    .expireBehavior(RegGateConfig.ExpireBehavior.NAG_ONLY)  // 到期仅提示（默认 BLOCK）
    .appName("我的应用")                      // UI 展示名称
    .build();
```

## 可选功能

### 查询注册状态

```java
RegistrationManager manager = new RegistrationManager(context);

// 查询当前状态
RegistrationManager.State state = manager.getCurrentState();
// LICENSED(已激活) / TRIALING(试用中) / EXPIRED(已过期) / NEED_REGISTER(需注册)

// 判断是否已激活
boolean isLicensed = manager.isLicensed();

// 获取试用期剩余天数
int trialRemainingDays = manager.getTrialRemainingDays();

// 获取许可证到期时间
Long expiryMs = manager.getLicenseExpiryMs();  // 0=永久, null=无许可证

// 获取许可证剩余天数
int licenseRemainingDays = manager.getLicenseRemainingDays();  // -1=永久
```

### 业务级断言

在关键业务方法前添加断言，未注册则终止进程：

```java
public void doPremiumFeature() {
    RegistrationManager manager = new RegistrationManager(this);
    manager.ensureRegistered();  // 未注册/过期则终止进程
    // ... 业务逻辑
}
```

## 剥离注册库

如需移除注册库，执行以下步骤：

1. 移除 `build.gradle` 中的 `registration-lib` 依赖
2. `Application` 类改回继承 `Application`
3. 移除 `RegGateConfig.init()` 和 `installLifecycleGuard()` 调用
4. `AndroidManifest.xml` 改回原启动 Activity

## 默认配置

注册库内置以下默认配置：

| 配置项 | 默认值 | 说明 |
|---|---|---|
| 公钥 | `res/raw/reggate_pub_key.txt` | 编译时内置 |
| 试用天数 | 7 天 | 0=不提供试用 |
| 弹框时机 | EVERY_LAUNCH | 每次启动都弹框 |
| 到期行为 | BLOCK | 到期后限制功能 |
| 应用名称 | "本应用" | UI 展示名称 |

## 流程图

```
用户启动应用
    │
    ▼
RegistrationGateActivity（注册入口）
    │
    ├─ 检查激活状态
    │
    ├─ LICENSED（已激活）→ 直接跳转主界面
    │
    ├─ TRIALING（试用中）
    │   ├─ promptTiming = FIRST_LAUNCH → 首次启动弹试用框
    │   ├─ promptTiming = EVERY_LAUNCH → 每次启动弹试用框
    │   └─ promptTiming = ON_EXPIRY → 直接进入主界面
    │
    └─ EXPIRED / NEED_REGISTER
        ├─ expireBehavior = BLOCK → 弹注册界面，必须激活
        └─ expireBehavior = NAG_ONLY → 弹提示框，可关闭继续使用
```

## 注意事项

1. **公钥安全**：公钥编译到 AAR 中，源码公开不影响安全性
2. **私钥安全**：私钥由注册机从本地文件加载，永不写入任何 APK
3. **设备绑定**：激活码与设备指纹绑定，换机需重新激活
4. **试用重置**：清除 App 数据可重置试用期

## 常见问题

### Q: 应用启动时崩溃，提示"无法读取默认公钥"

**A**: 确保 `registration-lib/src/main/res/raw/reggate_pub_key.txt` 文件存在且包含有效的 Base64 公钥。

### Q: 激活后仍弹出试用框

**A**: 检查是否调用了 `isLicensed()` 判断。已激活状态不会弹出试用框。

### Q: 如何自定义试用弹窗内容

**A**: 当前试用弹窗由注册库内置。如需完全自定义，可使用 `promptTiming(ON_EXPIRY)` 关闭默认弹窗，在业务代码中自行处理。

### Q: 最小支持哪个 Android 版本

**A**: API 21（Android 5.0）。