package nostalgia.framework.controllers;

import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.util.SparseIntArray;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;

import java.lang.ref.WeakReference;

import nostalgia.framework.Emulator;
import nostalgia.framework.EmulatorController;
import nostalgia.framework.GfxProfile;
import nostalgia.framework.R;
import nostalgia.framework.base.EmulatorActivity;
import nostalgia.framework.ui.gamegallery.GameDescription;
import nostalgia.framework.ui.multitouchbutton.MultitouchBtnInterface;
import nostalgia.framework.ui.multitouchbutton.MultitouchButton;
import nostalgia.framework.ui.multitouchbutton.MultitouchImageButton;
import nostalgia.framework.ui.multitouchbutton.MultitouchLayer;
import nostalgia.framework.ui.multitouchbutton.OnMultitouchEventListener;
import nostalgia.framework.ui.preferences.PreferenceUtil;
import nostalgia.framework.utils.EmuUtils;

/**
 * 触摸控制器，实现屏幕虚拟手柄的触摸输入处理。
 * <p>
 * 管理多层触摸控件（方向键、A/B键、连发键、Start/Select、快进、菜单等），
 * 支持自动隐藏、透明度调节、动态方向键切换等功能。
 * </p>
 */
public class TouchController implements EmulatorController, OnMultitouchEventListener {

    private static final String TAG = "controllers.TouchController";
    private Emulator emulator;
    private EmulatorActivity emulatorActivity;
    private int port;
    private SparseIntArray mapping;
    private SparseIntArray resIdMapping = new SparseIntArray();
    private MultitouchLayer multitouchLayer;
    private ImageView remoteIc, zapperIc, palIc, ntscIc, muteIc;
    private View view;
    private View aTurbo, bTurbo, abButton, fastForward;
    private boolean hidden = false;
    private KeyHandler keyHandler = new KeyHandler(this);

    public TouchController(EmulatorActivity emulatorActivity) {
        this.emulatorActivity = emulatorActivity;
    }

    public void onResume() {
        if (multitouchLayer != null) {
            multitouchLayer.setVibrationDuration(PreferenceUtil.getVibrationDuration(emulatorActivity));
        }
        emulator.resetKeys();
        multitouchLayer.reloadTouchProfile();
        multitouchLayer.setOpacity(PreferenceUtil.getControlsOpacity(emulatorActivity));
        multitouchLayer.setEnableStaticDPAD(!PreferenceUtil.isDynamicDPADEnable(emulatorActivity));
    }

    public void onPause() {
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
    }

    public void onDestroy() {
        multitouchLayer = null;
        emulatorActivity = null;
    }

    @Override
    public void connectToEmulator(int port, Emulator emulator) {
        this.emulator = emulator;
        this.port = port;
        mapping = emulator.getInfo().getKeyMapping();
    }

    boolean isPointerHandled(int pointerId) {
        return multitouchLayer.isPointerHandled(pointerId);
    }

