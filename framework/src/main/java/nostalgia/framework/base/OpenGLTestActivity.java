package nostalgia.framework.base;

import android.content.res.Resources;
import android.app.Activity;
import android.os.Bundle;

/**
 * OpenGL 兼容性测试 Activity，用于检测设备的 OpenGL 纹理能力。
 */
public class OpenGLTestActivity extends Activity
        implements OpenGLTestView.Callback {

    OpenGLTestView view;

    // 车机密度适配：AppCompat 会给 Activity 设 overrideConfiguration，
    // 资源实例与 base context 不同，统一在 getResources 时应用缩放
    @Override
    public Resources getResources() {
        Resources res = super.getResources();
        nostalgia.framework.utils.DensityScaler.apply(res);
        return res;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        view = new OpenGLTestView(this, this);
        setContentView(view);
    }

    @Override
    public void onDetected(int i) {
        runOnUiThread(() -> {
            setResult(i);
            finish();
        });
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (view != null) {
            view.onPause();
        }
    }
}
