# 注册库集成指南

## 一、前置准备

### 1.1 环境要求

- Android SDK build-tools 35.0.0+
- Android Platform android-34
- JDK 8+
- Gradle 8.9（用于构建注册库）

### 1.2 注册库目录结构

```
anddex/
├── registration-lib/           # 注册库模块
│   ├── src/main/java/com/reggate/lib/
│   ├── src/main/res/
│   └── build.gradle
├── libs/                       # 依赖库
│   ├── jetified-security-crypto-1.1.0-alpha06-runtime.jar
│   └── tink-android-1.8.0.jar
└── gradlew                     # Gradle 脚本
```

## 二、注册库改造（关键步骤）

由于宿主应用使用自定义 build.sh 构建，无法正确处理 AndroidX 依赖，必须对注册库进行改造。

### 2.1 Activity 基类修改

将所有 Activity 从 `AppCompatActivity` 改为 `Activity`：

**RegistrationGateActivity.java**
```java
// 修改前
import androidx.appcompat.app.AppCompatActivity;
public class RegistrationGateActivity extends AppCompatActivity { }

// 修改后
import android.app.Activity;
public class RegistrationGateActivity extends Activity { }
```

**RegistrationActivity.java**
```java
import android.app.Activity;
public class RegistrationActivity extends Activity { }
```

**TrialDialogActivity.java**
```java
import android.app.Activity;
public class TrialDialogActivity extends Activity { }
```

**ExpiredNagActivity.java**
```java
import android.app.Activity;
public class ExpiredNagActivity extends Activity { }
```

### 2.2 移除 MaterialComponents 样式

删除布局 XML 中的 `style="@style/Widget.MaterialComponents.Button.TextButton"`：

```xml
<!-- 修改前 -->
<Button
    android:id="@+id/reggate_btn_copy_code"
    style="@style/Widget.MaterialComponents.Button.TextButton"
    android:layout_width="0dp" />

<!-- 修改后 -->
<Button
    android:id="@+id/reggate_btn_copy_code"
    android:layout_width="0dp" />
```

涉及文件：
- `reggate_activity_registration.xml`
- `reggate_activity_trial_dialog.xml`
- `reggate_activity_expired_nag.xml`

### 2.3 修改主题配置

**themes.xml**
```xml
<!-- 修改前 -->
<style name="Theme.RegGate" parent="Theme.MaterialComponents.Light.NoActionBar">

<!-- 修改后 -->
<style name="Theme.RegGate" parent="@android:style/Theme.Holo.Light.NoActionBar">
```

```xml
<!-- 修改前 -->
<style name="Theme.RegGate.Dialog" parent="Theme.MaterialComponents.Light.Dialog">

<!-- 修改后 -->
<style name="Theme.RegGate.Dialog" parent="@android:style/Theme.Holo.Light.Dialog">
```

### 2.4 动态资源查找（关键）

创建 `RegGateResources.java` 工具类，使用 `getIdentifier()` 动态查找资源：

```java
package com.reggate.lib;

import android.content.Context;

public final class RegGateResources {
    private RegGateResources() {}

    public static int getLayoutId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "layout", ctx.getPackageName());
    }

    public static int getId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "id", ctx.getPackageName());
    }

    public static int getStringId(Context ctx, String name) {
        return ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
    }

    public static String getString(Context ctx, String name, Object... args) {
        int id = getStringId(ctx, name);
        if (id == 0) return name;
        if (args.length == 0) {
            return ctx.getString(id);
        }
        return ctx.getString(id, args);
    }
}
```

### 2.5 修改 Activity 使用动态资源

**TrialDialogActivity.java**
```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    int layoutId = RegGateResources.getLayoutId(this, "reggate_activity_trial_dialog");
    if (layoutId == 0) {
        finish();
        return;
    }
    setContentView(layoutId);

    TextView tvTitle = findViewById(RegGateResources.getId(this, "reggate_tv_trial_title"));
    tvTitle.setText(RegGateResources.getString(this, "reggate_trial_title", appName));
}
```

同样修改 `RegistrationActivity.java` 和 `ExpiredNagActivity.java`。

