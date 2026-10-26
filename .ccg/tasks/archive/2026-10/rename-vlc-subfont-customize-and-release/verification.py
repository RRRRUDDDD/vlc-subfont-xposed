from pathlib import Path
p=Path(r'E:/Downloads/888888/vlc-subfont-xposed')
assert 'VLC字幕字体自定义' in (p/'README.md').read_text(encoding='utf-8')
assert '@string/footnote' not in (p/'app/src/main/res/layout/activity_main.xml').read_text(encoding='utf-8')
print('PASS: release task checks')
