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
# The VM's own limit. sandbox.sh stops the tests at 90 min; polling stops at 100, which leaves
# time to fetch the results and delete the VM inside the 2 hours SKILL.md gives the command.
TTL = 7200
POLL_MINUTES = 100
# A new VM fetches each snapshot file from boat.dev's storage the first time it's read, which made
# Gradle's start on a fresh VM about 80 s instead of 5. Reading the files a node's run opens (lists
# made by warm.sh) 64 at a time while the tree is pushed saved about a minute on a 26.3 run.
PREFETCH = 'tr "\\n" "\\0" < ~/prefetch-lists/{node}.list | xargs -0 -P 64 -n 20 cat > /dev/null 2>&1'


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
state_lock = threading.Lock()
# Names of the VMs this process asked for, and the ids of those still on the account. A name is
# recorded before `boat new`, so cleanup can find a VM whose id never came back.
our_names = set()
live_vms = set()
kept_vms = set()
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
    try:
        return subprocess.run([BOAT, '--no-update', *args], capture_output=True, text=True, encoding='utf-8',
                              errors='replace', cwd=cwd, timeout=timeout)
    except subprocess.TimeoutExpired as e:
        # What it printed before the timeout can hold the id of a VM it made.
        return subprocess.CompletedProcess(args, 124, text_of(e.stdout),
                                           text_of(e.stderr) + f'\nboat {args[0]} timed out after {timeout} s')


def boat_retry(*args, cwd=None, timeout=120):
    """boat for steps that are safe to repeat, tried three times: the API fails now and then."""
    for attempt in range(3):
        result = boat(*args, cwd=cwd, timeout=timeout)
        if result.returncode == 0:
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
    """{name: (id, state)} for this process's VMs on the account, stopped ones included; None when
    boat list fails, which is not the same as having none."""
    listed = next(events(boat('list', '--all', '--json').stdout), None)
    if not listed or 'sandboxes' not in listed:
        return None
    with state_lock:
        names = set(our_names)
    return {v['name']: (v['id'], v.get('state')) for v in listed['sandboxes'] if v.get('name') in names}


def remove_vm(vm, keep=False):
    """Deletes the VM (or stops it, to keep), retrying; says loudly when that fails. boat.dev
    finishes a delete in the background; main() checks at the end that the VMs are gone."""
    for _ in range(3):
        if boat('stop' if keep else 'delete', vm, '--json', *([] if keep else ['--yes'])).returncode == 0:
            with state_lock:
                live_vms.discard(vm)
                if keep:
                    kept_vms.add(vm)
            return
        time.sleep(10)
    say(None, f'!! could not {"stop" if keep else "delete"} VM {vm}. Run: boat delete {vm} --yes')


def remove_all(*_):
    """Deletes every VM of this process, including any still being created: on Ctrl-C, and at exit."""
    stop_starting.set()
    if _:
        interrupted.set()
    with state_lock:
        ids = set(live_vms)
    for vm in ids:
        remove_vm(vm)
    # A `boat new` that was under way can still make its VM; look for our names, twice.
    for wait in (0, 15) if _ else (0,):
        time.sleep(wait)
        found = our_vms() or {}
        for vm, vm_state in found.values():
            if vm not in ids and vm not in kept_vms and vm_state not in ('deleting', 'deleted', 'archived'):
                remove_vm(vm)
                ids.add(vm)
    if _:
        os._exit(130)


def install_cleanup():
    atexit.register(remove_all)
    for sig in (signal.SIGINT, signal.SIGTERM, getattr(signal, 'SIGBREAK', None)):
        if sig:
            signal.signal(sig, remove_all)


def newest_snapshot():
    """The most recent saved snapshot named diorama-gametest-<commit>, or None."""
    listed = next(events(boat('snapshots', '--json').stdout), {})
    named = [s for s in listed.get('named', []) if s['name'].startswith(SNAPSHOT_PREFIX) and s['status'] == 'ready']
    return max(named, key=lambda s: s['createdAt'])['name'] if named else None


def wait_for_start_slot():
    """Waits (on `boat limits`, which is free) until the account may start a VM this minute, so a
    start isn't refused: refused starts count against the limits too. Raises when the hour's or
    the day's starts, or the plan's hours, are gone."""
    for _ in range(18):
        if stop_starting.is_set():
            raise Refused('not starting: an earlier start was refused, or the script is stopping')
        limits = next(events(boat('limits', '--json').stdout), None)
        if not limits or 'starts' not in limits:
            return
        starts = limits['starts']
        if starts['day']['remaining'] < 1 or starts['hour']['remaining'] < 1 or limits.get('canStart') is False:
            stop_starting.set()
            raise Refused(f'boat.dev has no starts left this hour or day ({starts["hour"]["remaining"]} this hour, '
                          f'{starts["day"]["remaining"]} today) or cannot start ({limits.get("startBlockedReason")}); '
                          'see `boat limits` and run later')
        if starts['minute']['remaining'] >= 1:
            return
        time.sleep(10)
    raise Refused('no start free for 3 min: other agents are starting VMs too; run later')


def wait_up(vm):
    """Waits for a VM whose `boat new` answer got lost to come up; deletes it if it doesn't."""
    for _ in range(12):
        sandbox = next(events(boat('info', vm, '--json', timeout=30).stdout), {}).get('sandbox', {})
        if sandbox.get('state') in ('ready', 'idle', 'running'):
            return vm
        time.sleep(10)
    remove_vm(vm)
    raise RuntimeError(f'VM {vm} never came up')


