package nostalgia.framework.base;

import android.content.res.Resources;

import androidx.appcompat.app.AppCompatActivity;

import nostalgia.framework.utils.DensityScaler;

/**
 * 车机密度适配基类。
 * <p>
 * androidx 的 AppCompatDelegate 会给 Activity 注入 overrideConfiguration，
 * 使其持有与 Application/base context 不同的 Resources/DisplayMetrics，
 * 导致只改 Application 的缩放失效。统一在 getResources() 出口应用缩放，
 * 无论资源实例是否被 AppCompat 重新创建都能生效。
 * </p>
 */
public abstract class ScaledAppCompatActivity extends AppCompatActivity {

    @Override
    public Resources getResources() {
        Resources res = super.getResources();
        DensityScaler.apply(res);
        return res;
    }
}
