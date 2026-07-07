# Android 注册系统 - 使用说明

## 一、项目结构

```
anddex/
├── registration-lib/       # 注册库(AAR)
│   └── src/main/java/com/reggate/lib/
│       ├── CryptoUtils.java          # RSA-2048 验签
│       ├── RegistrationManager.java  # 核心状态管理
│       ├── RegGateConfig.java        # 配置类(默认值写死)
│       ├── RegGateApplication.java   # 基类(自动守卫)
│       ├── RegistrationGateActivity.java  # 入口界面
│       ├── License.java              # License 数据模型
│       └── Base32.java               # Crockford Base32 编解码
├── keygen-app/             # 注册机(APK)
│   └── src/main/java/com/keygen/app/
│       ├── KeygenUtils.java          # RSA 签名与激活码生成
│       ├── Base32.java               # Crockford Base32 编解码
│       └── MainActivity.java         # 注册机 UI(私钥文件选择)
├── requirements.md         # 需求文档
└── README.md               # 使用说明(本文档)
```

## 二、准备工作

### 2.1 生成 RSA 密钥对

```bash
# 生成私钥(PKCS#8,推荐)
openssl genpkey -algorithm RSA -out private.pem -pkeyopt rsa_keygen_bits:2048

# 提取公钥
openssl rsa -pubout -in private.pem -out public.pem
```

### 2.2 公钥配置

打开注册机 APK,选择私钥文件 `private.pem`,复制导出的公钥,然后粘贴到注册库配置中。

> **安全模型**:
> - 公钥写死在编译后的 APK 中,源码公开不影响安全性
> - 私钥由注册机从本地文件动态加载,永不写入任何 APK
> - 源码可公开,安全核心在于私钥

## 三、注册库集成

### 3.1 引入依赖

在宿主 App 的 `build.gradle` 中添加:

```gradle
dependencies {
    implementation project(':registration-lib')
}
```

### 3.2 配置注册库

**方式一:继承 RegGateApplication(推荐)**

```java
public class MyApplication extends RegGateApplication {
    @Override
    public void onCreate() {
        // 必须在 super.onCreate() 之前初始化配置
        RegGateConfig.init()
            .publicKey("MIIBIjANBgkqhkiG9w0BAQEFAAO...")  // 必填:编译时写死的公钥
            .mainActivity(MainActivity.class)               // 必填:注册通过后跳转的主界面
            .trialDays(7)                                   // 覆盖默认:试用7天(默认3天)
            .expireBehavior(RegGateConfig.ExpireBehavior.NAG_ONLY)  // 覆盖默认:到期只弹提示(默认BLOCK)
            .build();  // 必须调用 build() 完成初始化
        
        super.onCreate();  // 父类会自动安装生命周期守卫
    }
}
```

**方式二:不继承基类,手动安装守卫**

```java
public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        
        // 先初始化配置
        RegGateConfig.init()
            .publicKey("MIIBIjANBgkqhkiG9w0BAQEFAAO...")
            .mainActivity(MainActivity.class)
            .build();
        
        // 手动安装生命周期守卫
        RegistrationManager manager = new RegistrationManager(this);
        manager.installLifecycleGuard(this);
    }
}
```

### 3.3 修改启动入口

在 `AndroidManifest.xml` 中:

