from pathlib import Path
p=Path(r'E:/Downloads/888888/vlc-subfont-xposed')
manifest=(p/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
main=(p/'app/src/main/java/com/vlcsubfont/module/MainActivity.java').read_text(encoding='utf-8')
layout=(p/'app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
checks=[
 'android:name=".LauncherAlias"' in manifest,
 'android:targetActivity=".MainActivity"' in manifest,
 manifest.count('android.intent.category.LAUNCHER') == 1,
 'switch_hide_icon' in layout,
 'setComponentEnabledSetting' in main,
 'hide_icon' in main,
]
assert all(checks)
print('PASS: 6/6 launcher-icon checks')
