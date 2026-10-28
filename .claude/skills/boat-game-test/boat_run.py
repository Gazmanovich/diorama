"""Run Diorama's client game tests on boat.dev VMs: software rendering, no GPU. See SKILL.md.

usage: python boat_run.py <node[,node...]> --tests <sections|all> [--type default|large] [--env KEY=VALUE]... [--keep]

Each node gets its own VM, started at the same time from the newest warmed snapshot. The script
pushes this working tree (uncommitted changes included), runs :<node>:runClientGameTest, copies the
results to build/boat-runs/<time>-<node>/ and deletes the VM. Exits non-zero if any node failed.
"""
import argparse
import concurrent.futures
import json
import os
import secrets
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
TTL = 7200
# SKILL.md gives the command 2 hours. sandbox.sh stops the tests at 90 min; every boat call is cut
# off at 105 min so fetching results fits, and cleanup has the rest.
WORK_MINUTES = 105
# A new VM fetches each snapshot file from boat.dev's storage the first time it's read, which made
# Gradle's start on a fresh VM about 80 s instead of 5. Reading the files a node's run opens (lists
# made by warm.sh) 64 at a time while the tree is pushed saved about a minute on a 26.3 run.
PREFETCH = 'tr "\\n" "\\0" < ~/prefetch-lists/{node}.list | xargs -0 -P 64 -n 20 cat > /dev/null 2>&1'
GONE = ('deleting', 'deleted')


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
# Every VM this run starts is named <PREFIX><what>; the random token makes the prefix this run's
# alone, so cleanup can delete by name without touching another agent's VMs.
PREFIX = f'diorama-test-{secrets.token_hex(3)}-'
work_deadline = time.time() + WORK_MINUTES * 60
# Names to stop rather than delete, recorded before the stop is tried.
keep_names = set()
# Set on a refused start or Ctrl-C: no thread starts another VM after it.
stop_starting = threading.Event()
interrupted = threading.Event()


class Refused(RuntimeError):
    pass


def say(node, text):
    with print_lock:
        print(f'[{node}] {text}' if node else text, flush=True)


def text_of(output):
    return output.decode('utf-8', 'replace') if isinstance(output, bytes) else (output or '')


