package dev.diorama.gameshim;

import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.minecraft.server.MinecraftServer;

/**
 * Hands work between the test thread and the game's threads. Client tasks run at the start of
 * every frame, before the game's own tasks, including the frames the game draws while it blocks
 * (loading a world, leaving one), so a task may itself block on those. The game keeps running
 * between tasks: there is no lockstep.
 */
public final class ShimThreading {
	/** Longest a single task may take, world loading included, before the test gives up. */
	private static final long TASK_TIMEOUT_MINUTES = 10;
	/** Longest the client may go without ticking or drawing before a wait gives up. */
	private static final long STALL_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(5);

	private static final Queue<FutureTask<?>> CLIENT_TASKS = new ConcurrentLinkedQueue<>();
	private static final Object CLOCK = new Object();
	private static long ticks;
	private static long frames;
	static volatile Thread testThread;

	private ShimThreading() {
	}

	/** Render thread, at the start of every frame. */
	public static void runClientTasks() {
		FutureTask<?> task;
		while ((task = CLIENT_TASKS.poll()) != null) {
			task.run();
		}
	}

	/** Render thread, after every client tick. */
	public static void onTickEnd() {
		synchronized (CLOCK) {
			ticks++;
			CLOCK.notifyAll();
		}
	}

	/** Render thread, after every frame has been drawn into the main render target and shown. */
	public static void onFrameEnd() {
		synchronized (CLOCK) {
			frames++;
			CLOCK.notifyAll();
		}
	}

	static void checkOnTestThread(String method) {
		if (Thread.currentThread() != testThread) {
			throw new IllegalStateException(method + " can only be called from the client game test thread");
		}
	}

	/** Waits until the client has finished {@code count} more ticks. */
	static void waitTicks(int count) {
		synchronized (CLOCK) {
			long target = ticks + count;
			awaitClock(() -> ticks >= target, "ticking");
		}
	}

	/** Waits until the client has drawn {@code count} more frames. */
	static void waitFrames(int count) {
		synchronized (CLOCK) {
			long target = frames + count;
			awaitClock(() -> frames >= target, "drawing frames");
		}
	}

	private static void awaitClock(java.util.function.BooleanSupplier done, String what) {
		long lastTicks = ticks;
		long lastFrames = frames;
		long lastProgress = System.currentTimeMillis();
		while (!done.getAsBoolean()) {
			try {
				CLOCK.wait(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new AssertionError("Interrupted while waiting for the client", e);
			}
			long now = System.currentTimeMillis();
			if (ticks != lastTicks || frames != lastFrames) {
				lastTicks = ticks;
				lastFrames = frames;
				lastProgress = now;
			} else if (now - lastProgress > STALL_TIMEOUT_MILLIS) {
				throw new AssertionError("The client stopped " + what + " for " + STALL_TIMEOUT_MILLIS / 1000 + " seconds");
			}
		}
	}

	/** Work for another thread, which may throw anything. */
	interface Task<T> {
		T run() throws Throwable;
	}

	/** Runs the task on the render thread and waits for it, rethrowing whatever it threw. */
	static <T> T onClient(Task<T> work) {
		FutureTask<T> task = new FutureTask<>(callable(work));
		CLIENT_TASKS.add(task);
		return await(task, "client");
	}

	/** Queues a task for the render thread without waiting for it. */
	static void onClientLater(Runnable runnable) {
		CLIENT_TASKS.add(new FutureTask<>(runnable, null));
	}

	/** Runs the task on the server thread and waits for it, rethrowing whatever it threw. */
	static <T> T onServer(MinecraftServer server, Task<T> work) {
		// The server logs and swallows exceptions from its tasks, and dies on errors; the
		// FutureTask catches both so they come back to the test thread instead.
		FutureTask<T> task = new FutureTask<>(callable(work));
		server.execute(task);
		return await(task, "server");
	}

	private static <T> Callable<T> callable(Task<T> work) {
		return () -> {
			try {
				return work.run();
			} catch (Throwable throwable) {
				throw ShimThreading.<RuntimeException>sneakyThrow(throwable);
			}
		};
	}

	private static <T> T await(FutureTask<T> task, String side) {
		try {
			return task.get(TASK_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		} catch (ExecutionException e) {
			throw sneakyThrow(e.getCause());
		} catch (TimeoutException e) {
			throw new AssertionError("The " + side + " didn't finish a task within " + TASK_TIMEOUT_MINUTES + " minutes");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError("Interrupted while waiting for the " + side, e);
		}
	}

	@SuppressWarnings("unchecked")
	static <E extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws E {
		throw (E) throwable;
	}
}
