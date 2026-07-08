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

### 使用配置文件

注册库支持通过加密配置文件进行配置，配置文件放置在应用的 `assets` 目录下，优先级高于代码配置。

#### 配置文件格式

配置文件为加密格式（XOR 加密），文件名：`reggate_config.dat`

原始 JSON 格式如下：

```json
{
    "trial_days": 7,
    "prompt_timing": "EVERY_LAUNCH",
    "expire_behavior": "BLOCK",
    "first_trial_delay_ms": 0,
    "contact": {
        "phone": "13800138000",
        "email": "support@example.com",
        "website": "https://example.com",
        "shop_url": "",
        "qr_code_res_name": "reggate_qr_code",
        "custom_text": "工作日 9:00-18:00"
    }
}
```

#### 配置项说明

| 配置项 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `trial_days` | int | 7 | 试用天数，0 表示不提供试用 |
| `prompt_timing` | string | EVERY_LAUNCH | 弹框时机：FIRST_LAUNCH / ON_EXPIRY / EVERY_LAUNCH |
| `expire_behavior` | string | BLOCK | 到期行为：BLOCK / NAG_ONLY |
| `first_trial_delay_ms` | long | 0 | 首次弹框延迟（毫秒） |
| `contact.phone` | string | 空 | 联系电话（点击拨打电话，支持复制） |
| `contact.email` | string | 空 | 联系邮箱（点击发送邮件，支持复制） |
| `contact.website` | string | 空 | 联系网址（点击打开浏览器，支持复制） |
| `contact.shop_url` | string | 空 | 网店地址（点击打开浏览器，支持复制） |
| `contact.qr_code_res_name` | string | 空 | 二维码资源名称（drawable 资源名） |
| `contact.custom_text` | string | 空 | 自定义说明文字 |

#### 配置文件加密

配置文件使用 **AES-256-GCM** 加密保护，防止被轻易读取。

##### 加密流程

1. **原始配置文件**（明文，项目之外）：
   - 位置：`/Users/yy/pro-test/anddex-config/reggate_config.json`
   - 这是明文配置，方便编辑和版本管理

2. **编译时自动加密**：
   - Gradle 构建时自动执行加密任务
   - 将外部明文配置加密后放入 `registration-lib/src/main/assets/reggate_config.dat`
   - 加密算法：AES-256-GCM + SHA-256 密钥派生

3. **加密脚本**：
   - 位置：`anddex/scripts/EncryptConfig.java`
   - 使用 Java 实现，确保与 Android 端解密算法一致

##### 自定义配置目录

通过环境变量指定配置目录：

```bash
export REGGATE_CONFIG_DIR=/path/to/your/config/dir
./build_all.sh
```

默认配置目录：`/Users/yy/pro-test/anddex-config`

##### 配置文件优先级

1. **应用 assets/reggate_config.dat**（优先）- 应用自定义配置
2. **注册库 assets/reggate_config.dat**（默认）- 库内置配置（编译时自动加密生成）

#### 二维码图片位置

二维码图片为本地 drawable 资源，放置位置：

| 位置 | 说明 |
|---|---|
| `app/src/main/res/drawable/` | 应用自定义二维码（优先） |
| `registration-lib/src/main/res/drawable/reggate_qr_code.xml` | 库内置默认二维码（占位图） |

使用时在配置文件中指定资源名称（不带扩展名）：

```json
"qr_code_res_name": "my_qr_code"
```

#### 联系方式显示规则

- 联系方式区域仅在至少有一个联系方式时显示
- 每个联系方式项（电话、邮箱、网址、网店）为空时不显示
- 点击联系方式可执行相应操作（拨打电话、发送邮件、打开浏览器）
- 点击"复制"可将联系方式复制到剪贴板

#### 使用方法

```java
// 方法一：使用配置文件初始化
RegGateConfig.init(this)
    .mainActivity(MainActivity.class)
    .loadFromConfig()
    .build();

// 方法二：使用配置文件初始化（简化版）
RegGateConfig.initFromConfig(this)
    .mainActivity(MainActivity.class)
    .build();

// 方法三：配置文件 + 代码覆盖
RegGateConfig.init(this)
    .mainActivity(MainActivity.class)
    .loadFromConfig()
    .trialDays(14)  // 覆盖配置文件中的值
    .build();
```

#### 配置文件放置位置

- **应用配置文件**：`app/src/main/assets/reggate_config.json`
- **库默认配置文件**：`registration-lib/src/main/assets/reggate_config.json`

#### 配置文件创建步骤

1. 在应用项目中创建 `app/src/main/assets` 目录（如果不存在）
2. 创建 `reggate_config.json` 文件，按上述格式配置
3. 在 `Application.onCreate()` 中调用 `.loadFromConfig()`

### 配置联系方式

