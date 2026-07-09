package com.reggate.lib;

import android.content.Context;
import android.content.res.Resources;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * 注册库全局配置。
 *
 * <b>设计原则:</b>
 *   - 默认配置写死在库中(公钥、试用天数、弹出时机、到期行为等)
 *   - 宿主可通过链式调用覆盖任意默认选项
 *   - 仅主界面类为必填项
 *   - 设置可以覆盖默认选项
 *
 * <pre>
 * // 最简集成(使用库内置的公钥和默认配置)
 * RegGateConfig.init(this).mainActivity(MainActivity.class).build();
 *
 * // 覆盖部分默认配置
 * RegGateConfig.init(this)
 *     .mainActivity(MainActivity.class)
 *     .publicKey("自定义公钥")      // 可选:覆盖内置公钥
 *     .trialDays(7)                 // 可选:试用7天
 *     .expireBehavior(NAG_ONLY);    // 可选:到期只弹提示
 * </pre>
 */
public final class RegGateConfig {

    /** 注册框弹出时机。 */
    public enum PromptTiming {
        /** 试用期第一次启动就弹出注册/试用框。 */
        FIRST_LAUNCH,
        /** 试用期不弹,直到试用期结束才弹出注册框。 */
        ON_EXPIRY,
        /** 每次启动都弹出注册框(适用于严格限制场景)。 */
        EVERY_LAUNCH,
    }

    /** 到期后(试用或购买时长结束)的行为。 */
    public enum ExpireBehavior {
        /** 仅弹提示框,用户可关闭后继续使用(可配合 {@link RegistrationManager#isLicensed()} 自行限制功能)。 */
        NAG_ONLY,
        /** 限制功能:无法进入主界面,必须重新激活。 */
        BLOCK,
    }

    /**
     * 默认配置(写死在库中):
     *   - publicKey: 从 res/raw/reggate_pub_key.txt 读取
     *   - trialDays: 7 天(提供试用)
     *   - promptTiming: EVERY_LAUNCH(每次启动弹框)
     *   - expireBehavior: BLOCK(到期限制功能)
     *   - firstTrialDialogDelayMs: 0(立即弹出)
     *   - appName: "本应用"
     */
    private static final int DEFAULT_TRIAL_DAYS = 7;
    private static final PromptTiming DEFAULT_PROMPT_TIMING = PromptTiming.EVERY_LAUNCH;
    private static final ExpireBehavior DEFAULT_EXPIRE_BEHAVIOR = ExpireBehavior.BLOCK;
    private static final long DEFAULT_FIRST_TRIAL_DELAY_MS = 0L;

    private static volatile ConfigHolder holder;
    private static volatile String defaultPublicKey = null;

    private final String publicKeyBase64;
    private final Class<?> mainActivityClass;
    private final int trialDays;
    private final PromptTiming promptTiming;
    private final ExpireBehavior expireBehavior;
    private final long firstTrialDialogDelayMs;
    private final String appName;
    private final ContactInfo contactInfo;

    private RegGateConfig(Builder b) {
        this.publicKeyBase64 = b.publicKeyBase64;
        this.mainActivityClass = b.mainActivityClass;
        this.trialDays = b.trialDays;
        this.promptTiming = b.promptTiming;
        this.expireBehavior = b.expireBehavior;
        this.firstTrialDialogDelayMs = b.firstTrialDialogDelayMs;
        this.appName = b.appName;
        this.contactInfo = b.contactInfo;
    }

