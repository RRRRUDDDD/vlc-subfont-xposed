package com.vlcsubfont.module;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textview.MaterialTextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.json.JSONObject;

public class MainActivity extends Activity {

    private static final int REQ_PICK_FONT = 1;
    private static final int REQ_STORAGE_PERM = 2;

    private static final String[] FONT_MIMES = {
            "font/ttf", "font/otf", "font/collection", "font/sfnt",
            "application/x-font-ttf", "application/x-font-opentype",
            "application/x-font-troff", "application/octet-stream",
    };

    private SharedPreferences mPrefs;
    private MaterialSwitch mSwitch;
    private MaterialTextView mTxtFont;
    private MaterialTextView mTxtStatus;
    private Uri mPendingPick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences("config", MODE_PRIVATE);
        setContentView(R.layout.activity_main);

        mSwitch = findViewById(R.id.switch_enabled);
        mTxtFont = findViewById(R.id.txt_font);
        mTxtStatus = findViewById(R.id.txt_status);

        mSwitch.setOnCheckedChangeListener((btn, checked) -> {
            mPrefs.edit().putBoolean("enabled", checked).apply();
            writeMediaConfig();
            refreshUi();
        });

        MaterialButton btnPick = findViewById(R.id.btn_pick);
        btnPick.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_MIME_TYPES, FONT_MIMES);
            startActivityForResult(i, REQ_PICK_FONT);
        });

        MaterialButton btnClear = findViewById(R.id.btn_clear);
        btnClear.setOnClickListener(v -> {
            new File(getFilesDir(), "font.ttf").delete();
            mPrefs.edit().putBoolean("enabled", false).apply();
            deleteMediaMirror();
            refreshUi();
        });

        MaterialButton btnVlcPerm = findViewById(R.id.btn_vlc_perm);
        btnVlcPerm.setOnClickListener(v -> openVlcStoragePermission());

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQ_STORAGE_PERM);
        }

        refreshUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshUi();
    }

    private void refreshUi() {
        boolean enabled = mPrefs.getBoolean("enabled", false);
        String name = mPrefs.getString("font_name", null);
        boolean hasFont = new File(getFilesDir(), "font.ttf").canRead();

        mSwitch.setEnabled(hasFont);
        mSwitch.setChecked(enabled && hasFont);
        mTxtFont.setText(hasFont && !TextUtils.isEmpty(name)
                ? name : "未选择（选择 ttf / otf / ttc 字体文件）");
        mTxtStatus.setText(enabled && hasFont
                ? "已启用。重启 VLC（或强制停止后再打开）后生效。"
                : "未启用。");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK_FONT || resultCode != RESULT_OK
                || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            String name = queryDisplayName(uri);
            File tmp = new File(getFilesDir(), "font.ttf.part");
            try (InputStream in = getContentResolver().openInputStream(uri);
                 OutputStream out = new FileOutputStream(tmp)) {
                if (in == null) {
                    throw new IOException("无法读取所选文件");
                }
                byte[] buf = new byte[65536];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            File dst = new File(getFilesDir(), "font.ttf");
            if (!tmp.renameTo(dst)) {
                throw new IOException("保存失败");
            }
            mPrefs.edit()
                    .putString("font_name", name)
                    .putLong("version", System.currentTimeMillis())
                    .putBoolean("enabled", true)
                    .apply();
            writeMediaConfig();
            Toast.makeText(this, "已选择: " + name, Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "导入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        refreshUi();
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (!TextUtils.isEmpty(name)) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "font.ttf";
    }

    /** 写入模块自身 Android/media 目录作兜底镜像（Provider 不可用时注入端从这里取） */
    private void writeMediaConfig() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(),
                    "Android/media/" + getPackageName());
            if (!dir.isDirectory() && !dir.mkdirs()) {
                return;
            }
            File src = new File(getFilesDir(), "font.ttf");
            JSONObject json = new JSONObject();
            json.put("enabled", mPrefs.getBoolean("enabled", false) && src.canRead());
            json.put("fontName", mPrefs.getString("font_name", null));
            json.put("version", mPrefs.getLong("version", 0L));
            try (OutputStream out = new FileOutputStream(new File(dir, "config.json"))) {
                out.write(json.toString().getBytes(StandardCharsets.UTF_8));
            }
            File mirror = new File(dir, "font.ttf");
            File tmp = new File(dir, "font.ttf.part");
            if (src.canRead()) {
                try (InputStream in = new java.io.FileInputStream(src);
                     OutputStream out = new FileOutputStream(tmp)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                }
                tmp.renameTo(mirror);
            } else {
                mirror.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private void deleteMediaMirror() {
        try {
            File dir = new File(Environment.getExternalStorageDirectory(),
                    "Android/media/" + getPackageName());
            new File(dir, "font.ttf").delete();
            new File(dir, "config.json").delete();
        } catch (Throwable ignored) {
        }
    }

    private void openVlcStoragePermission() {
        try {
            Intent i = new Intent(
                    "android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                    Uri.parse("package:" + FontStore.VLC_PACKAGE));
            startActivity(i);
        } catch (Exception e) {
            try {
                startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
            } catch (Exception ignored) {
                Toast.makeText(this, "无法打开设置，请手动进入 系统设置 → 应用 → VLC", Toast.LENGTH_LONG).show();
            }
        }
    }
}