def create_vm(size, snapshot, name, ttl=TTL):
    """A new VM from the snapshot, named so it can be found if the answer gets lost. One start per
    call and no retry: a failed start that did make a VM is found by name, and one that didn't
    fails the node."""
    with state_lock:
        our_names.add(name)
    wait_for_start_slot()
    result = boat('new', '--type', size, '--no-env', '--ttl', str(ttl), '--name', name, '--json',
                  *(['--from', snapshot] if snapshot else []), timeout=300)
    found = list(events(result.stdout + result.stderr))
    vm = next((e['id'] for e in found if e.get('event') in ('ready', 'created') and 'id' in e), None)
    if vm:
        with state_lock:
            live_vms.add(vm)
    if any(e.get('event') == 'ready' for e in found):
        return vm
    error = next((e for e in found if e.get('event') == 'error' or 'error' in e), {})
    if error.get('status') == 429 or 'limit' in str(error.get('code', '')):
        stop_starting.set()
        if vm:
            remove_vm(vm)
        raise Refused(f'boat.dev refused the start ({error.get("code")}: {error.get("error")}); '
                      'see `boat limits` and run later')
    if not vm:
        # The answer got lost or the start failed: boat.dev may have made the VM anyway.
        for _ in range(3):
            time.sleep(20)
            listed = our_vms()
            if listed is not None:
                vm = listed.get(name, (None, None))[0]
                break
        else:
            raise RuntimeError(f'boat new failed ({error or tail(result)}) and boat list is failing too: '
                               f'look for a VM named {name} with `boat list --all` and delete it')
    if vm:
        with state_lock:
            live_vms.add(vm)
        return wait_up(vm)
    raise RuntimeError(f'boat new failed: {error or tail(result)}')


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


def run_node(node, args, repo, label, snapshot, start):
    out = repo / 'build' / 'boat-runs' / f'{time.strftime("%Y%m%d-%H%M%S")}-{os.getpid()}-{node}'
    out.mkdir(parents=True)
    vm = create_vm(args.type, snapshot, f'diorama-test-{label}-{node}-{os.getpid()}')
    say(node, f'VM {vm} ({args.type}) ready after {time.time() - start:.0f} s')
    ran = False
    keep = args.keep
    try:
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
        ran = True
        code, log = run_script(vm, SANDBOX_SH, node, args.tests, *args.env, deadline=start + POLL_MINUTES * 60)
        (out / 'vm.log').write_text(log, encoding='utf-8')
        if code:
            raise RuntimeError(f'the VM script failed setting up or packing results; see {out / "vm.log"}')
        fetched = boat_retry('scp', f'{vm}:/home/user/result.tgz', 'result.tgz', cwd=out, timeout=300)
        if fetched.returncode or not (out / 'result.tgz').exists():
            raise RuntimeError(f'no results came back; see {out / "vm.log"}')
        with tarfile.open(out / 'result.tgz') as tar:
            tar.extractall(out, filter='data')
        (out / 'result.tgz').unlink()
        usage = next(events(boat('usage', vm, '--json').stdout), {})
    except Exception:
        # Once the tests ran, the VM holds their evidence: keep it stopped rather than lose it.
        if ran and not interrupted.is_set():
            keep = True
            say(node, f'!! VM {vm} kept, stopped, with what the run left in ~/result.tgz and ~/sandbox.log: '
                      f'boat resume {vm}, boat scp {vm}:/home/user/result.tgz ., then boat delete {vm} --yes')
        raise
    finally:
        remove_vm(vm, keep=keep)

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


def check_gone():
    """Says which of this process's VMs are still on the account, other than ones kept on purpose."""
    for wait in (5, 30):
        # A delete finishes in the background.
        time.sleep(wait)
        found = our_vms()
        if found is None:
            say(None, '!! could not list VMs to check they are gone: run `boat list --all`')
            return
        left = [f'{vm} ({name}, {vm_state})' for name, (vm, vm_state) in found.items()
                if vm not in kept_vms and vm_state not in ('deleting', 'deleted', 'archived')]
        if not left:
            return
    if left:
        say(None, '!! still on the account: ' + ', '.join(left) + '; delete them with boat delete <id> --yes')


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
    branch = subprocess.run(['git', 'rev-parse', '--abbrev-ref', 'HEAD'], capture_output=True, text=True,
                            cwd=repo).stdout.strip()
    label = ''.join(c if c.isalnum() else '-' for c in branch)[:40] or 'detached'

    snapshot = args.snapshot or newest_snapshot()
    if snapshot:
        say(None, f'starting from snapshot {snapshot}')
    else:
        say(None, f'no {SNAPSHOT_PREFIX}* snapshot: each VM sets up from scratch (about 5 min more)')
    say(None, f'VMs are named diorama-test-{label}-<node>-{os.getpid()}')

    # Delete the VMs if this script is stopped. A hard kill skips this; then `boat list --all` shows them.
    install_cleanup()
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
        # 10 starts a minute from this script, inside the account's 12; wait_for_start_slot covers
        # other agents' starts.
        if i:
            time.sleep(6)
        t.start()
    for t in threads:
        while t.is_alive():
            t.join(1)
    check_gone()
    if len(nodes) > 1:
        say(None, 'summary: ' + ', '.join(f'{n} {"passed" if codes[n] == 0 else "FAILED"}' for n in nodes))
    sys.exit(max(codes.values()))


if __name__ == '__main__':
    main()
