# keygen-app proguard rules
-keep class com.keygen.app.** { *; }

# BouncyCastle Ed25519
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.math.** { *; }
-dontwarn org.bouncycastle.**
