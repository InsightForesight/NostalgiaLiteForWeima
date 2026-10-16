package nostalgia.framework.bt;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.Bundle;
import android.util.SparseIntArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.ActionBar;

import java.util.ArrayList;
import java.util.List;

import nostalgia.framework.R;
import nostalgia.framework.base.ScaledAppCompatActivity;

/**
 * 手柄按键映射设置页。
 * <p>
 * 首行选择预设方案（Switch / Xbox / PlayStation，按手柄型号自动识别）。
 * 每个功能可绑定多个物理键：点击功能项后按下手柄按键追加绑定，
 * 长按清空该功能全部绑定；末行恢复默认。
 * 方向键与斜向为内置预设（十字键+左摇杆），不在列表中。
 * 映射按手柄型号（vid/pid）分别存储，接入新手柄可独立配置。
 * </p>
 */
public class GamepadMappingActivity extends ScaledAppCompatActivity
        implements OnItemClickListener, OnItemLongClickListener {

    private String deviceKey = "default";
    private String deviceName = "";
    /** emu功能码 -> 绑定的 keyCode 列表（支持一键多绑） */
    private final SparseIntArray effective = new SparseIntArray(); // keyCode -> emu
    private Adapter adapter;
    private Dialog bindDialog;

    /** 行布局：0=预设方案，1..N=功能项，最后一行=恢复默认 */
    private int presetRow() {
        return 0;
    }

    private int functionRow(int position) {
        return position - 1;
    }

    private int resetRow() {
        return GamepadKeyMap.FUNCTIONS.length + 1;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.gamepad_mapping_activity);
        ActionBar bar = getSupportActionBar();
        if (bar != null) {
            bar.setDisplayHomeAsUpEnabled(true);
        }
        pickDevice();
        reload();

        TextView dev = findViewById(R.id.gm_device);
        dev.setText(getString(R.string.gm_device_label,
                deviceName.isEmpty() ? getString(R.string.gm_no_device) : deviceName));

        ListView list = findViewById(R.id.gm_list);
        adapter = new Adapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener(this);
        list.setOnItemLongClickListener(this);
    }

    /** 选择当前要配置的手柄：取系统中第一个游戏手柄设备。 */
    private void pickDevice() {
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice d = InputDevice.getDevice(id);
            if (d != null && !d.isVirtual()
                    && (d.getSources() & InputDevice.SOURCE_GAMEPAD)
                            == InputDevice.SOURCE_GAMEPAD) {
                deviceKey = GamepadKeyMap.deviceKey(d);
                deviceName = d.getName();
                return;
            }
        }
    }

    /** 重新加载有效映射（keyCode -> emuCode）。 */
    private void reload() {
        effective.clear();
        SparseIntArray m = GamepadKeyMap.load(this, deviceKey);
        for (int i = 0; i < m.size(); i++) {
            effective.put(m.keyAt(i), m.valueAt(i));
        }
    }

    /** 某功能当前绑定的全部物理键。 */
    private List<Integer> keysOf(int emuCode) {
        List<Integer> res = new ArrayList<>();
        for (int i = 0; i < effective.size(); i++) {
            if (effective.valueAt(i) == emuCode) {
                res.add(effective.keyAt(i));
            }
        }
        return res;
    }

    private String keysLabel(int emuCode) {
        List<Integer> keys = keysOf(emuCode);
        StringBuilder sb = new StringBuilder();
        for (int k : keys) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(GamepadKeyMap.keyLabel(deviceKey, k));
        }
        return sb.toString();
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (position == presetRow()) {
            showPresetDialog();
        } else if (position == resetRow()) {
            GamepadKeyMap.reset(this, deviceKey);
            reload();
            adapter.notifyDataSetChanged();
        } else {
            showBindDialog(functionRow(position));
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        int fi = functionRow(position);
        if (position > presetRow() && position < resetRow() && fi >= 0) {
            for (int k : keysOf(GamepadKeyMap.FUNCTIONS[fi])) {
                GamepadKeyMap.bind(this, deviceKey, k, -1);
            }
            reload();
            adapter.notifyDataSetChanged();
            return true;
        }
        return false;
    }

    /** 预设方案选择对话框。 */
    private void showPresetDialog() {
        String current = GamepadKeyMap.presetFor(this, deviceKey);
        int checked = 1;
        for (int i = 0; i < GamepadKeyMap.PRESETS.length; i++) {
            if (GamepadKeyMap.PRESETS[i].equals(current)) {
                checked = i;
            }
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.gm_preset_title)
                .setSingleChoiceItems(GamepadKeyMap.PRESET_LABELS, checked,
                        (dialog, which) -> {
                            GamepadKeyMap.setPreset(this, deviceKey,
                                    GamepadKeyMap.PRESETS[which]);
                            reload();
                            adapter.notifyDataSetChanged();
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** 弹出"请按键"对话框，把按下的键追加绑定到该功能。 */
    private void showBindDialog(final int fi) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.gm_press_key,
                GamepadKeyMap.FUNCTION_NAMES[fi]));
        builder.setNegativeButton(android.R.string.cancel, null);
        final Dialog d = builder.create();
        d.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN
                    || keyCode == KeyEvent.KEYCODE_BACK) {
                return false;
            }
            int emu = GamepadKeyMap.FUNCTIONS[fi];
            int bound = effective.get(keyCode, Integer.MIN_VALUE);
            if (bound == emu) {
                // 已绑在本功能上，无需处理
            } else {
                if (bound != Integer.MIN_VALUE) {
                    GamepadKeyMap.bind(this, deviceKey, keyCode, -1);
                }
                GamepadKeyMap.bind(this, deviceKey, keyCode, emu);
            }
            reload();
            adapter.notifyDataSetChanged();
            dialog.dismiss();
            return true;
        });
        bindDialog = d;
        d.show();
    }

    private String presetLabel() {
        String p = GamepadKeyMap.presetFor(this, deviceKey);
        for (int i = 0; i < GamepadKeyMap.PRESETS.length; i++) {
            if (GamepadKeyMap.PRESETS[i].equals(p)) {
                return GamepadKeyMap.PRESET_LABELS[i];
            }
        }
        return p;
    }

    @Override
    protected void onDestroy() {
        if (bindDialog != null && bindDialog.isShowing()) {
            bindDialog.dismiss();
        }
        super.onDestroy();
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private class Adapter extends BaseAdapter {
        private final LayoutInflater inflater = getLayoutInflater();

        @Override
        public int getCount() {
            return GamepadKeyMap.FUNCTIONS.length + 2;
        }

        @Override
        public Object getItem(int position) {
            return null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = inflater.inflate(R.layout.row_keyboard_settings,
                        parent, false);
            }
            TextView name = convertView.findViewById(R.id.row_keyboard_name);
            TextView desc = convertView.findViewById(R.id.row_keyboard_desc);
            TextView keyName = convertView.findViewById(R.id.row_keyboard_key_name);
            if (position == presetRow()) {
                name.setText(R.string.gm_preset_title);
                desc.setVisibility(View.GONE);
                keyName.setVisibility(View.VISIBLE);
                keyName.setText(presetLabel());
            } else if (position == resetRow()) {
                name.setText(R.string.gm_reset_default);
                desc.setVisibility(View.GONE);
                keyName.setVisibility(View.GONE);
            } else {
                int fi = functionRow(position);
                name.setText(GamepadKeyMap.FUNCTION_NAMES[fi]);
                desc.setVisibility(View.GONE);
                keyName.setVisibility(View.VISIBLE);
                keyName.setText(keysLabel(GamepadKeyMap.FUNCTIONS[fi]));
            }
            return convertView;
        }
    }
}
