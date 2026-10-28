"""Make a new warmed snapshot for boat_run.py. See SKILL.md, "Refresh the snapshot".

usage: python boat_snapshot.py

Run it from a checkout of origin/master. Starts a large VM from boat.dev's stock image, sets it up
with sandbox.sh (packages, JDK 25, a boot-only 26.3 run), compiles every node and fetches what each
node's game test needs with warm.sh, then saves the VM as diorama-gametest-<commit> and deletes it.
Keeps the two newest diorama-gametest-* snapshots and removes older ones.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

from boat_run import HERE, SANDBOX_SH, SNAPSHOT_PREFIX, boat, events, live_vms, remove_vm, run_script, tail

WARM_SH = '/home/user/src/.claude/skills/boat-game-test/warm.sh'


def git(*args, cwd):
    return subprocess.run(['git', *args], capture_output=True, text=True, cwd=cwd).stdout.strip()


def main():
    sys.stdout.reconfigure(line_buffering=True)
    repo = Path(git('rev-parse', '--show-toplevel', cwd=HERE))
    commit = git('rev-parse', '--short=7', 'HEAD', cwd=repo)
    if git('status', '--porcelain', '--untracked-files=no', cwd=repo):
        sys.exit('commit or set aside your changes first: the snapshot is named after the commit it was warmed on')
    if commit != git('rev-parse', '--short=7', 'origin/master', cwd=repo):
        print(f'note: HEAD {commit} is not origin/master; the snapshot will be warmed on {commit}')
    name = SNAPSHOT_PREFIX + commit
    start = time.time()

    created = boat('new', '--type', 'large', '--no-env', '--ttl', '14400', '--name', f'snapshot-build-{commit}', '--json')
    vm = next((e['id'] for e in events(created.stdout) if e.get('event') == 'ready'), None)
    if not vm:
        sys.exit(f'boat new failed: {tail(created)}')
    live_vms.add(vm)
    print(f'VM {vm} (large) ready')
    try:
        pushed = boat('push', vm, str(repo), '--to', '/home/user/src', timeout=900)
        if pushed.returncode:
            sys.exit(f'boat push failed: {tail(pushed)}')
        boat('exec', vm, '--timeout', '60', '--', 'sed', '-i', r's/\r$//', SANDBOX_SH, WARM_SH)
        deadline = start + 3.5 * 3600
        print('setting up and booting 26.3 once (sandbox.sh)')
        code, log = run_script(vm, SANDBOX_SH, '26.3', 'boot', deadline=deadline)
        print(log.strip()[-2000:])
        if code:
            sys.exit('sandbox.sh failed setting up the VM')
        booted = boat('exec', vm, '--timeout', '30', '--', 'cat', '/home/user/result/result.json')
        try:
            boot_exit = json.loads(next(events(booted.stdout), {}).get('stdout', '')).get('exit')
        except ValueError:
            boot_exit = None
        if boot_exit != 0:
            sys.exit(f'the 26.3 boot run failed: {tail(booted)}')
        print('warming every node (warm.sh)')
        boat('exec', vm, '--timeout', '30', '--', 'rm', '-f', '/home/user/run-started', '/home/user/run-exit')
        code, log = run_script(vm, WARM_SH, commit, deadline=deadline)
        print(log.strip()[-3000:])
        if code:
            sys.exit('warm.sh failed; nothing was saved')
        # Stopping captures the whole disk; the saved snapshot is made from that capture.
        stopped = boat('stop', vm, '--json', timeout=1800)
        if stopped.returncode:
            sys.exit(f'boat stop failed: {tail(stopped)}')
        for _ in range(60):
            if '"state":"stopped"' in boat('info', vm, '--json').stdout.replace(' ', ''):
                break
            time.sleep(10)
        saved = boat('snapshot', vm, name, '--json', timeout=1800)
        if saved.returncode:
            sys.exit(f'boat snapshot failed: {tail(saved)}')
        print(f'saved {name} after {(time.time() - start) / 60:.0f} min')
    finally:
        remove_vm(vm)

    listed = next(events(boat('snapshots', '--json').stdout), {})
    ours = sorted((s for s in listed.get('named', []) if s['name'].startswith(SNAPSHOT_PREFIX)),
                  key=lambda s: s['createdAt'], reverse=True)
    for old in ours[2:]:
        boat('snapshot', 'rm', old['name'])
        print(f'removed the older snapshot {old["name"]}')


if __name__ == '__main__':
    main()
