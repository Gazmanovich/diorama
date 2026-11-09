"""A real vanilla Minecraft client, no mods, for a player without Diorama in the game tests.

usage: python3 vanilla_client.py <minecraft version> <java> <name> <host:port> <server console> <seconds>

Downloads the version's client and libraries from Mojang (kept in ~/vanilla/<version>), starts it
with Quick Play into the server as an offline player called <name>, and waits for the server's
console to say the player joined. Then it stays <seconds> and leaves. Exits 0 if the player joined
and wasn't disconnected meanwhile; prints the client's last log lines otherwise. Runs under the
game test's X server (DISPLAY) with its software rendering.
"""
import concurrent.futures
import hashlib
import json
import os
import subprocess
import sys
import time
import urllib.request
from pathlib import Path

MANIFEST = 'https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'


def fetch(url, path, sha1=None):
    path = Path(path)
    if path.exists() and (sha1 is None or hashlib.sha1(path.read_bytes()).hexdigest() == sha1):
        return path
    path.parent.mkdir(parents=True, exist_ok=True)
    part = path.with_name(path.name + '.part')
    with urllib.request.urlopen(url, timeout=120) as response, open(part, 'wb') as out:
        out.write(response.read())
    part.replace(path)
    return path


def allowed(rules):
    if not rules:
        return True
    allow = False
    for rule in rules:
        os_rule = rule.get('os')
        if 'features' in rule:
            continue
        if os_rule is None or os_rule.get('name') == 'linux':
            allow = rule['action'] == 'allow'
    return allow


def main():
    version, java, name, address, console, seconds = sys.argv[1:7]
    root = Path.home() / 'vanilla' / version
    # The list of versions is kept, and fetched again when it doesn't have this one yet.
    cached = Path.home() / 'vanilla' / 'manifest.json'
    manifest = json.loads(fetch(MANIFEST, cached).read_text())
    if not any(v['id'] == version for v in manifest['versions']):
        cached.unlink()
        manifest = json.loads(fetch(MANIFEST, cached).read_text())
    entry = next(v for v in manifest['versions'] if v['id'] == version)
    info = json.loads(fetch(entry['url'], root / f'{version}.json', entry.get('sha1')).read_text())
    client = info['downloads']['client']
    classpath = []
    for library in info['libraries']:
        artifact = library.get('downloads', {}).get('artifact')
        if artifact and allowed(library.get('rules')):
            classpath.append(str(fetch(artifact['url'], root / 'libraries' / artifact['path'], artifact.get('sha1'))))
    classpath.append(str(fetch(client['url'], root / 'client.jar', client.get('sha1'))))
    # The game's assets but the sounds, which the client runs without. Loom's cache on the warm
    # snapshot has most of them already.
    index = info['assetIndex']
    assets = root / 'assets'
    objects = json.loads(fetch(index['url'], assets / 'indexes' / f"{index['id']}.json", index.get('sha1')).read_text())['objects']
    loom = Path.home() / '.gradle' / 'caches' / 'fabric-loom' / 'assets' / 'objects'
    if loom.is_dir() and not (assets / 'objects').exists():
        (assets / 'objects').symlink_to(loom)
    # Once per hash: names can share one, and two downloads of it would share a file.
    wanted = sorted({o['hash'] for name, o in objects.items() if not name.startswith(('minecraft/sounds/', 'minecraft/music/', 'sounds/'))})
    with concurrent.futures.ThreadPoolExecutor(16) as pool:
        list(pool.map(lambda h: fetch(f'https://resources.download.minecraft.net/{h[:2]}/{h}', assets / 'objects' / h[:2] / h), wanted))

    game = root / 'game'
    game.mkdir(parents=True, exist_ok=True)
    # The accessibility screen on a first start would keep Quick Play from joining.
    (game / 'options.txt').write_text('onboardAccessibility:false\nsoundCategory_master:0.0\npauseOnLostFocus:false\n')
    log = root / 'client.log'
    command = [java, '-Xmx768M', '-cp', os.pathsep.join(classpath), info['mainClass'],
               '--username', name, '--version', version, '--gameDir', str(game),
               '--assetsDir', str(assets), '--assetIndex', index['id'],
               '--uuid', hashlib.md5(name.encode()).hexdigest(), '--accessToken', '0', '--userType', 'legacy',
               '--quickPlayMultiplayer', address]
    # Only what the console says from here on counts: the same name may have joined before.
    before = len(Path(console).read_text(errors='replace'))
    started = time.time()
    with open(log, 'w') as out:
        process = subprocess.Popen(command, stdout=out, stderr=subprocess.STDOUT, cwd=game)
    try:
        joined = None
        while time.time() - started < 600:
            text = Path(console).read_text(errors='replace')
            at = text.find(f'{name} joined the game', before)
            if at >= 0:
                joined = at
                break
            if process.poll() is not None:
                break
            time.sleep(1)
        if joined is None:
            print(f'{name} never joined the server')
            print(log.read_text(errors='replace')[-4000:])
            return 1
        print(f'{name} joined after {time.time() - started:.0f} s')
        time.sleep(float(seconds))
        after = Path(console).read_text(errors='replace')[joined:]
        if f'{name} lost connection' in after or f'{name} left the game' in after or process.poll() is not None:
            print(f'{name} was disconnected:')
            print(after[-3000:])
            print(log.read_text(errors='replace')[-3000:])
            return 1
        print(f'{name} stayed {seconds} s')
        return 0
    finally:
        process.terminate()
        try:
            process.wait(30)
        except subprocess.TimeoutExpired:
            process.kill()


if __name__ == '__main__':
    sys.exit(main())