### 2.6 修复 handleTrialing() 逻辑

**RegistrationGateActivity.java**
```java
private void handleTrialing() {
    RegGateConfig.PromptTiming timing = manager.getConfig().getPromptTiming();
    boolean needDialog = timing == RegGateConfig.PromptTiming.FIRST_LAUNCH
            && !manager.isTrialDialogShown()
            || timing == RegGateConfig.PromptTiming.EVERY_LAUNCH;

    if (!needDialog) {
        launchMain();
        return;
    }

    if (timing == RegGateConfig.PromptTiming.FIRST_LAUNCH) {
        manager.markTrialDialogShown();
    }

    // 显示试用对话框...
}
```

## 三、构建注册库

### 3.1 编译注册库

```bash
cd anddex
./gradlew registration-lib:build
```

### 3.2 合并 jar 文件（关键步骤）

**注意：full.jar 不包含 R 类，必须合并 classes.jar 和 R.jar！**

```bash
# 找到编译后的文件
# classes.jar: build/.transforms/xxx/transformed/out/jars/classes.jar
# R.jar: build/.transforms/xxx/transformed/out/jars/libs/R.jar

# 合并到 time2/libs/registration-lib.jar
cd /Users/yy/pro-test/time2/libs
rm -rf temp && mkdir -p temp && cd temp
unzip -q /Users/yy/pro-test/anddex/registration-lib/build/.transforms/f82310f1c1dd1a263cd73657a922bab6/transformed/out/jars/classes.jar
unzip -q /Users/yy/pro-test/anddex/registration-lib/build/.transforms/f82310f1c1dd1a263cd73657a922bab6/transformed/out/jars/libs/R.jar
jar cvf ../registration-lib.jar *
rm -rf temp
```

## 四、宿主应用修改

### 4.1 修改 TimeDisplayApplication

**TimeDisplayApplication.java**
```java
package com.example.timedisplay;

import android.app.Application;
import com.reggate.lib.RegGateConfig;

public class TimeDisplayApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // mainActivity 必须设为 MainActivity，不能设为 SplashScreenActivity
        RegGateConfig.init(this).mainActivity(MainActivity.class).build();
    }
}
```

### 4.2 修改 SplashScreenActivity

**SplashScreenActivity.java**
```java
@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_splash);

    new Handler().postDelayed(new Runnable() {
        @Override
        public void run() {
            Intent intent = new Intent(SplashScreenActivity.this, 
                    com.reggate.lib.RegistrationGateActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            finish();
        }
    }, 1000);
}
```

### 4.3 修改 AndroidManifest.xml

添加注册库 Activity 声明：

```xml
<activity
    android:name="com.reggate.lib.RegistrationGateActivity"
    android:exported="true"
    android:theme="@style/Theme.RegGate.Transparent" />
<activity
    android:name="com.reggate.lib.RegistrationActivity"
    android:exported="false"
    android:theme="@style/Theme.RegGate" />
<activity
    android:name="com.reggate.lib.TrialDialogActivity"
    android:exported="false"
    android:theme="@style/Theme.RegGate.Dialog" />
<activity
    android:name="com.reggate.lib.ExpiredNagActivity"
    android:exported="false"
    android:theme="@style/Theme.RegGate.Dialog" />
```

### 4.4 修改 build.sh

移除 appcompat 依赖：

```bash
# 删除以下行：
ANDROIDX_DIR="$APP_DIR/../libs/appcompat"
ANDROIDX_JAR="$ANDROIDX_DIR/classes.jar"
...
if [ -n "$ANDROIDX_JAR" ]; then
    CLASSPATH="$CLASSPATH:$ANDROIDX_JAR"
fi

# 简化 classpath
CLASSPATH="$PLATFORM_DIR/android.jar:$OUT_DIR/classes:$REG_LIB_JAR:$REG_LIB_DIR/jetified-security-crypto-1.1.0-alpha06-runtime.jar:$REG_LIB_DIR/tink-android-1.8.0.jar"

# D8 打包移除 ANDROIDX_JAR
"$D8" \
    --classpath "$PLATFORM_DIR/android.jar" \
    $CLASS_FILES \
    "$REG_LIB_JAR" \
    "$REG_LIB_DIR/jetified-security-crypto-1.1.0-alpha06-runtime.jar" \
    "$REG_LIB_DIR/tink-android-1.8.0.jar" \
    --output "$OUT_DIR"
```

