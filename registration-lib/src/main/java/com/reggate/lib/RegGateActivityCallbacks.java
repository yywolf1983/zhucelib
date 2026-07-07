package com.reggate.lib;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

public final class RegGateActivityCallbacks implements Application.ActivityLifecycleCallbacks {

    private final RegistrationManager manager;

    public RegGateActivityCallbacks(RegistrationManager manager) {
        this.manager = manager;
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle bundle) {}

    @Override
    public void onActivityStarted(Activity activity) {
        manager.enforceRegistration(activity);
    }

    @Override
    public void onActivityResumed(Activity activity) {}

    @Override
    public void onActivityPaused(Activity activity) {}

    @Override
    public void onActivityStopped(Activity activity) {}

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle bundle) {}

    @Override
    public void onActivityDestroyed(Activity activity) {}
}
