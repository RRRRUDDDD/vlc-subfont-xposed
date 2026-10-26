from pathlib import Path
p=Path(r'E:/Downloads/888888/vlc-subfont-xposed')
m=(p/'app/src/main/AndroidManifest.xml').read_text(encoding='utf-8')
l=(p/'app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
assert 'de.robv.android.xposed.category.MODULE_SETTINGS' in m
assert 'android:name=".LauncherAlias"' in m
assert m.count('android.intent.category.LAUNCHER') == 1
assert '播放器界面' in l and '字幕字体' in l and '使用说明' in l
print('PASS: LSPosed settings entry and layout grouping checks')
