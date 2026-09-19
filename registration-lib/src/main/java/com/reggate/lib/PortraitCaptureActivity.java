package com.reggate.lib;

import android.content.pm.ActivityInfo;
import android.os.Bundle;

import com.journeyapps.barcodescanner.CaptureActivity;

/**
 * 强制竖屏的扫码界面。
 * zxing-android-embedded 默认 CaptureActivity 允许随传感器旋转到横屏,
 * 此处无论启动时设备朝向如何都锁定为竖屏。
 */
public class PortraitCaptureActivity extends CaptureActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
    }
}