    private View createView() {
        LayoutInflater inflater = (LayoutInflater)
                emulatorActivity.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        View layout = inflater.inflate(R.layout.controler_layout, null);
        multitouchLayer = layout.findViewById(R.id.touch_layer);
        MultitouchBtnInterface up = multitouchLayer.findViewById(R.id.button_up);
        up.setOnMultitouchEventlistener(this);
        resIdMapping.put(R.id.button_up, mapping.get(EmulatorController.KEY_UP));
        MultitouchBtnInterface down = multitouchLayer.findViewById(R.id.button_down);
        down.setOnMultitouchEventlistener(this);
        resIdMapping.put(R.id.button_down, mapping.get(EmulatorController.KEY_DOWN));
        MultitouchBtnInterface left = multitouchLayer.findViewById(R.id.button_left);
        left.setOnMultitouchEventlistener(this);
        resIdMapping.put(R.id.button_left, mapping.get(EmulatorController.KEY_LEFT));
        MultitouchBtnInterface right = multitouchLayer.findViewById(R.id.button_right);
        right.setOnMultitouchEventlistener(this);
        resIdMapping.put(R.id.button_right, mapping.get(EmulatorController.KEY_RIGHT));
        MultitouchBtnInterface a = multitouchLayer.findViewById(R.id.button_a);
        a.setOnMultitouchEventlistener(this);
        resIdMapping.put(R.id.button_a, mapping.get(EmulatorController.KEY_A));
        MultitouchBtnInterface b = multitouchLayer.findViewById(R.id.button_b);
        b.setOnMultitouchEventlistener(this);
        resIdMapping.put(R.id.button_b, mapping.get(EmulatorController.KEY_B));
        aTurbo = multitouchLayer.findViewById(R.id.button_a_turbo);
        ((MultitouchBtnInterface) aTurbo).setOnMultitouchEventlistener(this);
        resIdMapping.put(R.id.button_a_turbo, mapping.get(EmulatorController.KEY_A_TURBO));
        bTurbo = multitouchLayer.findViewById(R.id.button_b_turbo);
        ((MultitouchBtnInterface) bTurbo).setOnMultitouchEventlistener(this);
        resIdMapping.put(R.id.button_b_turbo, mapping.get(EmulatorController.KEY_B_TURBO));
        // 车机布局移除了 AB 组合键和快进键，保留为空安全处理
        abButton = multitouchLayer.findViewById(R.id.button_ab);
        fastForward = multitouchLayer.findViewById(R.id.button_fast_forward);
        if (fastForward != null) {
            ((MultitouchBtnInterface) fastForward).setOnMultitouchEventlistener(new OnMultitouchEventListener() {
                @Override
                public void onMultitouchExit(MultitouchBtnInterface btn) {
                    emulatorActivity.onFastForwardUp();
                }

                @Override
                public void onMultitouchEnter(MultitouchBtnInterface btn) {
                    emulatorActivity.onFastForwardDown();
                }
            });
        }
        MultitouchButton select = layout.findViewById(R.id.button_select);
        if (select != null) {
            select.setOnMultitouchEventlistener(new OnMultitouchEventListener() {
                @Override
                public void onMultitouchExit(MultitouchBtnInterface btn) {
                }

                @Override
                public void onMultitouchEnter(MultitouchBtnInterface btn) {
                    sendKey(EmulatorController.KEY_SELECT);
                }
            });
        }

        MultitouchButton start = layout.findViewById(R.id.button_start);
        start.setOnMultitouchEventlistener(new OnMultitouchEventListener() {
            @Override
            public void onMultitouchExit(MultitouchBtnInterface btn) {
            }

            @Override
            public void onMultitouchEnter(MultitouchBtnInterface btn) {
                sendKey(EmulatorController.KEY_START);
            }
        });
        View menu = layout.findViewById(R.id.button_menu);
        if (menu != null) {
            ((MultitouchBtnInterface) menu).setOnMultitouchEventlistener(new OnMultitouchEventListener() {
                @Override
                public void onMultitouchExit(MultitouchBtnInterface btn) {
                }

                @Override
                public void onMultitouchEnter(MultitouchBtnInterface btn) {
                    emulatorActivity.openGameMenu();
                }
            });
        }

        // 车机布局新增：返回游戏列表 / 重置游戏
        View exit = layout.findViewById(R.id.button_exit);
        if (exit != null) {
            ((MultitouchBtnInterface) exit).setOnMultitouchEventlistener(new OnMultitouchEventListener() {
                @Override
                public void onMultitouchExit(MultitouchBtnInterface btn) {
                }

                @Override
                public void onMultitouchEnter(MultitouchBtnInterface btn) {
                    emulatorActivity.onTouchExit();
                }
            });
        }
        View reset = layout.findViewById(R.id.button_reset);
        if (reset != null) {
            ((MultitouchBtnInterface) reset).setOnMultitouchEventlistener(new OnMultitouchEventListener() {
                @Override
                public void onMultitouchExit(MultitouchBtnInterface btn) {
                }

                @Override
                public void onMultitouchEnter(MultitouchBtnInterface btn) {
                    emulatorActivity.onTouchReset();
                }
            });
        }
        View center = layout.findViewById(R.id.button_center);
        View[] views = new View[]{menu, select, start, (View) up, (View) down, (View) right,
                (View) left, (View) a, (View) b, center, exit, reset};
        for (View view : views) {
            if (view != null) {
                view.setFocusable(false);
            }
        }

        remoteIc = layout.findViewById(R.id.ic_game_remote);
        zapperIc = layout.findViewById(R.id.ic_game_zapper);
        palIc = layout.findViewById(R.id.ic_game_pal);
        ntscIc = layout.findViewById(R.id.ic_game_ntsc);
        muteIc = layout.findViewById(R.id.ic_game_muted);
        return layout;
    }

