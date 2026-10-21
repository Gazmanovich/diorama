"""Run Diorama's client game tests on a boat.dev VM: software rendering, no GPU. See SKILL.md.

usage: python boat_run.py <node> --tests <sections|all> [--env KEY=VALUE]... [--keep]

Starts a VM from the warmed snapshot, pushes this working tree (uncommitted changes included),
runs :<node>:runClientGameTest, copies the results to build/boat-runs/<time>-<node>/ and deletes
the VM. Exits with the test run's exit code.
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tarfile
import time
from pathlib import Path

SNAPSHOT = 'diorama-gametest'
HERE = Path(__file__).resolve().parent


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


def boat(*args, cwd=None):
    return subprocess.run([BOAT, '--no-update', *args], capture_output=True, text=True, encoding='utf-8',
                          errors='replace', cwd=cwd)


def events(output):
    for line in output.splitlines():
        try:
            yield json.loads(line)
        except ValueError:
            pass


def create_vm():
    """A new VM, from the warmed snapshot if it exists. Waits up to 20 min for a free slot."""
    use_snapshot = True
    waited = 0
    while True:
        args = ['new', '--type', 'default', '--no-env', '--ttl', '7200', '--json']
        result = boat(*args, *(['--from', SNAPSHOT] if use_snapshot else []))
        found = list(events(result.stdout + result.stderr))
        ready = [e for e in found if e.get('event') == 'ready']
        if ready:
            return ready[-1]['id']
        error = next((e for e in found if e.get('event') == 'error' or 'error' in e), {})
        created = next((e['id'] for e in found if e.get('event') == 'created'), None)
        if created:
            boat('delete', created, '--yes')
        if error.get('code') == 'snapshot_not_found' and use_snapshot:
            print(f'no snapshot named {SNAPSHOT}: setting up a fresh VM (about 5 min more)')
            use_snapshot = False
            continue
        if (error.get('status') == 429 or 'limit' in str(error.get('code', ''))) and waited < 1200:
            if not waited:
                print(f'boat.dev has no free VM ({error.get("error")}); waiting for one')
            time.sleep(60)
            waited += 60
            continue
        sys.exit(f'boat new failed: {error or (result.stdout + result.stderr).strip()[-2000:]}')


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('node', help='Stonecutter node, as in versions/: 26.3, 1.21.11, 26.3-neoforge, ...')
    parser.add_argument('--tests', required=True, help='DIORAMA_TESTS sections, comma-separated, or "all"')
    parser.add_argument('--env', action='append', default=[], help='extra KEY=VALUE for the game, e.g. DIORAMA_GIF=1')
    parser.add_argument('--keep', action='store_true', help='stop the VM instead of deleting it, to look around after')
    args = parser.parse_args()
    sys.stdout.reconfigure(line_buffering=True)

    repo = Path(subprocess.run(['git', 'rev-parse', '--show-toplevel'], capture_output=True, text=True,
                               cwd=HERE, check=True).stdout.strip())
    if not (repo / 'versions' / args.node).is_dir():
        sys.exit(f'no node versions/{args.node}')
    out = repo / 'build' / 'boat-runs' / f'{time.strftime("%Y%m%d-%H%M%S")}-{args.node}'
    out.mkdir(parents=True)

    start = time.time()
    vm = create_vm()
    print(f'VM {vm} ready after {time.time() - start:.0f} s')
    try:
        boat('exec', vm, '--', 'rm', '-rf', '/home/user/src')  # the snapshot's copy; push only adds files
        pushed = boat('push', vm, str(repo), '--to', '/home/user/src')
        if pushed.returncode:
            sys.exit(f'boat push failed: {(pushed.stdout + pushed.stderr).strip()[-2000:]}')
        # A Windows checkout can have CRLF line endings, which bash rejects.
        boat('exec', vm, '--', 'sed', '-i', r's/\r$//', '/home/user/src/.claude/skills/boat-game-test/sandbox.sh')
        print(f'pushed {repo.name}; running {args.tests} on {args.node}')
        with open(out / 'vm.log', 'w', encoding='utf-8') as log:
            subprocess.run([BOAT, '--no-update', 'exec', vm, '--timeout', '0', '--', 'bash',
                            '/home/user/src/.claude/skills/boat-game-test/sandbox.sh', args.node, args.tests, *args.env],
                           stdout=log, stderr=subprocess.STDOUT)
        fetched = boat('scp', f'{vm}:/home/user/result.tgz', 'result.tgz', cwd=out)
        if fetched.returncode or not (out / 'result.tgz').exists():
            sys.exit(f'no results came back; see {out / "vm.log"}')
        with tarfile.open(out / 'result.tgz') as tar:
            tar.extractall(out, filter='data')
        (out / 'result.tgz').unlink()
        usage = next(events(boat('usage', vm, '--json').stdout), {})
    finally:
        boat('stop' if args.keep else 'delete', vm, *([] if args.keep else ['--yes']))

    result = json.loads((out / 'result.json').read_text())
    shots = sorted((out / 'screenshots').glob('*.png')) if (out / 'screenshots').exists() else []
    print(f'exit={result["exit"]} after {result["minutes"]} min of tests, '
          f'{(time.time() - start) / 60:.0f} min in all, ${usage.get("dollars", 0):.3f}')
    print(f'{len(shots)} screenshots in {out / "screenshots"}')
    if result['exit']:
        lines = (out / 'gradle.log').read_text(encoding='utf-8', errors='replace').splitlines()
        cause = [l for l in lines if any(k in l for k in ('AssertionError', 'Description:', 'Caused by', 'FAILED'))]
        print('failure lines:', *cause[:8], sep='\n  ')
        print(f'full output: {out / "gradle.log"}; game log: {out / "latest.log"}')
    if args.keep:
        print(f'VM {vm} is stopped, not deleted: boat resume {vm}, and boat delete {vm} --yes when done')
    sys.exit(result['exit'])


main()
