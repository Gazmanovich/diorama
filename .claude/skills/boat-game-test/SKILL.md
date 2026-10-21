---
name: boat-game-test
description: Run Diorama's client game tests on a boat.dev VM instead of this PC. Use when a functional game test run (does it pass, do the screenshots show the right thing) has to wait for the local Minecraft lock, or when several versions need testing at once.
---

# Game tests on boat.dev

A boat.dev VM has no GPU: Minecraft renders with OpenGL on the CPU, through Mesa. Tests pass the same way and screenshots show the same scenes, but slower than on Dara's PC: the full 26.3 suite took 68 min there against 24 min here. One section takes 5–10 min on 26.3 (`screens` 4–8, `tour` 7), depending on how busy the host is; the three multiplayer ones together about an hour.

That makes it the right place for functional checks and the wrong place for anything about the GPU:

- **boat.dev**: a feature works, a screenshot shows what it should, a fix holds on another version.
- **This PC, under the Minecraft lock**: fps, frame time and bake-speed measurements, GPU bake (`DIORAMA_GPU_BAKE`), pixel-for-pixel comparisons against an RTX image, and anything driven on the desktop.

## Run

1. Pick the test sections your change touches. They're the names in `selectedTests()` in `src/gametest/java/dev/diorama/test/DioramaClientTest.java` (`tour`, `waypoints`, `multiplayer`, ...). Run a few sections, not `all`: the trial's compute hours are limited (`boat limits` shows what's left).
2. Run from your checkout. It pushes your working tree, uncommitted changes included:

   ```
   python .claude/skills/boat-game-test/boat_run.py <node> --tests <section,section>
   ```

   `<node>` is a folder in `versions/`: `26.3`, `1.21.11`, `26.3-neoforge`, ... Pass game environment variables with `--env DIORAMA_GIF=1`. The script waits for the run; give the command a timeout longer than the run, up to 2 hours.
3. Open the screenshots it lists (`build/boat-runs/<time>-<node>/screenshots/`) and look at them. A pass with the wrong picture is still a failure. On a failure the script prints the assertion; `gradle.log` and `latest.log` in the same folder have the rest.

Done when the run exits 0 and you've seen the screenshots for the sections you changed.

## Limits and traps

- **Two VMs at a time** on the trial, Default size (4 vCPU, 8 GB). When both are busy the script waits up to 20 min for one.
- **Starting point**: VMs start from the named snapshot `diorama-gametest`, which has 26.3 built. Other nodes download their Minecraft and mappings on every run, about 5 min more.
- **Refresh the snapshot** when 26.3 runs start by downloading dependencies too (a Gradle, Loom or Minecraft bump): run once with `--keep`, then `boat resume <id>`, `boat snapshot <id> diorama-gametest`, `boat delete <id> --yes`.
- **Leftover VMs**: the script deletes its VM even when the tests fail, but not when the script itself is killed. Then `boat list` shows it; `boat delete <id> --yes`. VMs stop on their own after 2 hours.
- **`--keep`** stops the VM instead of deleting it, so you can look around with `boat ssh <id>`. Delete it when done, or its disk stays on the account for 14 days.
- **The account is Dara's.** If `boat` says you're signed out or have no plan, stop and tell Dara, then run on this PC under the Minecraft lock.
