package dev.transcribe;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Animated waveform indicator: 7 vertical bars whose heights oscillate
 * in a sine-wave pattern driven by an audio level (0.0 – 1.0).
 *
 * At level 0 all bars are at minimum height; at level 1 the tallest bar
 * fills the view height. The bars phase-shift gives a pleasing "wave" look.
 */
public class WaveformView extends View {

    private static final int   BAR_COUNT   = 7;
    private static final float BAR_WIDTH_DP = 4f;
    private static final float BAR_GAP_DP  = 8f;
    private static final float MIN_HEIGHT_FRACTION = 0.12f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] currentHeights = new float[BAR_COUNT];
    private final float[] targetHeights  = new float[BAR_COUNT];
    private final RectF   barRect        = new RectF();

    private float barWidthPx;
    private float barGapPx;
    private float cornerPx;

    private float level = 0f;
    private float phase = 0f;

    private final ValueAnimator animator;

    public WaveformView(Context context) {
        this(context, null);
    }

    public WaveformView(Context context, AttributeSet attrs) {
        super(context, attrs);

        float density = context.getResources().getDisplayMetrics().density;
        barWidthPx = BAR_WIDTH_DP * density;
        barGapPx   = BAR_GAP_DP  * density;
        cornerPx   = barWidthPx / 2f;

        paint.setColor(context.getColor(R.color.cl_accent));

        // Continuous animator to smoothly update bar heights
        animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(120);
        animator.setRepeatCount(ValueAnimator.INFINITE);
        animator.addUpdateListener(a -> {
            phase += 0.18f;
            updateTargetHeights();
            interpolateHeights();
            invalidate();
        });
    }

    /** Set audio level in [0, 1]. Call from main thread every ~80 ms. */
    public void setLevel(float newLevel) {
        this.level = Math.max(0f, Math.min(1f, newLevel));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        animator.cancel();
    }

    private void updateTargetHeights() {
        for (int i = 0; i < BAR_COUNT; i++) {
            // Each bar gets a sine offset based on its index and the running phase
            double sine = Math.sin(phase + i * Math.PI / 3.0);
            float dynamic = (float) ((sine + 1.0) / 2.0) * level;
            targetHeights[i] = MIN_HEIGHT_FRACTION + dynamic * (1f - MIN_HEIGHT_FRACTION);
        }
    }

    private void interpolateHeights() {
        for (int i = 0; i < BAR_COUNT; i++) {
            currentHeights[i] += (targetHeights[i] - currentHeights[i]) * 0.35f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int viewH = getHeight();
        if (viewH == 0) return;

        // Center the bar cluster horizontally
        float totalWidth = BAR_COUNT * barWidthPx + (BAR_COUNT - 1) * barGapPx;
        float startX = (getWidth() - totalWidth) / 2f;

        for (int i = 0; i < BAR_COUNT; i++) {
            float barH = currentHeights[i] * viewH;
            float left  = startX + i * (barWidthPx + barGapPx);
            float right = left + barWidthPx;
            float top   = (viewH - barH) / 2f;
            float bottom = top + barH;
            barRect.set(left, top, right, bottom);
            canvas.drawRoundRect(barRect, cornerPx, cornerPx, paint);
        }
    }
}
