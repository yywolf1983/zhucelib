# consumer-rules.pro - 引用本库的宿主 App 会自动应用以下规则

# 保留配置入口与反射相关类
-keep class com.reggate.lib.RegGateConfig { *; }
-keep class com.reggate.lib.RegGateConfig$Builder { *; }
-keep class com.reggate.lib.RegistrationManager$VerifyResult { *; }
-keep class com.reggate.lib.CryptoUtils$License { *; }

# 保留各 Activity(在 Manifest 中声明,但保险起见)
-keep class com.reggate.lib.RegistrationGateActivity { *; }
-keep class com.reggate.lib.RegistrationActivity { *; }
-keep class com.reggate.lib.TrialDialogActivity { *; }
-keep class com.reggate.lib.ExpiredNagActivity { *; }
# 自定义悬浮注册入口 View(宿主 XML 以全限定名引用,混淆会致引用失败)
-keep class com.reggate.lib.RegGateRegisterButton { *; }
-keep class com.reggate.lib.RegistrationManager$VerifyResult { *; }
-keep class com.reggate.lib.RegGateConfig$PromptTiming { *; }
-keep class com.reggate.lib.RegGateConfig$ExpireBehavior { *; }
-keep class com.reggate.lib.RegistrationManager$State { *; }

# BouncyCastle Ed25519
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