    @Override
    public View getView() {
        if (view == null) {
            view = createView();
        }
        return view;
    }

    public void setStaticDPadEnabled(boolean enabled) {
        if (multitouchLayer != null) {
            multitouchLayer.setEnableStaticDPAD(enabled);
        }
    }

    private void sendKey(int code) {
        int cc = mapping.get(code);
        emulator.setKeyPressed(port, cc, true);
        keyHandler.sendEmptyMessageDelayed(cc, 200);
    }

    public void handleKey(int cc) {
        emulator.setKeyPressed(port, cc, false);
    }

    @Override
    public void onMultitouchEnter(MultitouchBtnInterface btn) {
        emulator.setKeyPressed(port, resIdMapping.get(btn.getId()), true);
    }

    @Override
    public void onMultitouchExit(MultitouchBtnInterface btn) {
        emulator.setKeyPressed(port, resIdMapping.get(btn.getId()), false);
    }

    @Override
    public void onGameStarted(GameDescription game) {
        GfxProfile gfxProfile = emulator.getActiveGfxProfile();
        zapperIc.setVisibility(PreferenceUtil.isZapperEnabled(emulatorActivity, game.checksum) ? View.VISIBLE : View.GONE);
        palIc.setVisibility(gfxProfile.name.equals("PAL") ? View.VISIBLE : View.GONE);
        ntscIc.setVisibility(gfxProfile.name.equals("NTSC") ? View.VISIBLE : View.GONE);
        boolean remoteVisible = PreferenceUtil.isWifiServerEnable(emulatorActivity)
                && EmuUtils.isWifiAvailable(emulatorActivity);
        remoteIc.setVisibility(remoteVisible ? View.VISIBLE : View.INVISIBLE);
        muteIc.setVisibility(PreferenceUtil.isSoundEnabled(emulatorActivity) ? View.GONE : View.VISIBLE);

        // 车机版连发键作为固定 AB 第二组常显，不随 turbo 开关隐藏
        aTurbo.setVisibility(View.VISIBLE);
        bTurbo.setVisibility(View.VISIBLE);
        aTurbo.setEnabled(true);
        bTurbo.setEnabled(true);

        if (fastForward != null) {
            if (PreferenceUtil.isFastForwardEnabled(emulatorActivity)) {
                fastForward.setVisibility(View.VISIBLE);
                fastForward.setEnabled(true);

            } else {
                fastForward.setVisibility(View.INVISIBLE);
                fastForward.setEnabled(false);
            }
        }

        if (abButton != null) {
            abButton.setVisibility(PreferenceUtil.isABButtonEnabled(emulatorActivity) ? View.VISIBLE : View.INVISIBLE);
            abButton.setEnabled(PreferenceUtil.isABButtonEnabled(emulatorActivity));
        }
        multitouchLayer.invalidate();
    }

    @Override
    public void onGamePaused(GameDescription game) {
    }

    public void hide() {
        if (!hidden) {
            emulatorActivity.runOnUiThread(() -> view.setVisibility(View.GONE));
            hidden = true;
        }
    }

    public void show() {
        if (hidden) {
            emulatorActivity.runOnUiThread(() -> view.setVisibility(View.VISIBLE));
            hidden = false;
        }
    }

    /** 按键释放处理器，使用弱引用避免内存泄漏。 */
    private static class KeyHandler extends Handler {

        WeakReference<TouchController> weakController;

        KeyHandler(TouchController controller) {
            weakController = new WeakReference<>(controller);
        }

        @Override
        public void handleMessage(Message msg) {
            TouchController controller = weakController.get();
            if (controller != null) {
                controller.handleKey(msg.what);
            }
        }
    }
}
