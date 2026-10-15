#!/usr/bin/env python3
"""Verify unsupported pointer-lock controls cannot break the owned Android viewer."""
import argparse
import asyncio
import json
from pathlib import Path
import websockets

from test_android_browser import Android, CDP


async def verify(serial, output):
    android = Android(serial, output)
    assert serial.startswith('emulator-')
    assert (await android.adb('emu', 'avd', 'name')).decode().splitlines()[0] == 'agentshell-ui-poc'
    output.mkdir(parents=True, exist_ok=True)
    await android.adb('reverse', 'tcp:18998', 'tcp:18998')
    await android.adb('shell', 'wm', 'size', '1536x1605')
    target = await android.relaunch()
    results = {}

    async def check():
        target = await android.ready()
        async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
            client = CDP(socket)
            for _ in range(20):
                guarded=await client.evaluate('''(()=>{
                    const game=document.getElementById('noVNC_game_mode_button');
                    const setting=document.getElementById('noVNC_setting_pointer_lock');
                    return game.alt==='Touch mouse' && setting.disabled && !setting.checked &&
                        setting.closest('li').getBoundingClientRect().height===0;
                })()''')
                if guarded:break
                await asyncio.sleep(.1)
            assert guarded, 'Unsupported controls are still visible or enabled'
            results['desktop_pointer_lock_replaced_by_touch_mouse'] = True
            await client.evaluate('''(()=>{
                window.pointerLockRequests=0;
                const canvas=AgentShellUI.rfb._canvas, original=canvas.requestPointerLock.bind(canvas);
                canvas.requestPointerLock=(...args)=>{pointerLockRequests++;return original(...args)};
                document.getElementById('noVNC_game_mode_button').click();
                document.getElementById('noVNC_game_mode_button').parentNode.click();
                AgentShellUI.toggleRelativePointer(); AgentShellUI.togglePointerLock();
            })()''')
            await asyncio.sleep(.3)
            assert await client.evaluate('pointerLockRequests===0 && !AgentShellUI.rfb.pointerRelative && !AgentShellUI.rfb.pointerLock')
            results['alternate_entry_points_never_request_pointer_lock'] = True
            # Exercise the same document shortcut path used by a hardware keyboard.
            for key, code in [('2', 50), ('3', 51)]:
                await client.call('Input.dispatchKeyEvent', type='keyDown', key=key, code='Digit'+key,
                                  modifiers=10, windowsVirtualKeyCode=code, nativeVirtualKeyCode=code)
                await client.call('Input.dispatchKeyEvent', type='keyUp', key=key, code='Digit'+key,
                                  modifiers=10, windowsVirtualKeyCode=code, nativeVirtualKeyCode=code)
            await asyncio.sleep(.3)
            assert await client.evaluate('''AgentShellUI.connected && pointerLockRequests===0 &&
                document.getElementById('noVNC_fallback_errormsg').innerText==='' ''')
            results['shortcuts_preserve_live_viewer_without_fatal_error'] = True

    await check()
    async with websockets.connect(target['webSocketDebuggerUrl']) as socket:
        client = CDP(socket)
        await client.evaluate("AgentShellUI.forceSetting('pointer_lock',true,false)")
    await android.relaunch()
    await check()
    results['stale_pointer_lock_preference_cleared_on_reload'] = True
    await android.options('pointer-lock-guard')
    results['settings_remain_scrollable'] = True
    await android.screenshot('fixed-mobile-controls')
    (output/'results.json').write_text(json.dumps(results, indent=2)+'\n')
    print(json.dumps(results, indent=2))


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--serial', default='emulator-5560')
    parser.add_argument('--output', type=Path, default=Path('/tmp/agentshell-browser-implementation/pointer-lock/after'))
    args = parser.parse_args()
    try:
        await verify(args.serial, args.output)
    finally:
        await Android(args.serial,args.output).adb('shell','wm','size','reset')


if __name__ == '__main__':
    asyncio.run(main())
