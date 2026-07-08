# Android 注册系统

基于 RSA-2048 的 Android 应用注册验证系统，包含注册库和注册机两个组件。

## 项目结构

```
anddex/
├── registration-lib/          # 注册库(AAR) - 核心验证逻辑
│   ├── src/main/java/com/reggate/lib/
│   │   ├── CryptoUtils.java           # RSA-2048 验签
│   │   ├── RegistrationManager.java   # 核心状态管理
│   │   ├── RegGateConfig.java         # 配置类(默认值写死)
│   │   ├── RegGateApplication.java    # 基类(自动守卫)
│   │   ├── RegistrationGateActivity.java  # 入口界面
│   │   ├── TrialDialogActivity.java   # 试用弹窗
│   │   ├── License.java               # License 数据模型
│   │   └── Base32.java                # Crockford Base32 编解码
│   └── src/main/res/raw/reggate_pub_key.txt  # 内置公钥
├── keygen-app/                # 注册机(APK) - 生成激活码
│   └── src/main/java/com/keygen/app/
│       ├── KeygenUtils.java           # RSA 签名与激活码生成
│       ├── Base32.java                # Crockford Base32 编解码
│       └── MainActivity.java          # 注册机 UI
├── demo-app/                  # 演示应用
│   ├── build.sh              # 构建脚本
│   └── src/main/java/com/reggate/demo/
│       ├── DemoApplication.java       # 初始化示例
│       └── MainActivity.java          # 状态展示界面
├── generate_keys.sh          # RSA 密钥生成脚本
├── build.sh                  # 项目构建脚本
├── requirements.md           # 需求文档
└── README.md                 # 使用说明
```

## 快速开始

### 1. 生成密钥对

```bash
./generate_keys.sh
```

生成的文件：
- `keys/reggate_priv.pem` - 私钥（注册机使用）
- `keys/reggate_pub.pem` - 公钥（参考）
- `keys/reggate_pub_base64.txt` - Base64 公钥

### 2. 配置公钥

将生成的公钥替换到 `registration-lib/src/main/res/raw/reggate_pub_key.txt`。

### 3. 构建项目

```bash
./build.sh
```

构建产物：
- `registration-lib/build/outputs/aar/registration-lib-debug.aar`
- `keygen-app/build/outputs/apk/debug/keygen-app-debug.apk`
- `demo-app/build/outputs/apk/debug/demo-app-debug.apk`

## 注册库集成

### 步骤一：引入依赖

```gradle
dependencies {
    implementation project(':registration-lib')
}
```

### 步骤二：初始化

```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        RegGateConfig.init(this)
            .mainActivity(MainActivity.class)
            .build();
        new RegistrationManager(this).installLifecycleGuard(this);
    }
}
```

### 步骤三：修改启动入口

```xml
<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.DEFAULT" />
    </intent-filter>
</activity>

<activity android:name="com.reggate.lib.RegistrationGateActivity">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

### 集成完成

无需手动设置公钥，无需配置试用参数，所有配置由库自动完成。

> **重要**：注册库的所有资源（字符串、颜色、样式、布局）都已内置在库中。**不要在应用项目中硬编码任何 `reggate_` 前缀的资源**，否则会导致资源版本不一致。详见 [INSTALL.md](INSTALL.md) 中的「避免硬编码资源」章节。

## 配置项

| 方法 | 参数 | 默认值 | 说明 |
|---|---|---|---|
| `mainActivity()` | Class<?> | 无(必填) | 注册通过后跳转的主界面 |
| `trialDays()` | int | 7 | 试用期天数,0=不试用 |
| `promptTiming()` | PromptTiming | EVERY_LAUNCH | 注册框弹出时机 |
| `expireBehavior()` | ExpireBehavior | BLOCK | 到期后行为 |
| `appName()` | String | "本应用" | 应用名称(UI展示) |

**覆盖默认配置**：

```java
RegGateConfig.init(this)
    .mainActivity(MainActivity.class)
    .trialDays(14)
    .promptTiming(RegGateConfig.PromptTiming.FIRST_LAUNCH)
    .expireBehavior(RegGateConfig.ExpireBehavior.NAG_ONLY)
    .build();
```

## 查询注册状态

```java
RegistrationManager manager = new RegistrationManager(context);

RegistrationManager.State state = manager.getCurrentState();
// LICENSED(已激活) / TRIALING(试用中) / EXPIRED(已过期) / NEED_REGISTER(需注册)

boolean isLicensed = manager.isLicensed();
int trialRemainingDays = manager.getTrialRemainingDays();
Long expiryMs = manager.getLicenseExpiryMs();  // 0=永久
int licenseRemainingDays = manager.getLicenseRemainingDays();  // -1=永久
```

## 注册机使用

1. 安装 `keygen-app` APK
2. 点击「选择私钥文件」，选择 `reggate_priv.pem`
3. 输入客户机的安装码
4. 输入购买天数（0 = 永久）
5. 点击「生成激活码」
6. 复制激活码发给客户机

> **私钥路径会自动记住**，下次启动无需重新选择。

## 安全模型

| 要素 | 位置 | 说明 |
|---|---|---|
| 公钥 | 编译时内置到注册库 | 源码可公开，公钥本来就是公开的 |
| 私钥 | 注册机从本地文件加载 | 私钥永不写入任何 APK |
| 签名 | SHA256withRSA | 即使看到源码也无法伪造激活码 |
| 设备绑定 | deviceId 参与签名 | 一个激活码只能在一台设备上使用 |
| 防重放 | nonce 参与签名 | 每次启动的安装码都不同 |
| 存储 | EncryptedSharedPreferences | 激活码用 AES-256 加密存储 |

## 剥离注册库

1. 移除 `build.gradle` 中的 `registration-lib` 依赖
2. Application 改回继承 `Application`
3. 移除 `RegGateConfig.init()` 和 `installLifecycleGuard()` 调用
4. `AndroidManifest.xml` 改回原启动 Activity

## 技术栈

| 组件 | 技术 |
|---|---|
| 加密算法 | RSA-2048 + SHA256withRSA |
| 签名协议 | 挑战-响应(设备指纹 + 随机 nonce) |
| 编码方式 | Crockford Base32 |
| 最小 SDK | API 21(Android 5.0) |