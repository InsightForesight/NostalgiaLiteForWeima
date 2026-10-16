package nostalgia.framework.utils;

import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.util.DisplayMetrics;

/**
 * 车机密度适配。
 * <p>
 * 威马车机屏幕为 1920x1080 @160dpi（1dp=1px），按手机设计的 dp/sp
 * 界面元素在车机视距下偏小。这里把进程共享的 DisplayMetrics 固定为
 * 目标密度，使所有 dp/sp 渲染统一放大。
 * <p>
 * 生效前提：API23 上进程内 Resources 共享同一 DisplayMetrics 实例，
 * 在 Application.onCreate 中修改即可覆盖后续 Activity 的 dp→px 换算；
 * 配置变化（旋转等）会重置 metrics，通过 ComponentCallbacks2 重新应用。
 * </p>
 */
public final class DensityScaler {

    /** 目标密度倍数：160 -> 320dpi，即所有 dp/sp 放大 2 倍 */
    private static final float TARGET_DENSITY = 2.0f;
    private static final int TARGET_DENSITY_DPI = 320;

    private DensityScaler() {
    }

    /** 在 Application.onCreate 中调用 */
    public static void install(final Application app) {
        apply(app.getResources());
        app.registerComponentCallbacks(new ComponentCallbacks2() {
            @Override
            public void onConfigurationChanged(Configuration newConfig) {
                apply(app.getResources());
            }

            @Override
            public void onLowMemory() {
            }

            @Override
            public void onTrimMemory(int level) {
            }
        });
    }

    /**
     * 对单个 Resources 应用缩放。
     * <p>API23 上各 Activity（尤其 AppCompat 设了 overrideConfiguration 后）
     * 持有独立的 Resources/DisplayMetrics，需在每个 Activity 的
     * getResources() 中调用。</p>
     */
    public static void apply(Resources res) {
        if (res == null) {
            return;
        }
        DisplayMetrics m = res.getDisplayMetrics();
        if (m == null || Math.abs(m.density - TARGET_DENSITY) < 0.001f) {
            return;
        }
        float fontScale = res.getConfiguration().fontScale;
        m.density = TARGET_DENSITY;
        m.densityDpi = TARGET_DENSITY_DPI;
        m.scaledDensity = TARGET_DENSITY * fontScale;
    }
}
