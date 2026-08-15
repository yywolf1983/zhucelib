package com.reggate.lib;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewTreeObserver;

/**
 * 不起眼的注册入口悬浮按钮(半透明,可透出下层内容)。
 *
 * <p>由库自动挂载到宿主界面,无需任何 Java 代码:按钮按注册状态自动显隐,
 * 点击直接进入注册界面,注册成功后消失。
 *
 * <p>样式完全由库绘制,不依赖任何宿主资源,保证任何程序引入都不会出现资源冲突。
 */
public class RegGateRegisterButton extends View {

    private static final int DOT_COLOR = 0x2FEDEDED; // 更透明圆底,几乎隐于背景
    private static final int DOT_COLOR_PRESSED = 0x3FD8D8D8;
    private static final int TEXT_COLOR = 0x66AEAEAE; // 更透明浅灰文字,仅凑近可辨
    private static final int TEXT_COLOR_PRESSED = 0x668C8C8C;
    private static final int SIZE_DP = 38;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RegistrationManager manager;
    private boolean attached = false;
    private boolean pressed = false;
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener =
            () -> {
                if (attached) refreshVisibility();
            };

    public RegGateRegisterButton(Context context) {
        super(context);
        init();
    }

    public RegGateRegisterButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public RegGateRegisterButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        paint.setColor(DOT_COLOR);
        textPaint.setColor(TEXT_COLOR);
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setTextSize(11 * getResources().getDisplayMetrics().density);
        setBackgroundColor(Color.TRANSPARENT); // 透明背景,使半透明圆底可透出下层内容
        setClickable(true);
        setFocusable(true);
        setContentDescription("注册");
        setOnClickListener(v -> {
            Context ctx = getContext();
            // 优先使用 Activity 上下文发起注册;否则降级为无操作(不崩溃)。
            if (manager != null && ctx instanceof Activity) {
                manager.startRegistration((Activity) ctx);
            }
        });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        Context ctx = getContext();
        // 用 ApplicationContext 构造 Manager,避免持有 Activity 导致泄漏。
        manager = new RegistrationManager(ctx.getApplicationContext());
        refreshVisibility();
        // 注册返回后 Activity resume 时宿主通常已调用 syncState();
        // 这里额外监听一次布局,确保首次附加即同步,并在 detach 时移除避免泄漏。
        getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
        super.onDetachedFromWindow();
    }

    /** 由宿主在 onResume 调用,确保注册返回后按钮及时隐藏。 */
    public void syncState() {
        refreshVisibility();
    }

    private void refreshVisibility() {
        if (manager == null) return;
        boolean registered = manager.getCurrentState() == RegistrationManager.State.LICENSED;
        setVisibility(registered ? GONE : VISIBLE);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = (int) (SIZE_DP * getResources().getDisplayMetrics().density);
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(getWidth(), getHeight()) / 2f;
        // 极浅圆底
        paint.setColor(pressed ? DOT_COLOR_PRESSED : DOT_COLOR);
        canvas.drawCircle(cx, cy, r * 0.85f, paint);
        // 浅灰"注册"二字,居中,不醒目
        textPaint.setColor(pressed ? TEXT_COLOR_PRESSED : TEXT_COLOR);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textY = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText("注册", cx, textY, textPaint);
    }

    @Override
    public void setPressed(boolean pressed) {
        this.pressed = pressed;
        super.setPressed(pressed);
        invalidate();
    }
}