注册页面底部可以展示联系方式，方便用户获取激活码。联系方式可以在应用中配置，也可以使用注册库内置的默认联系方式。

#### 应用中配置（优先使用）

```java
import com.reggate.lib.RegGateConfig;
import com.reggate.lib.ContactInfo;

RegGateConfig.init(this)
    .mainActivity(MainActivity.class)
    .contactInfo(ContactInfo.builder()
        .phone("13800138000")           // 联系电话
        .email("support@example.com")   // 邮箱地址
        .website("https://example.com") // 网站地址
        .qrCodeResId(R.drawable.my_qr_code) // 二维码本地资源ID
        .customText("工作日 9:00-18:00") // 自定义说明文字
        .build())
    .build();
```

#### 注册库默认联系方式

如果应用未配置联系方式，将使用注册库内置的默认值：

| 联系方式 | 默认值 |
|---|---|
| 电话 | `13800138000` |
| 邮箱 | `support@example.com` |
| 网址 | `https://example.com` |

#### 联系方式展示规则

- 只有配置了联系方式时，注册页面才会显示联系方式区域
- 用户点击电话可直接拨打电话
- 用户点击邮箱可打开邮件应用
- 用户点击网址可打开浏览器
- 二维码使用本地资源（drawable），不使用网络URL

#### 二维码资源说明

- 应用配置：将二维码图片放入应用的 `res/drawable/` 目录，使用 `R.drawable.xxx` 引用
- 默认值：注册库内置默认二维码占位图（`reggate_qr_code`）
- 不显示：如果应用和注册库都未提供二维码资源，联系方式区域将不显示二维码

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
| 联系电话 | `13800138000` | 注册页面显示的联系方式 |
| 联系邮箱 | `support@example.com` | 注册页面显示的联系方式 |
| 联系网址 | `https://example.com` | 注册页面显示的联系方式 |

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

## 避免硬编码资源

**重要**：注册库的所有资源（字符串、颜色、样式、布局）都已内置在库中。**不要在应用项目中硬编码任何 `reggate_` 前缀的资源**，否则会导致：

- 资源版本不一致，修改库后应用仍使用旧资源
- 资源冲突，编译时可能覆盖库的资源

### 正确做法

#### 1. Gradle 项目（推荐）

通过 `implementation project(':registration-lib')` 引入库后，Gradle 会自动合并库的资源。应用项目中**不需要**：

- 创建 `reggate_` 前缀的字符串资源
- 创建 `reggate_` 前缀的颜色资源  
- 创建 `reggate_` 前缀的样式资源
- 创建 `reggate_activity_*.xml` 布局文件

#### 2. 非 Gradle 项目

如果使用自定义构建脚本（如手动调用 aapt2），需要从注册库源码目录复制资源：

```bash
# 注册库资源目录
REG_LIB_SRC="/path/to/anddex/registration-lib/src/main/res"

# 合并应用资源和注册库资源
cp -r "$REG_LIB_SRC"/* "$MERGED_RES_DIR/"
```

**不要**将注册库资源复制到应用的 `src/main/res` 目录，这会导致资源硬编码。

### 需要清理的硬编码资源

如果之前已在应用中硬编码了注册库资源，请删除以下文件或内容：

| 资源类型 | 文件路径 | 需要删除的内容 |
|---|---|---|
| 字符串 | `res/values/strings.xml` | 所有 `reggate_` 前缀的 `<string>` |
| 颜色 | `res/values/colors.xml` | 所有 `reggate_` 前缀的 `<color>` |
| 样式 | `res/values/styles.xml` | 所有 `Theme.RegGate` 相关样式 |
| 布局 | `res/layout/` | `reggate_activity_*.xml` |

## 注意事项

1. **公钥安全**：公钥编译到 AAR 中，源码公开不影响安全性
2. **私钥安全**：私钥由注册机从本地文件加载，永不写入任何 APK
3. **设备绑定**：激活码与设备指纹绑定，换机需重新激活
4. **试用重置**：清除 App 数据可重置试用期
5. **资源更新**：修改注册库资源后，重新编译应用即可自动获取最新资源

## 常见问题

### Q: 应用启动时崩溃，提示"无法读取默认公钥"

**A**: 确保 `registration-lib/src/main/res/raw/reggate_pub_key.txt` 文件存在且包含有效的 Base64 公钥。

### Q: 激活后仍弹出试用框

**A**: 检查是否调用了 `isLicensed()` 判断。已激活状态不会弹出试用框。

### Q: 如何自定义试用弹窗内容

**A**: 当前试用弹窗由注册库内置。如需完全自定义，可使用 `promptTiming(ON_EXPIRY)` 关闭默认弹窗，在业务代码中自行处理。

### Q: 最小支持哪个 Android 版本

**A**: API 21（Android 5.0）。