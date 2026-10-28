"""Run Diorama's client game tests on boat.dev VMs: software rendering, no GPU. See SKILL.md.

usage: python boat_run.py <node[,node...]> --tests <sections|all> [--type default|large] [--env KEY=VALUE]... [--keep]

Each node gets its own VM, started at the same time from the newest warmed snapshot. The script
pushes this working tree (uncommitted changes included), runs :<node>:runClientGameTest, copies the
results to build/boat-runs/<time>-<node>/ and deletes the VM. Exits non-zero if any node failed.
"""
import argparse
import atexit
import json
import os
import shutil
import signal
import subprocess
import sys
import tarfile
import threading
import time
from pathlib import Path

SNAPSHOT_PREFIX = 'diorama-gametest-'
HERE = Path(__file__).resolve().parent
SANDBOX_SH = '/home/user/src/.claude/skills/boat-game-test/sandbox.sh'
# The VM's own limit; sandbox.sh stops the tests at 90 min, inside it.
TTL = 7200


def find_boat():
    found = shutil.which('boat')
    if found:
        return found
    # The installer adds `boat` as a shell function, which subprocesses can't see.
    exe = Path.home() / '.ascii' / 'bin' / ('boat.exe' if os.name == 'nt' else 'boat')
    if exe.exists():
        return str(exe)
    sys.exit('boat CLI not found. Dara installs it: https://docs.boat.dev')


BOAT = find_boat()
print_lock = threading.Lock()
live_vms = set()


def say(node, text):
    with print_lock:
        print(f'[{node}] {text}' if node else text, flush=True)


def boat(*args, cwd=None, timeout=300):
    try:
        return subprocess.run([BOAT, '--no-update', *args], capture_output=True, text=True, encoding='utf-8',
                              errors='replace', cwd=cwd, timeout=timeout)
    except subprocess.TimeoutExpired:
        return subprocess.CompletedProcess(args, 124, '', f'boat {args[0]} timed out after {timeout} s')


def boat_retry(*args, cwd=None, timeout=300):
    """boat for steps that are safe to repeat, tried three times: the API fails now and then."""
    for _ in range(3):
        result = boat(*args, cwd=cwd, timeout=timeout)
        if result.returncode == 0:
            break
        time.sleep(15)
    return result


def events(output):
    for line in output.splitlines():
        try:
            found = json.loads(line)
        except ValueError:
            continue
        if isinstance(found, dict):
            yield found


def tail(result):
    return (result.stdout + result.stderr).strip()[-2000:]


def remove_vm(vm, keep=False):
    """Deletes the VM (or stops it, to keep), retrying; says loudly when that fails."""
    for _ in range(3):
        if boat('stop' if keep else 'delete', vm, *([] if keep else ['--yes'])).returncode == 0:
            live_vms.discard(vm)
            return
        time.sleep(10)
    say(None, f'!! could not {"stop" if keep else "delete"} VM {vm}. Run: boat delete {vm} --yes')


def remove_all_on_exit(*_):
    for vm in list(live_vms):
        remove_vm(vm)
    if _:
        sys.exit(130)


def newest_snapshot():
    """The most recent saved snapshot named diorama-gametest-<commit>, or None."""
    listed = next(events(boat('snapshots', '--json').stdout), {})
    named = [s for s in listed.get('named', []) if s['name'].startswith(SNAPSHOT_PREFIX) and s['status'] == 'ready']
    return max(named, key=lambda s: s['createdAt'])['name'] if named else None


def find_vm(name):
    """The id of our VM by its name, if boat.dev made one; None if not, or if boat list fails."""
    listed = next(events(boat('list', '--json').stdout), {})
    return next((v['id'] for v in listed.get('sandboxes', []) if v.get('name') == name), None)


def wait_up(vm):
    """Waits for a VM whose `boat new` answer got lost to come up; deletes it if it doesn't."""
    for _ in range(30):
        state = next(events(boat('info', vm, '--json').stdout), {}).get('sandbox', {}).get('state')
        if state in ('ready', 'idle', 'running'):
            return vm
        time.sleep(10)
    remove_vm(vm)
    raise RuntimeError(f'VM {vm} never came up')


