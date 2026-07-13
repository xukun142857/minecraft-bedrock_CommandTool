package command.plus;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.Queue;

public final class FloatToast {

    private static TextView view; // 当前正在显示的视图
    private static WindowManager windowManager;
    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static Runnable currentHideRunnable; // 当前条目的定时隐藏任务

    // 排队相关
    private static final Queue<ToastItem> queue = new ArrayDeque<>();
    private static boolean isShowing = false;

    // 可调参数
    private static final long DEFAULT_DURATION = 2000L;    // 显示时长（ms）
    private static final long FADE_IN_MS = 250L;          // 淡入时长
    private static final long FADE_OUT_MS = 200L;         // 淡出时长
    private static final float DEFAULT_TARGET_ALPHA = 1f; // 最终不透明度（0 - 1）

    // 最大队列长度（可选，防止无限积压）。设为 50，必要时可以修改或置为 Integer.MAX_VALUE
    private static final int MAX_QUEUE_SIZE = 50;

    private FloatToast() { }

    // 简单调用
    public static void show(Context context, String msg) {
        show(context, msg, DEFAULT_DURATION, DEFAULT_TARGET_ALPHA);
    }

    // 指定时长
    public static void show(Context context, String msg, long durationMs) {
        show(context, msg, durationMs, DEFAULT_TARGET_ALPHA);
    }

    // 指定时长 + 指定目标 alpha（例如 0.85f 或 0.7f）
    public static void show(Context context, String msg, long durationMs, float targetAlpha) {
        if (context == null || msg == null) return;
        final Context appContext = context.getApplicationContext();
        final ToastItem item = new ToastItem(msg, Math.max(0, durationMs), clamp(targetAlpha, 0f, 1f));

        synchronized (queue) {
            if (queue.size() >= MAX_QUEUE_SIZE) {
                // 队列已满：丢弃最老的一条（可替换为丢弃当前新条）
                queue.poll();
            }
            queue.offer(item);
        }

        handler.post(() -> {
            // 如果当前没有在显示，立即尝试显示下一个
            if (!isShowing) {
                showNext(appContext);
            }
        });
    }

    // 立即（带淡出）关闭并清空队列
    public static void dismiss() {
        handler.post(() -> {
            // 清空待显示队列
            synchronized (queue) {
                queue.clear();
            }
            // 取消当前的定时隐藏
            if (currentHideRunnable != null) {
                handler.removeCallbacks(currentHideRunnable);
                currentHideRunnable = null;
            }
            // 如果有正在显示的 view，则淡出并移除
            if (view != null) {
                try {
                    view.animate()
                            .alpha(0f)
                            .setDuration(FADE_OUT_MS)
                            .withEndAction(() -> removeViewImmediate())
                            .start();
                } catch (Exception e) {
                    removeViewImmediate();
                }
            } else {
                removeViewImmediate();
            }
        });
    }

    // 显示队列中下一个（内部方法）
    private static void showNext(Context appContext) {
        ToastItem item;
        synchronized (queue) {
            item = queue.poll();
        }
        if (item == null) {
            isShowing = false;
            return;
        }

        isShowing = true;

        // 清理可能遗留的 view/handler
        if (currentHideRunnable != null) {
            handler.removeCallbacks(currentHideRunnable);
            currentHideRunnable = null;
        }
        if (windowManager != null && view != null) {
            try {
                windowManager.removeViewImmediate(view);
            } catch (Exception ignored) {
                try { windowManager.removeView(view); } catch (Exception ignored2) {}
            }
            view = null;
            windowManager = null;
        }

        try {
            windowManager = (WindowManager) appContext.getSystemService(Context.WINDOW_SERVICE);

            TextView tv = new TextView(appContext);
            tv.setText(item.msg);
            tv.setTextSize(14);
            int padH = dpToPx(appContext, 16);
            int padV = dpToPx(appContext, 10);
            tv.setPadding(padH, padV, padH, padV);
            tv.setBackgroundResource(android.R.drawable.toast_frame);
            tv.setAlpha(0f);
            tv.setGravity(Gravity.CENTER);

            view = tv;

            final int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    : WindowManager.LayoutParams.TYPE_PHONE;

            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    type,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
            );

            // 以屏幕高度比例放在中下方（使用 bottom gravity 并设置 y 为屏幕高度比例）
            params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            // 这里用屏幕高度的 12% 作为底部偏移（向上），你可以调整 0.08f - 0.18f 之间的数值
            float verticalRatio = 0.12f;
            int screenH = appContext.getResources().getDisplayMetrics().heightPixels;
            params.y = (int) (screenH * verticalRatio);

            // 兼容一些厂商对 LayoutParams 的限制：确保宽度/高度不会太大（wrap_content 已足够）
            windowManager.addView(view, params);

            // 淡入到目标 alpha
            view.animate()
                    .alpha(item.targetAlpha)
                    .setDuration(FADE_IN_MS)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();

            // 安排隐藏：显示 item.duration 后开始淡出；淡出结束后移除 view 并显示下一个
            currentHideRunnable = () -> {
                if (view == null) {
                    currentHideRunnable = null;
                    // 继续下一个
                    handler.post(() -> {
                        isShowing = false;
                        showNext(appContext);
                    });
                    return;
                }

                try {
                    view.animate()
                            .alpha(0f)
                            .setDuration(FADE_OUT_MS)
                            .withEndAction(() -> {
                                removeViewImmediate();
                                // 显示队列中的下一个
                                handler.post(() -> {
                                    isShowing = false;
                                    showNext(appContext);
                                });
                            })
                            .start();
                } finally {
                    currentHideRunnable = null;
                }
            };

            handler.postDelayed(currentHideRunnable, item.durationMs);
        } catch (Exception e) {
            // 常见错误：没有 SYSTEM_ALERT_WINDOW 权限 或 addView 抛出异常
            e.printStackTrace();
            // 清理状态并尝试显示下一个（避免卡死队列）
            removeViewImmediate();
            handler.post(() -> {
                isShowing = false;
                showNext(appContext);
            });
        }
    }

    // 同步移除（安全包裹）
    private static void removeViewImmediate() {
        try {
            if (windowManager != null && view != null) {
                try {
                    windowManager.removeViewImmediate(view);
                } catch (Exception ex) {
                    try { windowManager.removeView(view); } catch (Exception ignore) {}
                }
            }
        } finally {
            view = null;
            windowManager = null;
        }
    }

    // dp 转 px
    private static int dpToPx(Context ctx, float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, ctx.getResources().getDisplayMetrics());
    }

    private static float clamp(float v, float a, float b) {
        return Math.max(a, Math.min(b, v));
    }

    // 队列条目类
    private static class ToastItem {
        final String msg;
        final long durationMs;
        final float targetAlpha;

        ToastItem(String msg, long durationMs, float targetAlpha) {
            this.msg = msg;
            this.durationMs = durationMs;
            this.targetAlpha = targetAlpha;
        }
    }
}