### 4.5 复制资源文件

确保注册库资源文件已复制到宿主应用：

```
app/src/main/res/layout/reggate_activity_registration.xml
app/src/main/res/layout/reggate_activity_trial_dialog.xml
app/src/main/res/layout/reggate_activity_expired_nag.xml
app/src/main/res/raw/reggate_pub_key.txt
app/src/main/res/values/strings.xml (添加注册库字符串)
app/src/main/res/values/colors.xml (添加注册库颜色)
app/src/main/res/values/themes.xml (添加注册库主题)
```

## 五、构建宿主应用

```bash
cd /Users/yy/pro-test/time2
./build.sh install
```

## 六、验证流程

### 6.1 首次启动流程

```
SplashScreenActivity → RegistrationGateActivity → TrialDialogActivity → MainActivity
```

1. 用户打开应用 → 显示启动页
2. 延迟1秒后跳转 → RegistrationGateActivity 判断状态
3. 显示试用对话框 → 显示试用剩余天数
4. 用户点击"继续试用" → 进入 MainActivity
5. 用户点击"立即注册" → 进入 RegistrationActivity

### 6.2 日志验证

```bash
adb logcat | grep -E "(RegistrationActivity|TrialDialog|RegistrationGate)"
```

预期输出：
```
I ActivityTaskManager: START u0 {flg=0x10008000 cmp=com.example.timedisplay/com.reggate.lib.RegistrationGateActivity}
I ActivityTaskManager: START u0 {cmp=com.example.timedisplay/com.reggate.lib.TrialDialogActivity (has extras)}
I ActivityTaskManager: Displayed com.example.timedisplay/com.reggate.lib.TrialDialogActivity: +388ms
```

## 七、常见问题排查

### 7.1 循环启动问题

**现象**：SplashScreenActivity 和 RegistrationGateActivity 互相启动

**原因**：`TimeDisplayApplication` 中 `mainActivity` 设为了 `SplashScreenActivity`

**解决**：改为 `MainActivity.class`

```java
RegGateConfig.init(this).mainActivity(MainActivity.class).build();
```

### 7.2 R$layout ClassNotFoundException

**现象**：`java.lang.ClassNotFoundException: com.reggate.lib.R$layout`

**原因**：full.jar 不包含 R 类

**解决**：合并 classes.jar 和 R.jar（见 3.2 节）

### 7.3 Resources$NotFoundException

**现象**：`Resource ID #0x0`

**原因**：注册库 R 类的资源 ID 与宿主应用不匹配

**解决**：使用动态资源查找（见 2.4、2.5 节）

### 7.4 AppCompatActivity ClassNotFoundException

**现象**：`java.lang.NoClassDefFoundError: androidx.appcompat.app.AppCompatActivity`

**原因**：自定义 build.sh 无法正确打包 AndroidX 库

**解决**：将 Activity 改为继承 `Activity`（见 2.1 节）

### 7.5 MaterialComponents 样式错误

**现象**：`android.view.InflateException: Binary XML file line #XX`

**原因**：XML 中引用了 MaterialComponents 样式

**解决**：移除 `style="@style/Widget.MaterialComponents.Button.TextButton"`（见 2.2 节）

## 八、注意事项

1. **jar 合并**：每次重新构建注册库后，必须重新合并 classes.jar 和 R.jar
2. **mainActivity 设置**：必须设为真正的主界面 Activity，不能设为 SplashScreenActivity
3. **资源文件**：注册库的布局、字符串、颜色、主题资源必须复制到宿主应用
4. **build.sh**：移除所有 AndroidX 相关依赖，避免打包冲突
5. **主题继承**：使用 `Theme.Holo.Light.NoActionBar` 而非 MaterialComponents 主题