    /**
     * 获取默认公钥(从 res/raw/reggate_pub_key.txt 读取)。
     */
    public static String getDefaultPublicKey(Context context) {
        if (defaultPublicKey == null) {
            try {
                int id = context.getResources().getIdentifier("reggate_pub_key", "raw", context.getPackageName());
                if (id == 0) {
                    throw new IOException("未找到公钥资源文件");
                }
                try (InputStream is = context.getResources().openRawResource(id);
                     BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line.trim());
                    }
                    defaultPublicKey = sb.toString();
                }
            } catch (Exception e) {
                Log.w("RegGateConfig", "读取默认公钥失败: " + e.getMessage());
                defaultPublicKey = "";
            }
        }
        return defaultPublicKey;
    }

    public static Builder init(Context context) {
        return new Builder(context);
    }

    public static Builder init() {
        return new Builder(null);
    }

    /**
     * 从配置文件初始化。配置文件优先级：
     * 1. 应用 assets/reggate_config.json（优先）
     * 2. 注册库 assets/reggate_config.json（默认）
     *
     * <pre>
     * // 使用配置文件初始化
     * RegGateConfig.init(this).mainActivity(MainActivity.class).loadFromConfig().build();
     * </pre>
     */
    public static Builder initFromConfig(Context context) {
        return new Builder(context).loadFromConfig();
    }

    public static RegGateConfig get() {
        if (holder == null)
            throw new IllegalStateException("RegGateConfig 未初始化,请在 Application.onCreate 调用 RegGateConfig.init(context).mainActivity(...).build()");
        return holder.config;
    }

    public String getPublicKeyBase64() { return publicKeyBase64; }
    public Class<?> getMainActivityClass() { return mainActivityClass; }
    public int getTrialDays() { return trialDays; }
    public PromptTiming getPromptTiming() { return promptTiming; }
    public ExpireBehavior getExpireBehavior() { return expireBehavior; }
    public long getFirstTrialDialogDelayMs() { return firstTrialDialogDelayMs; }
    public String getAppName() { return appName; }

    public long getTrialDurationMs() {
        return trialDays > 0 ? trialDays * 24L * 60L * 60L * 1000L : 0L;
    }

    public ContactInfo getContactInfo() { return contactInfo; }

    private static final class ConfigHolder {
        final RegGateConfig config;
        ConfigHolder(RegGateConfig c) { this.config = c; }
    }

    /**
     * 配置构建器。
     *
     * 默认值已写死在库中,调用对应方法可覆盖。
     * 必填项: mainActivity
     */
    public static class Builder {
        private final Context context;
        private String publicKeyBase64;
        private Class<?> mainActivityClass;
        private int trialDays = DEFAULT_TRIAL_DAYS;
        private PromptTiming promptTiming = DEFAULT_PROMPT_TIMING;
        private ExpireBehavior expireBehavior = DEFAULT_EXPIRE_BEHAVIOR;
        private long firstTrialDialogDelayMs = DEFAULT_FIRST_TRIAL_DELAY_MS;
        private String appName;
        private ContactInfo contactInfo;

        private boolean trialDaysSet = false;
        private boolean promptTimingSet = false;
        private boolean expireBehaviorSet = false;
        private boolean firstTrialDialogDelayMsSet = false;
        private boolean appNameSet = false;
        private boolean contactInfoSet = false;
        private boolean configLoaded = false;

        Builder(Context context) {
            this.context = context;
        }

        public Builder publicKey(String publicKeyBase64) {
            this.publicKeyBase64 = publicKeyBase64;
            return this;
        }

        public Builder mainActivity(Class<?> clazz) {
            this.mainActivityClass = clazz;
            return this;
        }

        public Builder trialDays(int days) {
            this.trialDays = days;
            this.trialDaysSet = true;
            return this;
        }

        public Builder promptTiming(PromptTiming t) {
            this.promptTiming = t;
            this.promptTimingSet = true;
            return this;
        }

        public Builder expireBehavior(ExpireBehavior b) {
            this.expireBehavior = b;
            this.expireBehaviorSet = true;
            return this;
        }

        public Builder firstTrialDialogDelayMs(long ms) {
            this.firstTrialDialogDelayMs = ms;
            this.firstTrialDialogDelayMsSet = true;
            return this;
        }

        public Builder appName(String name) {
            this.appName = name;
            this.appNameSet = true;
            return this;
        }

        public Builder contactInfo(ContactInfo info) {
            this.contactInfo = info;
            this.contactInfoSet = true;
            return this;
        }

        public Builder loadFromConfig() {
            if (context == null) {
                throw new IllegalStateException("loadFromConfig 需要 Context,请使用 init(context)");
            }
            JSONObject config = RegGateConfigLoader.loadConfig(context);

            if (!trialDaysSet) {
                this.trialDays = RegGateConfigLoader.getInt(config, "trial_days", DEFAULT_TRIAL_DAYS);
            }
            if (!promptTimingSet) {
                this.promptTiming = RegGateConfigLoader.getPromptTiming(config);
            }
            if (!expireBehaviorSet) {
                this.expireBehavior = RegGateConfigLoader.getExpireBehavior(config);
            }
            if (!firstTrialDialogDelayMsSet) {
                this.firstTrialDialogDelayMs = RegGateConfigLoader.getLong(config, "first_trial_delay_ms", DEFAULT_FIRST_TRIAL_DELAY_MS);
            }
            if (!appNameSet) {
                String configAppName = RegGateConfigLoader.getString(config, "app_name", "");
                if (!configAppName.isEmpty()) {
                    this.appName = configAppName;
                }
            }
            if (!contactInfoSet) {
                this.contactInfo = RegGateConfigLoader.getContactInfo(context, config);
            }

            this.configLoaded = true;
            return this;
        }

        public void build() {
            if (mainActivityClass == null)
                throw new IllegalStateException("RegGateConfig: mainActivity 未设置");

            if (!configLoaded && context != null) {
                try {
                    loadFromConfig();
                } catch (Exception e) {
                    // 配置文件加载失败,使用默认值
                }
            }

            if (publicKeyBase64 == null || publicKeyBase64.length() == 0) {
                if (context == null) {
                    throw new IllegalStateException("RegGateConfig: 未设置公钥且未传入 Context,无法读取默认公钥。请使用 init(context) 或手动设置 publicKey()");
                }
                publicKeyBase64 = getDefaultPublicKey(context);
                if (publicKeyBase64 == null || publicKeyBase64.length() == 0) {
                    throw new IllegalStateException("RegGateConfig: 无法读取默认公钥(res/raw/reggate_pub_key.txt)");
                }
            }

            if (appName == null || appName.isEmpty()) {
                if (context != null) {
                    try {
                        appName = context.getPackageManager().getApplicationLabel(context.getApplicationInfo()).toString();
                    } catch (Exception e) {
                        appName = "应用";
                    }
                } else {
                    appName = "应用";
                }
            }

            holder = new ConfigHolder(new RegGateConfig(this));
        }
    }
}
