---
name: boat-game-test
description: Run Diorama's client game tests on boat.dev VMs instead of this PC. Use when a functional game test run (does it pass, do the screenshots show the right thing) would wait for the local Minecraft lock, or when several versions need testing at once.
---

# Game tests on boat.dev

A boat.dev VM has no GPU: Minecraft renders with OpenGL on the CPU, through Mesa. Tests pass the same way and screenshots show the same scenes, only slower than on Dara's PC. That makes it the place for functional checks and the wrong place for anything about the GPU:

- **boat.dev**: a feature works, a screenshot shows what it should, a fix holds on other versions.
- **This PC, under the Minecraft lock**: fps, frame time and bake-speed measurements, GPU bake (`DIORAMA_GPU_BAKE`), pixel-for-pixel comparisons against an RTX image, and anything driven on the desktop.

## Run

1. Pick the test sections your change touches: the names in `selectedTests()` in `src/gametest/java/dev/diorama/test/DioramaClientTest.java` (`tour`, `waypoints`, `multiplayer`, ...). Run the sections you need, not `all`.
2. Run from your checkout. It pushes your working tree, uncommitted changes included, to one VM per node, all at once:

   ```
   python .claude/skills/boat-game-test/boat_run.py <node[,node...]> --tests <section,section>
   ```

   `<node>` is a folder in `versions/`: `26.3`, `1.21.11`, `26.3-neoforge`, ... Pass game environment variables with `--env DIORAMA_GIF=1`. Run it in the foreground with a 2-hour timeout (`timeout: 7200000`); the script gives up at 115 min.
3. Open the screenshots it lists (`build/boat-runs/<time>-<node>/screenshots/`) and look at them. A pass with the wrong picture is still a failure. On a failure the script prints the assertion; `gradle.log` and `latest.log` in the same folder have the rest.
4. Run `boat list --all` (plain `boat list` hides stopped VMs) and delete any VM of yours still there (`boat delete <id> --yes`). Yours are named `diorama-test-<your branch>-<node>-<pid>`; leave the others, they belong to other agents.

Done when every node exits 0, you've seen the screenshots for the sections you changed, and `boat list --all` shows none of your VMs.

## Size and cost

The plan (`box_20`) includes 555 VM-hours a month, counted at the size's rate: small 0.5x, default 1x, large 2x. Past them, runs are paid from a wallet that holds $0, so they stop. Measured on 2026-10-28 from the warm snapshot, one run each (times on boat.dev vary by a minute between runs):

| `screens` section | default (4 vCPU) | large (8 vCPU) |
|---|---|---|
| 26.3: game / whole run | 4.6 / 6.6 min | 3.0 / 5.0 min |
| 1.21.1: game / whole run | 3.1 / 6.0 min | 2.7 / 5.0 min |

About 2.5 min of every run is fixed: VM start, push, Gradle, booting Minecraft and baking the first map. Large only speeds up the game part, so a short run saves a minute or so at twice the VM-hours. Use **default** for a few sections, and **`--type large`** for long runs (`all`, the multiplayer sections) where the game part dominates.

## Limits

100 VMs at once; starts are limited to 12 a minute, 60 an hour and 200 a day, and a refused start counts too. The script never retries a refused start: it fails, and you decide when to run again. Before a fan-out of more than about 10 VMs, run `boat limits` (free, counts nothing) and check `starts` and `planIncluded.vmHoursRemaining`. Other agents share the same account and the same limits.

## Warm snapshot

VMs start from the newest saved snapshot named `diorama-gametest-<commit>`: JDK 25 (which runs every node, 1.20.1 included), Mesa, and Gradle, Loom and NeoForge caches with every node compiled and every node's Minecraft, libraries and assets downloaded, warmed on master at `<commit>`. The script prints which one it used. A snapshot only holds the disk: a stopped, forked or resumed VM starts with no processes, so every run boots Minecraft afresh.

A new VM fetches each snapshot file from boat.dev's storage the first time it's read. The snapshot also holds the list of files each node's boot opens (`~/prefetch-lists/<node>.list`), and the script reads those ahead in parallel while it pushes; that took a 26.3 run's Gradle start from 62 s to 20 s.

**Refresh it** when runs start downloading again: after a Minecraft, Loom, NeoForge or Gradle bump, or a new node. From a clean checkout of origin/master:

```
python .claude/skills/boat-game-test/boat_snapshot.py
```

It takes about XX min on a large VM, saves `diorama-gametest-<commit>`, and keeps the two newest such snapshots. Tell Dara the new name.

## Traps

- **boat.dev's API fails now and then**: 500s, timeouts, live output streams cut after a few minutes. The script starts the tests detached and polls for their exit code, and retries the steps that are safe to repeat. A run that still fails on an API error is worth one rerun.
- **A killed script leaves its VM running.** On Ctrl-C the script deletes its VMs; a hard kill (a background command's timeout) skips that. Step 4 catches it.
- **`--keep`** stops the VM instead of deleting it, so you can look around with `boat resume <id>`, then `boat ssh <id>`. Delete it when done: its disk stays on the account for 14 days.
- **Other mods in a run (26.x only, unobfuscated):** put the jars in a folder inside the worktree and pass `--env "JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=40 -Dfabric.addMods=/home/user/diorama/<folder>"` (this replaces sandbox.sh's own `JAVA_TOOL_OPTIONS`, so keep the RAM cap). `boat push` skips git-ignored files, so leave the folder untracked rather than ignored.
- **The account is Dara's.** If `boat` says you're signed out or the plan is gone, stop and tell Dara, then run on this PC under the Minecraft lock.