def boat(*args, cwd=None, timeout=120):
    if args[0] in ('list', 'delete', 'stop', 'info'):
        # Cleanup runs past the work deadline, in short calls.
        timeout = min(timeout, 60)
    else:
        timeout = min(timeout, work_deadline - time.time())
        if timeout <= 0:
            return subprocess.CompletedProcess(args, 124, '', f'boat {args[0]} not run: past the deadline')
    try:
        return subprocess.run([BOAT, '--no-update', *args], capture_output=True, text=True, encoding='utf-8',
                              errors='replace', cwd=cwd, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        # What it printed before the timeout can hold the id of a VM it made.
        return subprocess.CompletedProcess(args, 124, text_of(e.stdout),
                                           text_of(e.stderr) + f'\nboat {args[0]} timed out after {timeout:.0f} s')


def boat_retry(*args, cwd=None, timeout=120):
    """boat for steps that are safe to repeat, tried three times: the API fails now and then."""
    for attempt in range(3):
        result = boat(*args, cwd=cwd, timeout=timeout)
        if result.returncode == 0 or time.time() > work_deadline:
            break
        if attempt < 2:
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


def our_vms():
    """[(name, id, state)] of this run's VMs on the account, stopped ones included; None when boat
    list fails, which is not the same as having none."""
    listed = next(events(boat('list', '--all', '--json').stdout), None)
    if not listed or 'sandboxes' not in listed:
        return None
    return [(v['name'], v['id'], v.get('state')) for v in listed['sandboxes'] if v.get('name', '').startswith(PREFIX)]


def find_vm(name):
    found = our_vms()
    return None if found is None else next((vm for n, vm, _ in found if n == name), '')


def settle(name, vm, vm_state):
    """Deletes one of this run's VMs, or stops it if it's kept; None when that worked, else why not."""
    if name in keep_names:
        if vm_state in ('stopped', 'stopping') or boat('stop', vm, '--json').returncode == 0:
            return 'kept'
        return f'kept, but it is {vm_state} and boat stop failed: boat stop {vm}'
    if boat('delete', vm, '--yes', '--json').returncode == 0:
        return None
    # Already being deleted (by its own thread, a moment ago) also fails a delete.
    sandbox = next(events(boat('info', vm, '--json', timeout=30).stdout), {}).get('sandbox')
    if sandbox is None:
        return 'delete failed and its state is unknown'
    return None if sandbox.get('state') in GONE else f'delete failed, {sandbox.get("state")}'


def ignore_signals():
    for sig in (signal.SIGINT, signal.SIGTERM, getattr(signal, 'SIGBREAK', None)):
        if sig:
            signal.signal(sig, signal.SIG_IGN)


def reconcile(rounds=1):
    """The final cleanup, on normal exit, on an error and after Ctrl-C: deletes every VM with this
    run's prefix, stopping instead the ones kept on purpose, and says what's left. Signals are
    ignored meanwhile, so a second Ctrl-C can't cut it short."""
    ignore_signals()
    left = None
    for i in range(rounds):
        if i:
            # A `boat new` that was under way can still make its VM.
            time.sleep(20)
        found = our_vms()
        if found is None:
            left = None
            continue
        found = [f for f in found if f[2] not in GONE]
        with concurrent.futures.ThreadPoolExecutor(8) as pool:
            outcomes = list(pool.map(lambda f: settle(*f), found))
        left = [(name, vm, why) for (name, vm, _), why in zip(found, outcomes) if why]
    if left is None:
        say(None, f'!! could not list VMs to clean up: find yours with `boat list --all`, named {PREFIX}*, '
                  'and delete them with boat delete <id> --yes')
        return
    for name, vm, why in left:
        if why == 'kept':
            say(None, f'VM {vm} ({name}) is kept, stopped: boat resume {vm} to look at it, boat delete {vm} --yes when done')
        elif why.startswith('kept'):
            say(None, f'!! VM {vm} ({name}) is {why}')
        else:
            say(None, f'!! VM {vm} ({name}) is still on the account ({why}): boat delete {vm} --yes')


def newest_snapshot():
    """The most recent saved snapshot named diorama-gametest-<commit>, or None."""
    listed = next(events(boat('snapshots', '--json').stdout), {})
    named = [s for s in listed.get('named', []) if s['name'].startswith(SNAPSHOT_PREFIX) and s['status'] == 'ready']
    return max(named, key=lambda s: s['createdAt'])['name'] if named else None


def wait_for_start_slot():
    """Waits (on `boat limits`, which is free) until the account may start a VM this minute, so a
    start isn't refused: refused starts count against the limits too. Raises when the hour's or
    the day's starts, or the plan's hours, are gone."""
    until = time.time() + 180
    while True:
        if stop_starting.is_set():
            raise Refused('not starting: an earlier start was refused, or the script is stopping')
        limits = next(events(boat('limits', '--json', timeout=30).stdout), None)
        if not limits or 'starts' not in limits:
            # boat limits failing says nothing about the limits; boat new will say.
            return
        starts = limits['starts']
        if starts['day']['remaining'] < 1 or starts['hour']['remaining'] < 1 or limits.get('canStart') is False:
            stop_starting.set()
            raise Refused(f'boat.dev has no starts left ({starts["hour"]["remaining"]} this hour, '
                          f'{starts["day"]["remaining"]} today) or cannot start ({limits.get("startBlockedReason")}); '
                          'see `boat limits` and run later')
        if starts['minute']['remaining'] >= 1:
            return
        if time.time() > until:
            raise Refused('no start free for 3 min: other agents are starting VMs too; run later')
        time.sleep(10)


def create_vm(size, snapshot, what, ttl=TTL):
    """A new VM named PREFIX + what. One start per call and no retry: a failed start that did make
    a VM is found by name, and one that didn't fails the node."""
    name = PREFIX + what
    wait_for_start_slot()
    if stop_starting.is_set():
        raise Refused('not starting: an earlier start was refused, or the script is stopping')
    result = boat('new', '--type', size, '--no-env', '--ttl', str(ttl), '--name', name, '--json',
                  *(['--from', snapshot] if snapshot else []), timeout=300)
    found = list(events(result.stdout + result.stderr))
    if any(e.get('event') == 'ready' for e in found):
        return next(e['id'] for e in found if e.get('event') == 'ready')
    error = next((e for e in found if e.get('event') == 'error' or 'error' in e), {})
    if error.get('status') == 429 or 'limit' in str(error.get('code', '')):
        stop_starting.set()
        raise Refused(f'boat.dev refused the start ({error.get("code")}: {error.get("error")}); '
                      'see `boat limits` and run later')
    # The answer got lost or the start failed: boat.dev may have made the VM anyway.
    vm = next((e['id'] for e in found if e.get('event') == 'created' and 'id' in e), None)
    for _ in range(3):
        if vm:
            break
        time.sleep(20)
        vm = find_vm(name)
        if vm is not None:
            break
    if not vm:
        raise RuntimeError(f'boat new failed: {error or tail(result)}')
    for _ in range(12):
        sandbox = next(events(boat('info', vm, '--json', timeout=30).stdout), {}).get('sandbox', {})
        if sandbox.get('state') in ('ready', 'idle', 'running'):
            return vm
        time.sleep(10)
    raise RuntimeError(f'VM {vm} never came up')


def run_script(vm, script, *args, deadline):
    """Starts a VM script detached and waits for the exit code it writes to ~/run-exit; returns it
    with the script's log. Each step is a short API call, so the run survives the API failing for a
    while, which happens: long live output streams drop, and calls return 500 now and then. None of
    this counts against start limits."""
    cleared = boat_retry('exec', vm, '--timeout', '30', '--json', '--', 'rm', '-f', '/home/user/run-started',
                         '/home/user/run-exit')
    if next(events(cleared.stdout), {}).get('exitCode') != 0:
        raise RuntimeError(f'could not clear the last run\'s exit code: {tail(cleared)}')
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
        if interrupted.is_set():
            raise RuntimeError('stopped: the script was interrupted')
        read = next(events(boat('exec', vm, '--timeout', '30', '--json', '--', 'cat', '/home/user/run-exit',
                                timeout=60).stdout), {})
        if read.get('exitCode') == 0 and read.get('stdout', '').strip().isdigit():
            log = next(events(boat('exec', vm, '--timeout', '60', '--json', '--', 'bash', '-c',
                                   'tail -c 20000 ~/sandbox.log ~/warm.out 2>/dev/null', timeout=120).stdout), {})
            return int(read['stdout'].strip()), log.get('stdout', '')
        # exitCode 1 is cat finding no file yet: still running. Anything else is the API failing.
        misses = 0 if read.get('exitCode') == 1 else misses + 1
        if misses == 20:
            raise RuntimeError(f'lost contact with VM {vm} for 10 min')
    raise RuntimeError(f'{script} was still running at the deadline')


def run_node(node, args, repo, snapshot, start):
    out = repo / 'build' / 'boat-runs' / f'{time.strftime("%Y%m%d-%H%M%S")}-{PREFIX}{node}'
    out.mkdir(parents=True)
    name = PREFIX + node
    if args.keep:
        keep_names.add(name)
    vm = create_vm(args.type, snapshot, node)
    say(node, f'VM {vm} ({args.type}) ready after {time.time() - start:.0f} s')
    ran = False
    try:
        if interrupted.is_set():
            raise RuntimeError('stopped: the script was interrupted')
        if not args.no_prefetch:
            boat('exec', vm, '--detach', '--json', '--', 'bash', '-c', PREFETCH.format(node=node))
        # The snapshot's copy and results: push only adds files, and an old result.tgz could pass for this run's.
        cleared = boat_retry('exec', vm, '--timeout', '120', '--', 'rm', '-rf', '/home/user/src', '/home/user/result',
                             '/home/user/result.tgz')
        if cleared.returncode:
            raise RuntimeError(f'could not clear the VM: {tail(cleared)}')
        pushed = boat_retry('push', vm, str(repo), '--to', '/home/user/src', timeout=300)
        if pushed.returncode:
            raise RuntimeError(f'boat push failed: {tail(pushed)}')
        # A Windows checkout can have CRLF line endings, which bash rejects.
        normalized = boat_retry('exec', vm, '--timeout', '60', '--', 'sed', '-i', r's/\r$//', SANDBOX_SH)
        if normalized.returncode:
            raise RuntimeError(f'could not prepare sandbox.sh: {tail(normalized)}')
        say(node, f'pushed after {time.time() - start:.0f} s; running {args.tests}')
        # The tests get what's left after setup, less 2 min to pack the results and 10 to fetch
        # them and delete the VM.
        test_seconds = int(min(5400, work_deadline - 720 - time.time()))
        if test_seconds < 300:
            raise RuntimeError('setup took so long that no time is left for the tests')
        ran = True
        code, log = run_script(vm, SANDBOX_SH, node, args.tests, *args.env, f'TEST_SECONDS={test_seconds}',
                               deadline=work_deadline - 600)
        (out / 'vm.log').write_text(log, encoding='utf-8')
        if code:
            raise RuntimeError(f'the VM script failed setting up or packing results; see {out / "vm.log"}')
        fetched = boat_retry('scp', f'{vm}:/home/user/result.tgz', 'result.tgz', cwd=out, timeout=300)
        if fetched.returncode or not (out / 'result.tgz').exists():
            raise RuntimeError(f'no results came back; see {out / "vm.log"}')
        with tarfile.open(out / 'result.tgz') as tar:
            tar.extractall(out, filter='data')
        (out / 'result.tgz').unlink()
        usage = next(events(boat('usage', vm, '--json', timeout=30).stdout), {})
    except Exception:
        # Once the tests ran, the VM holds their evidence: keep it, stopped, rather than lose it.
        if ran and not interrupted.is_set():
            keep_names.add(name)
            say(node, f'!! keeping VM {vm} with what the run left in ~/result.tgz and ~/sandbox.log: '
                      f'boat resume {vm}, boat scp {vm}:/home/user/result.tgz ., then boat delete {vm} --yes')
        raise
    finally:
        # A failure here is caught by reconcile() at the end.
        boat('stop' if name in keep_names else 'delete', vm, '--json', *([] if name in keep_names else ['--yes']))

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
    return result['exit']


def interrupt_on_signals():
    """SIGTERM and SIGBREAK raise KeyboardInterrupt in the main thread, like Ctrl-C, so the cleanup
    runs in ordinary code rather than inside a signal handler."""
    def interrupt(*_):
        raise KeyboardInterrupt
    for sig in (signal.SIGTERM, getattr(signal, 'SIGBREAK', None)):
        if sig:
            signal.signal(sig, interrupt)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('nodes', help='Stonecutter nodes, as in versions/, comma-separated: 26.3,1.21.1,26.3-neoforge')
    parser.add_argument('--tests', required=True, help='DIORAMA_TESTS sections, comma-separated, or "all"')
    parser.add_argument('--type', default='default', choices=['small', 'default', 'large'],
                        help='VM size. default (4 vCPU) unless a run is long: see SKILL.md')
    parser.add_argument('--env', action='append', default=[], help='extra KEY=VALUE for the game, e.g. DIORAMA_GIF=1')
    parser.add_argument('--keep', action='store_true', help='stop the VM instead of deleting it, to look around after')
    parser.add_argument('--snapshot', help='saved snapshot to start from (default: the newest diorama-gametest-*)')
    parser.add_argument('--no-prefetch', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    sys.stdout.reconfigure(line_buffering=True)

    repo = Path(subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True,
                               cwd=HERE, check=True).stdout.strip())
    nodes = list(dict.fromkeys(n for n in args.nodes.split(',') if n))
    missing = [n for n in nodes if not (repo / 'versions' / n).is_dir()]
    if missing:
        sys.exit(f'no node versions/{", ".join(missing)}')

    snapshot = args.snapshot or newest_snapshot()
    if snapshot:
        say(None, f'starting from snapshot {snapshot}')
    else:
        say(None, f'no {SNAPSHOT_PREFIX}* snapshot: each VM sets up from scratch (about 5 min more)')
    say(None, f'this run\'s VMs are named {PREFIX}<node>')

    interrupt_on_signals()
    start = time.time()
    codes = {}

    def one(node):
        try:
            codes[node] = run_node(node, args, repo, snapshot, start)
        except Exception as e:
            say(node, f'!! {e}')
            codes[node] = 2

    threads = [threading.Thread(target=one, args=(n,), daemon=True) for n in nodes]
    try:
        for i, t in enumerate(threads):
            # 10 starts a minute from this script, inside the account's 12; wait_for_start_slot
            # covers other agents' starts.
            if i:
                time.sleep(6)
            t.start()
        for t in threads:
            while t.is_alive():
                t.join(1)
    except KeyboardInterrupt:
        interrupted.set()
        stop_starting.set()
        ignore_signals()
        say(None, 'stopping: each node deletes its VM, then the script checks for any left')
        # A thread inside `boat new` learns its VM's id within 5 min, then deletes it.
        until = time.time() + 360
        for t in threads:
            if t.is_alive():
                t.join(max(0, until - time.time()))
        reconcile(rounds=2)
        sys.exit(130)
    reconcile()
    if len(nodes) > 1:
        say(None, 'summary: ' + ', '.join(f'{n} {"passed" if codes.get(n) == 0 else "FAILED"}' for n in nodes))
    sys.exit(max(codes.values(), default=2))


if __name__ == '__main__':
    main()
