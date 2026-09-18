#!/usr/bin/env python3
"""Read-only, same-call A/B capture. Python 3.9+. No profile/routing changes, no log clearing.
Raw system dumps may contain phone numbers, Bluetooth addresses and other personal details.
Review the resulting ZIP before sharing it. Never run this while driving.
"""
from __future__ import annotations
import argparse
import datetime as dt
import difflib
import pathlib
import shutil
import subprocess
import sys
import time
import zipfile


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', default='adb', help='ADB executable or full path')
    parser.add_argument('--serial', help='Device serial when more than one device is connected')
    args = parser.parse_args()
    adb = shutil.which(args.adb) or args.adb
    base = [adb] + (['-s', args.serial] if args.serial else [])
    stamp = dt.datetime.now().strftime('%Y%m%d-%H%M%S')
    folder = pathlib.Path('captures') / stamp
    folder.mkdir(parents=True, exist_ok=False)

    def run(*command: str) -> str:
        try:
            result = subprocess.run(base + list(command), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                    text=True, encoding='utf-8', errors='replace', timeout=35)
            return result.stdout + (f'\nEXIT={result.returncode}\n' if result.returncode else '')
        except subprocess.TimeoutExpired:
            return 'ERROR: command exceeded the 35-second capture deadline\n'

    state = run('get-state')
    if state.strip() != 'device':
        print('Connect/unlock one authorized device (or specify --serial):\n' + state, file=sys.stderr)
        return 1
    print('Park first. Raw dumps may contain sensitive data. Nothing is uploaded automatically.')
    (folder / 'README-PRIVACY.txt').write_text(__doc__ or '', encoding='utf-8')
    props = ['ro.product.model', 'ro.build.version.release', 'ro.build.version.sdk',
             'ro.build.version.oneui', 'ro.build.fingerprint', 'ro.build.version.security_patch']
    (folder / 'device.txt').write_text('\n'.join(p + '=' + run('shell', 'getprop', p).strip() for p in props), encoding='utf-8')
    listed = run('shell', 'dumpsys', '-l')
    (folder / 'services.txt').write_text(listed, encoding='utf-8')
    services = [s for s in ('telecom', 'audio', 'bluetooth_manager') if s in {line.strip() for line in listed.splitlines()}]
    if not services:
        print('Relevant services not found; retaining inventory only.', file=sys.stderr)
        return 1
    package = 'com.itaymatza.carcallrouter'
    (folder / 'appops.txt').write_text(run('shell', 'cmd', 'appops', 'get', package, 'MANAGE_ONGOING_CALLS'), encoding='utf-8')
    (folder / 'android-auto.txt').write_text(run('shell', 'dumpsys', 'package', 'com.google.android.projection.gearhead'), encoding='utf-8')
    with (folder / 'app-logcat.txt').open('wb') as log:
        proc = subprocess.Popen(base + ['logcat', '-v', 'threadtime', '-T', '1', 'CarCallRouter:I', '*:S'], stdout=log, stderr=subprocess.STDOUT)
        try:
            for label, instruction in [('A', 'Start a normal cellular call. Keep Android Auto connected and leave the BAD route selected.'),
                                       ('B', 'During the SAME call, manually select the BMW device. Keep the call active.')]:
                input(instruction + '\nPress Enter to capture state ' + label + ': ')
                path = folder / label
                path.mkdir()
                (path / 'captured-at.txt').write_text(dt.datetime.now(dt.timezone.utc).isoformat(), encoding='utf-8')
                for service in services:
                    (path / (service + '.txt')).write_text(run('shell', 'dumpsys', service), encoding='utf-8')
                (path / 'app-service.txt').write_text(run('shell', 'dumpsys', 'activity', 'service',
                    package + '/.telecom.RouterInCallService'), encoding='utf-8')
                print(label + ' captured.')
        finally:
            proc.terminate()
            try: proc.wait(timeout=5)
            except subprocess.TimeoutExpired: proc.kill(); proc.wait()
    for name in [s + '.txt' for s in services] + ['app-service.txt']:
        a, b = folder / 'A' / name, folder / 'B' / name
        diff = difflib.unified_diff(a.read_text(encoding='utf-8').splitlines(True),
                                   b.read_text(encoding='utf-8').splitlines(True), fromfile='A/' + name, tofile='B/' + name)
        (folder / ('diff-' + name)).write_text(''.join(diff), encoding='utf-8')
    archive = folder.with_suffix('.zip')
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
        for path in sorted(folder.rglob('*')):
            if path.is_file(): output.write(path, path.relative_to(folder))
    print(f'Created {archive}. Review personal information before sharing.')
    return 0

if __name__ == '__main__':
    try: raise SystemExit(main())
    except (OSError, KeyboardInterrupt, EOFError) as exc:
        print(f'Capture stopped: {exc}', file=sys.stderr)
        raise SystemExit(1)