def create_vm(size, snapshot, name):
    """A new VM from the snapshot. A refused start (a limit) ends the run: it counts against the
    start limits too, so there is no waiting loop. When the API fails instead, the VM may exist
    anyway, so look it up by name before trying once more."""
    args = ['new', '--type', size, '--no-env', '--ttl', str(TTL), '--name', name, '--json',
            *(['--from', snapshot] if snapshot else [])]
    for attempt in range(2):
        result = boat(*args)
        found = list(events(result.stdout + result.stderr))
        ready = next((e['id'] for e in found if e.get('event') == 'ready'), None)
        if ready:
            live_vms.add(ready)
            return ready
        error = next((e for e in found if e.get('event') == 'error' or 'error' in e), {})
        refused = error.get('status') == 429 or 'limit' in str(error.get('code', ''))
        if not refused:
            time.sleep(30)
        vm = find_vm(name)
        if vm:
            live_vms.add(vm)
            if refused:
                remove_vm(vm)
            else:
                return wait_up(vm)
        if refused:
            raise RuntimeError(f'boat.dev refused the start ({error.get("code")}: {error.get("error")}). '
                               'Check `boat limits`; a refused start counts too, so run again later, not in a loop.')
    raise RuntimeError(f'boat new failed twice: {error or tail(result)}')


def run_script(vm, script, *args, deadline):
    """Starts a VM script detached and waits for the exit code it writes to ~/run-exit; returns it
    with the script's log. Each step is a short API call, so the run survives the API failing for a
    while, which happens: long live output streams drop, and calls return 500 now and then. None of
    this counts against start limits."""
    for attempt in range(3):
        started = boat('exec', vm, '--detach', '--json', '--', 'bash', script, *args)
        if any('pid' in e for e in events(started.stdout)):
            break
        # A start reported as failed may have started anyway (the script writes ~/run-started), and
        # the script's lock stops a second copy.
        time.sleep(20)
        check = boat('exec', vm, '--timeout', '30', '--json', '--', 'ls', '/home/user/run-started')
        if next(events(check.stdout), {}).get('exitCode') == 0:
            break
        if attempt == 2:
            raise RuntimeError(f'could not start {script}: {tail(started)}')
    misses = 0
    while time.time() < deadline:
        time.sleep(30)
        read = next(events(boat('exec', vm, '--timeout', '30', '--json', '--', 'cat', '/home/user/run-exit', timeout=60).stdout), {})
        if read.get('exitCode') == 0 and read.get('stdout', '').strip().isdigit():
            log = next(events(boat('exec', vm, '--timeout', '60', '--json', '--', 'bash', '-c',
                                   'tail -c 20000 ~/sandbox.log ~/warm.out 2>/dev/null', timeout=120).stdout), {})
            return int(read['stdout'].strip()), log.get('stdout', '')
        # exitCode 1 is cat finding no file yet: still running. Anything else is the API failing.
        misses = 0 if read.get('exitCode') == 1 else misses + 1
        if misses == 20:
            raise RuntimeError(f'lost contact with VM {vm} for 10 min')
    raise RuntimeError(f'{script} was still running at the deadline')


