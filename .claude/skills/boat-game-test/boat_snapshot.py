"""Make a new warmed snapshot for boat_run.py. See SKILL.md, "Refresh the snapshot".

usage: python boat_snapshot.py

Run it from a clean checkout of origin/master. Starts a large VM from boat.dev's stock image, sets
it up with sandbox.sh (packages, JDK 25, a boot-only 26.3 run), compiles every node, fetches what
each node's game test needs, boots every node and records its prefetch list with warm.sh, then saves the VM
as diorama-gametest-<commit> and deletes it. Lists the older diorama-gametest-* snapshots without
removing them.
"""
import json
import subprocess
import sys
import time
from pathlib import Path

sys.dont_write_bytecode = True
import boat_run  # noqa: E402
from boat_run import HERE, SANDBOX_SH, SNAPSHOT_PREFIX, boat, boat_retry, create_vm, events, reconcile, run_script, tail  # noqa: E402

WARM_SH = '/home/user/src/.claude/skills/boat-game-test/warm.sh'


def git(*args, cwd):
    return subprocess.run(['git', *args], capture_output=True, text=True, cwd=cwd).stdout.strip()


def main():
    sys.stdout.reconfigure(line_buffering=True)
    repo = Path(git('rev-parse', '--show-toplevel', cwd=HERE))
    commit = git('rev-parse', '--short=7', 'HEAD', cwd=repo)
    # boat push sends untracked files too, so they'd end up in the snapshot.
    if git('status', '--porcelain', cwd=repo):
        sys.exit('commit or set aside your changes and untracked files first: '
                 'the snapshot is named after the commit it was warmed on')
    if commit != git('rev-parse', '--short=7', 'origin/master', cwd=repo):
        print(f'note: HEAD {commit} is not origin/master; the snapshot will be warmed on {commit}')
    name = SNAPSHOT_PREFIX + commit
    start = time.time()

    # The whole build gets 4 hours, the VM's lifetime.
    boat_run.work_deadline = start + 3.75 * 3600
    boat_run.interrupt_on_signals()
    try:
        vm = create_vm('large', None, f'snapshot-build-{commit}', ttl=14400)
        print(f'VM {vm} (large) ready')
        pushed = boat_retry('push', vm, str(repo), '--to', '/home/user/src', timeout=300)
        if pushed.returncode:
            sys.exit(f'boat push failed: {tail(pushed)}')
        normalized = boat_retry('exec', vm, '--timeout', '60', '--', 'sed', '-i', r's/\r$//', SANDBOX_SH, WARM_SH)
        if normalized.returncode:
            sys.exit(f'could not prepare the scripts: {tail(normalized)}')
        deadline = boat_run.work_deadline - 600
        print('setting up and booting 26.3 once (sandbox.sh)')
        code, log = run_script(vm, SANDBOX_SH, '26.3', 'boot', deadline=deadline)
        print(log.strip()[-2000:])
        booted = boat('exec', vm, '--timeout', '30', '--json', '--', 'cat', '/home/user/result/result.json')
        try:
            boot_exit = json.loads(next(events(booted.stdout), {}).get('stdout', '')).get('exit')
        except ValueError:
            boot_exit = None
        if code or boot_exit != 0:
            sys.exit(f'sandbox.sh failed setting up the VM or booting 26.3: {tail(booted)}')
        print('warming every node (warm.sh, about an hour)')
        code, log = run_script(vm, WARM_SH, commit, deadline=deadline)
        print(log.strip()[-4000:])
        if code:
            sys.exit('warm.sh failed; nothing was saved')
        # Stopping captures the whole disk; the saved snapshot is made from that capture.
        stopped = boat_retry('stop', vm, '--json', timeout=600)
        if stopped.returncode:
            sys.exit(f'boat stop failed: {tail(stopped)}')
        for _ in range(60):
            sandbox = next(events(boat('info', vm, '--json', timeout=30).stdout), {}).get('sandbox', {})
            if sandbox.get('state') == 'stopped':
                break
            time.sleep(10)
        else:
            sys.exit('the VM never reached stopped, so its disk capture may be incomplete; nothing was saved')
        saved = boat('snapshot', vm, name, '--json', timeout=1800)
        if not any(e.get('event') == 'saved' for e in events(saved.stdout)):
            sys.exit(f'boat snapshot failed: {tail(saved)}')
        print(f'saved {name} after {(time.time() - start) / 60:.0f} min')
    finally:
        # Deletes the build VM, also after an error or Ctrl-C.
        reconcile(rounds=2)

    listed = next(events(boat('snapshots', '--json').stdout), {})
    older = [s for s in listed.get('named', []) if s['name'].startswith(SNAPSHOT_PREFIX) and s['name'] != name]
    if older:
        print('older snapshots, kept (the plan includes 10 saved snapshots; remove one with boat snapshot rm <name> '
              'only if you made it or Dara says so): '
              + ', '.join(f'{s["name"]} ({s["createdAt"][:10]})' for s in older))


if __name__ == '__main__':
    main()
