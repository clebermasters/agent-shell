#!/usr/bin/env python3
"""Theme smoke test for the regular application, on an isolated emulator only."""
import argparse
import io
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image

parser = argparse.ArgumentParser()
parser.add_argument('--package', default='com.agentshell.debug')
parser.add_argument('--serial', default='emulator-5560')
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
assert args.serial.startswith('emulator-'), 'This test modifies emulator theme preferences'
args.output.mkdir(parents=True, exist_ok=True)

def adb(*parts):
    return subprocess.check_output(['adb', '-s', args.serial, *parts], timeout=20)

def hierarchy():
    deadline = time.monotonic() + 15
    while True:
        adb('shell', 'rm', '-f', '/sdcard/app-theme.xml')
        try:
            adb('shell', 'uiautomator', 'dump', '/sdcard/app-theme.xml')
            return ET.fromstring(adb('shell', 'cat', '/sdcard/app-theme.xml'))
        except (subprocess.CalledProcessError, ET.ParseError):
            assert time.monotonic() < deadline, 'Application did not expose a UI hierarchy'
            time.sleep(.2)

def find(label):
    return next((n for n in hierarchy().iter('node') if label in [n.get('text'), n.get('content-desc')]), None)

def expect(label):
    deadline = time.monotonic() + 15
    while (node := find(label)) is None:
        assert time.monotonic() < deadline, 'Missing native control: ' + label
    return node

def tap(label):
    node = expect(label)
    x1, y1, x2, y2 = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((x1+x2)//2), str((y1+y2)//2))

def capture(name, color):
    deadline = time.monotonic() + 15
    while True:
        data = adb('exec-out', 'screencap', '-p')
        image = Image.open(io.BytesIO(data)).convert('RGB')
        if image.getpixel((20, 900)) == color:
            (args.output / name).write_bytes(data)
            return
        assert time.monotonic() < deadline, (name, image.getpixel((20, 900)))
        time.sleep(.2)

adb('shell', 'pm', 'grant', args.package, 'android.permission.POST_NOTIFICATIONS')
adb('shell', 'cmd', 'uimode', 'night', 'no')
adb('shell', 'am', 'start', '-S', '-n', args.package+'/com.agentshell.MainActivity')
expect('Settings')
if find('Switch to light mode') is not None:
    tap('Switch to light mode')
expect('Switch to dark mode')
capture('production-home-light.png', (252, 252, 253))
tap('Switch to dark mode')
expect('Switch to light mode')
capture('production-home-dark.png', (23, 28, 39))
adb('shell', 'am', 'start', '-S', '-n', args.package+'/com.agentshell.MainActivity')
expect('Switch to light mode')
capture('production-dark-restarted.png', (23, 28, 39))
tap('Settings')
tap('Light')
adb('shell', 'input', 'keyevent', '4')
expect('Switch to dark mode')
tap('Settings')
tap('System')
adb('shell', 'cmd', 'uimode', 'night', 'yes')
adb('shell', 'input', 'keyevent', '4')
expect('Switch to light mode')
adb('shell', 'cmd', 'uimode', 'night', 'no')
expect('Switch to dark mode')
report = dict(package=args.package, activity='regular MainActivity', quick_toggle=True,
              exact_preview_colors=True, process_restart_persistence=True,
              settings_override=True, follows_system_changes=True)
(args.output/'theme-results.json').write_text(json.dumps(report, indent=2)+'\n')
print(json.dumps(report))