```xml
<!-- 原来的主 Activity -->
<activity android:name=".MainActivity">
    <intent-filter>
        <action android:name="android.intent.action.DEFAULT" />
    </intent-filter>
</activity>

<!-- 添加注册入口(置于主界面之前) -->
<activity android:name="com.reggate.lib.RegistrationGateActivity">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

### 3.4 业务级断言(可选)

在关键业务方法前添加断言,未注册则终止进程:

```java
public void doPremiumFeature() {
    RegistrationManager manager = new RegistrationManager(this);
    manager.ensureRegistered();  // 未注册/过期则终止进程
    // ... 业务逻辑
}
```

## 四、注册机使用

### 4.1 启动注册机

安装 `keygen-app` APK 到 Android 设备。

### 4.2 选择私钥文件

1. 点击「选择私钥文件」
2. 从文件管理器选择之前生成的 `private.pem`
3. 注册机自动加载并推导公钥

### 4.3 生成激活码

1. 客户机打开 App,获取安装码并复制
2. 粘贴安装码到注册机的「客户机安装码」输入框
3. 输入购买天数(0 = 永久)
4. 点击「生成激活码」
5. 复制激活码发回给客户机

### 4.4 激活码格式

激活码采用 Crockford Base32 编码,自动分组显示:

```
ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-Z0123-45678-9ABC...
```

- 支持复制时自动去除 `-` 分隔符
- 输入时自动进行字符容错(O→0、I/L→1)

## 五、配置项说明

| 方法 | 参数 | 默认值(写死) | 说明 |
|---|---|---|---|
| `publicKey()` | String | 无(必填) | 编译时写死的 RSA 公钥(Base64) |
| `mainActivity()` | Class<?> | 无(必填) | 注册通过后跳转的主界面 |
| `trialDays()` | int | 3 | 试用期天数,0=不试用 |
| `promptTiming()` | PromptTiming | FIRST_LAUNCH | 注册框弹出时机 |
| `expireBehavior()` | ExpireBehavior | BLOCK | 到期后行为 |
| `firstTrialDialogDelayMs()` | long | 0 | 首次弹框延迟(毫秒) |
| `appName()` | String | "本应用" | 应用名称(UI展示) |

**默认配置写死在库中,调用对应方法可覆盖:**

```java
// 仅设置必填项(使用所有默认值)
RegGateConfig.init()
    .publicKey("...")
    .mainActivity(MainActivity.class)
    .build();

// 覆盖部分默认配置
RegGateConfig.init()
    .publicKey("...")
    .mainActivity(MainActivity.class)
    .trialDays(7)                    // 覆盖默认:试用7天
    .expireBehavior(NAG_ONLY)        // 覆盖默认:到期只弹提示
    .build();
```

## 六、状态说明

### 6.1 查询状态

```java
RegistrationManager manager = new RegistrationManager(context);

// 查询当前状态
RegistrationManager.State state = manager.getCurrentState();
// State: LICENSED(已激活) / TRIALING(试用中) / EXPIRED(已过期) / NEED_REGISTER(需注册)

// 判断是否已激活
boolean isLicensed = manager.isLicensed();

// 获取试用期剩余天数
int trialDays = manager.getTrialRemainingDays();

// 获取许可证到期时间
Long expiryMs = manager.getLicenseExpiryMs();  // 0=永久,null=无许可证

// 获取许可证剩余天数
int licenseDays = manager.getLicenseRemainingDays();  // -1=永久
```

### 6.2 强制校验

```java
// 安装生命周期守卫(每次 Activity 启动时校验)
manager.installLifecycleGuard(appContext);

// 业务级断言(未注册/过期则终止进程)
manager.ensureRegistered();
```

## 七、剥离注册库

1. 移除 `build.gradle` 中的 `registration-lib` 依赖
2. Application 改回继承 `Application`
3. 移除 `RegGateConfig.init()` 调用
4. `AndroidManifest.xml` 改回原启动 Activity
5. 移除所有 `RegistrationManager` 相关调用

## 八、注意事项

### 8.1 私钥安全

- 私钥必须妥善保管,不能随注册机分发
- 注册机从本地文件选择私钥,不存储私钥内容
- 每次使用后建议将私钥文件移出设备

### 8.2 设备指纹

- 使用 `Build.SERIAL + Build.MODEL` 组合作为设备指纹
- 更换设备或恢复出厂设置后需重新激活

### 8.3 试用重置

- 清除 App 数据可重置试用期
- 如需防重置,需配合服务器验证

### 8.4 源码公开

- **注册库源码可公开**:安全核心在于私钥,不在源码
- **注册机源码可公开**:私钥由用户外部提供
- **公钥写死在源码中**:公钥本来就是公开的

## 九、技术栈

| 组件 | 技术 |
|---|---|
| 加密算法 | RSA-2048 + SHA256withRSA |
| 签名协议 | 挑战-响应(设备指纹 + 随机 nonce) |
| 编码方式 | Crockford Base32 |
| 密钥存储 | 私钥:本地文件;公钥:编译时硬编码 |
| 数据存储 | EncryptedSharedPreferences(AES-256) |
| 最小 SDK | API 21(Android 5.0) |
