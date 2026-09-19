package com.reggate.lib;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.util.DisplayMetrics;
import android.view.Window;
import android.view.WindowManager;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 安装码二维码弹窗: 居中大卡布局, 支持保存到相册 / 发送(分享)。
 * 保存与分享的实际执行委托给宿主 Activity(权限、FileProvider)。
 */
class QrCodeDialog extends Dialog {

    /**
     * 生成二维码并弹出弹窗; 编码失败时静默提示, 不弹窗。
     *
     * @param rawCode      无分组的原始安装码
     * @param displayCode  分组后的展示文本
     */
    static void show(RegistrationActivity activity, String rawCode, String displayCode,
                     String fileBaseName) {
        final Bitmap bitmap;
        try {
            bitmap = QrCodeUtils.encode(rawCode, 600);
        } catch (Exception e) {
            Toast.makeText(activity,
                    RegGateResources.getString(activity, "reggate_qr_load_failed"),
                    Toast.LENGTH_SHORT).show();
            return;
        }
        new QrCodeDialog(activity, bitmap, displayCode, fileBaseName).show();
    }

    private QrCodeDialog(RegistrationActivity activity, Bitmap bitmap, String displayCode,
                         String fileBaseName) {
        super(activity, resolveTheme(activity));
        Context ctx = activity;

        int layoutId = RegGateResources.getLayoutId(ctx, "reggate_dialog_qr");
        if (layoutId != 0) setContentView(layoutId);

        ImageView ivImage = findViewById(RegGateResources.getId(ctx, "reggate_qr_dialog_image"));
        TextView tvCode = findViewById(RegGateResources.getId(ctx, "reggate_qr_dialog_code"));
        TextView tvTitle = findViewById(RegGateResources.getId(ctx, "reggate_qr_dialog_title"));
        TextView tvSubtitle = findViewById(RegGateResources.getId(ctx, "reggate_qr_dialog_subtitle"));
        View btnSave = findViewById(RegGateResources.getId(ctx, "reggate_qr_dialog_save"));
        View btnShare = findViewById(RegGateResources.getId(ctx, "reggate_qr_dialog_share"));
        TextView btnSaveLabel = findViewById(RegGateResources.getId(ctx, "reggate_qr_dialog_save_label"));
        TextView btnShareLabel = findViewById(RegGateResources.getId(ctx, "reggate_qr_dialog_share_label"));
        TextView btnClose = findViewById(RegGateResources.getId(ctx, "reggate_qr_dialog_close"));

        if (tvTitle != null) {
            tvTitle.setText(RegGateResources.getString(ctx, "reggate_request_qr_title"));
        }
        if (tvSubtitle != null) {
            tvSubtitle.setText(RegGateResources.getString(ctx, "reggate_request_qr_subtitle"));
        }
        if (ivImage != null) ivImage.setImageBitmap(bitmap);
        if (tvCode != null) tvCode.setText(displayCode);
        if (btnSave != null) {
            if (btnSaveLabel != null) {
                btnSaveLabel.setText(RegGateResources.getString(ctx, "reggate_qr_dialog_save"));
            }
            btnSave.setOnClickListener(v -> activity.saveQrBitmap(bitmap, fileBaseName));
        }
        if (btnShare != null) {
            if (btnShareLabel != null) {
                btnShareLabel.setText(RegGateResources.getString(ctx, "reggate_qr_dialog_share"));
            }
            btnShare.setOnClickListener(v -> activity.shareQrBitmap(
                    bitmap, fileBaseName,
                    RegGateResources.getString(ctx, "reggate_qr_chooser_title")));
        }
        if (btnClose != null) {
            btnClose.setText(RegGateResources.getString(ctx, "reggate_qr_dialog_close"));
            btnClose.setOnClickListener(v -> dismiss());
        }

        setCanceledOnTouchOutside(true);
    }

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        // 两侧留白 32dp, 卡片浮于暗化背景之上
        Window window = getWindow();
        if (window == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        window.getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        int margin = (int) (32 * dm.density);
        WindowManager.LayoutParams lp = window.getAttributes();
        lp.width = Math.min(dm.widthPixels - margin * 2, (int) (420 * dm.density));
        window.setAttributes(lp);
        // 根布局自带圆角白底, 窗口背景必须透明
        window.setBackgroundDrawableResource(android.R.color.transparent);
    }

    private static int resolveTheme(Context ctx) {
        return ctx.getResources().getIdentifier(
                "Theme.RegGate.RegistrationDialog", "style", ctx.getPackageName());
    }
}
