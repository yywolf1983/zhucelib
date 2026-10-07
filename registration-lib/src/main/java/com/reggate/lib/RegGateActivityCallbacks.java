package com.reggate.lib;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.util.WeakHashMap;

/**
 * 宿主生命周期守卫:
 * - 每个 Activity 启动时执行注册状态校验(enforceRegistration);
 * - 自动在宿主的第一个非库自身 Activity 上挂载注册入口按钮,
 *   使"注册按钮"成为库的固有功能,任何 app 引入并初始化后都能看到,无需宿主编写任何 UI。
 */
public final class RegGateActivityCallbacks implements Application.ActivityLifecycleCallbacks {

    private static final int FAB_TAG_ID = 0x5eed1ab; // 防重复注入的标记

    // 记录已挂载入口的 Activity,保证每个 app 至少出现一个入口且不重复挂载。
    private static final WeakHashMap<Activity, Boolean> INJECTED = new WeakHashMap<>();

    // 处于 STARTED 状态的 Activity 计数, 用于判断应用是否真正回到前台。
    private int startedCount = 0;
    // 应用退到后台后, 延迟一小段时间再重置"本次前台会话已弹窗"标记,
    // 避免 CLEAR_TASK/配置变更造成的瞬时 0 计数误把标记清空(否则门 Activity 作 LAUNCHER
    // 与新守卫并存时会再次弹窗, 即"两次弹窗")。
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable pendingReset;

    private final RegistrationManager manager;

    public RegGateActivityCallbacks(RegistrationManager manager) {
        this.manager = manager;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle bundle) {}

    @Override
    public void onActivityStarted(Activity activity) {
        // 有新的 Activity 进入前台, 取消可能挂起的后台判定。
        if (pendingReset != null) {
            handler.removeCallbacks(pendingReset);
            pendingReset = null;
        }
        startedCount++;
        manager.enforceRegistration(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {
        // 跳过库自身的界面(注册/试用/到期提示),不往上面叠入口按钮。
        Class<?> cls = activity.getClass();
        if (cls == RegistrationGateActivity.class
                || cls == RegistrationActivity.class
                || cls == TrialDialogActivity.class
                || cls == ExpiredNagActivity.class
                || cls == PortraitCaptureActivity.class) {
            return;
        }
        // 宿主声明的豁免 Activity(如启动页)不注入注册入口按钮
        if (manager.getConfig().getExemptActivities().contains(cls)) {
            return;
        }

        // 已挂载:仅同步显隐(注册返回后隐藏)。
        if (activity.findViewById(FAB_TAG_ID) != null) {
            Object v = activity.findViewById(FAB_TAG_ID);
            if (v instanceof RegGateRegisterButton) ((RegGateRegisterButton) v).syncState();
            return;
        }
        // 每个 Activity 只挂载一次,确保任何 app 都能在界面上看到入口。
        if (INJECTED.containsKey(activity)) return;

        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null) return;
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        int m = (int) (14 * activity.getResources().getDisplayMetrics().density);
        lp.setMargins(m, m, m, m);
        RegGateRegisterButton fab = new RegGateRegisterButton(activity);
        fab.setId(FAB_TAG_ID);
        content.addView(fab, lp);
        INJECTED.put(activity, Boolean.TRUE);
    }

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {
        startedCount = Math.max(0, startedCount - 1);
        if (startedCount == 0) {
            // 所有 Activity 都已停止, 延迟判定为真正进入后台; 若短时间内又有 Activity 启动
            // (如门 Activity 用 CLEAR_TASK 跳转主界面), 上面的 onActivityStarted 会取消本次重置。
            pendingReset = () -> {
                RegistrationManager.resetGateSession();
                pendingReset = null;
            };
            handler.postDelayed(pendingReset, 1000);
        }
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {}

    @Override
    public void onActivityDestroyed(Activity activity) {
        INJECTED.remove(activity);
    }
}