def run_node(node, args, repo, label, snapshot, start):
    out = repo / 'build' / 'boat-runs' / f'{time.strftime("%Y%m%d-%H%M%S")}-{os.getpid()}-{node}'
    out.mkdir(parents=True)
    vm = create_vm(args.type, snapshot, f'diorama-test-{label}-{node}-{os.getpid()}')
    say(node, f'VM {vm} ({args.type}) ready after {time.time() - start:.0f} s')
    try:
        # The snapshot's copy and results: push only adds files, and an old result.tgz could pass for this run's.
        cleared = boat_retry('exec', vm, '--timeout', '120', '--', 'rm', '-rf', '/home/user/src', '/home/user/result',
                             '/home/user/result.tgz', '/home/user/run-started', '/home/user/run-exit')
        if cleared.returncode:
            raise RuntimeError(f'could not clear the VM: {tail(cleared)}')
        pushed = boat_retry('push', vm, str(repo), '--to', '/home/user/src', timeout=900)
        if pushed.returncode:
            raise RuntimeError(f'boat push failed: {tail(pushed)}')
        # A Windows checkout can have CRLF line endings, which bash rejects.
        boat_retry('exec', vm, '--timeout', '60', '--', 'sed', '-i', r's/\r$//', SANDBOX_SH)
        say(node, f'pushed after {time.time() - start:.0f} s; running {args.tests}')
        code, log = run_script(vm, SANDBOX_SH, node, args.tests, *args.env, deadline=start + 115 * 60)
        (out / 'vm.log').write_text(log, encoding='utf-8')
        if code:
            raise RuntimeError(f'the VM script failed setting up or packing results; see {out / "vm.log"}')
        fetched = boat_retry('scp', f'{vm}:/home/user/result.tgz', 'result.tgz', cwd=out, timeout=900)
        if fetched.returncode or not (out / 'result.tgz').exists():
            raise RuntimeError(f'no results came back; see {out / "vm.log"}')
        with tarfile.open(out / 'result.tgz') as tar:
            tar.extractall(out, filter='data')
        (out / 'result.tgz').unlink()
        usage = next(events(boat('usage', vm, '--json').stdout), {})
    finally:
        remove_vm(vm, keep=args.keep)

    result = json.loads((out / 'result.json').read_text())
    shots = sorted((out / 'screenshots').glob('*.png')) if (out / 'screenshots').exists() else []
    say(node, f'exit={result["exit"]} after {result["minutes"]} min of tests, '
              f'{(time.time() - start) / 60:.0f} min in all, ${usage.get("dollars", 0):.3f}')
    say(node, f'{len(shots)} screenshots in {out / "screenshots"}')
    if result['exit']:
        lines = (out / 'gradle.log').read_text(encoding='utf-8', errors='replace').splitlines()
        cause = [l for l in lines if any(k in l for k in ('AssertionError', 'Description:', 'Caused by', 'FAILED'))]
        say(node, '\n  '.join(['failure lines:', *cause[:8]]))
        say(node, f'full output: {out / "gradle.log"}; game log: {out / "latest.log"}')
    if args.keep:
        say(node, f'VM {vm} is stopped, not deleted: boat resume {vm}, and boat delete {vm} --yes when done')
    return result['exit']


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('nodes', help='Stonecutter nodes, as in versions/, comma-separated: 26.3,1.21.1,26.3-neoforge')
    parser.add_argument('--tests', required=True, help='DIORAMA_TESTS sections, comma-separated, or "all"')
    parser.add_argument('--type', default='default', choices=['small', 'default', 'large'],
                        help='VM size. default (4 vCPU) unless a run is long: see SKILL.md')
    parser.add_argument('--env', action='append', default=[], help='extra KEY=VALUE for the game, e.g. DIORAMA_GIF=1')
    parser.add_argument('--keep', action='store_true', help='stop the VM instead of deleting it, to look around after')
    parser.add_argument('--snapshot', help='saved snapshot to start from (default: the newest diorama-gametest-*)')
    args = parser.parse_args()
    sys.stdout.reconfigure(line_buffering=True)

    repo = Path(subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True,
                               cwd=HERE, check=True).stdout.strip())
    nodes = [n for n in args.nodes.split(',') if n]
    missing = [n for n in nodes if not (repo / 'versions' / n).is_dir()]
    if missing:
        sys.exit(f'no node versions/{", ".join(missing)}')
    branch = subprocess.run(['git', 'rev-parse', '--abbrev-ref', 'HEAD'], capture_output=True, text=True,
                            cwd=repo).stdout.strip()
    label = ''.join(c if c.isalnum() else '-' for c in branch)[:40] or 'detached'

    snapshot = args.snapshot or newest_snapshot()
    if snapshot:
        say(None, f'starting from snapshot {snapshot}')
    else:
        say(None, f'no {SNAPSHOT_PREFIX}* snapshot: each VM sets up from scratch (about 5 min more)')

    # Delete the VMs if this script is stopped. A hard kill skips this; then `boat list` shows them.
    atexit.register(remove_all_on_exit)
    for sig in (signal.SIGINT, signal.SIGTERM, getattr(signal, 'SIGBREAK', None)):
        if sig:
            signal.signal(sig, remove_all_on_exit)

    start = time.time()
    codes = {}

    def one(node):
        try:
            codes[node] = run_node(node, args, repo, label, snapshot, start)
        except Exception as e:
            say(node, f'!! {e}')
            codes[node] = 2

    threads = [threading.Thread(target=one, args=(n,), daemon=True) for n in nodes]
    for i, t in enumerate(threads):
        # 10 starts a minute, inside the account's 12, which other agents share.
        if i:
            time.sleep(6)
        t.start()
    for t in threads:
        while t.is_alive():
            t.join(1)
    if len(nodes) > 1:
        say(None, 'summary: ' + ', '.join(f'{n} {"passed" if codes[n] == 0 else "FAILED"}' for n in nodes))
    sys.exit(max(codes.values()))


if __name__ == '__main__':
    main()
