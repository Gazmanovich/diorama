package dev.diorama.gameshim;

import java.util.Objects;

import org.apache.commons.lang3.function.FailableConsumer;
import org.apache.commons.lang3.function.FailableFunction;

import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.minecraft.server.MinecraftServer;

/** The integrated server, after Fabric API's TestServerContextImpl. */
final class ShimServer implements TestServerContext {
	private final MinecraftServer server;

	ShimServer(MinecraftServer server) {
		this.server = server;
	}

	@Override
	public void runCommand(String command) {
		ShimThreading.checkOnTestThread("runCommand");
		Objects.requireNonNull(command, "command");
		runOnServer(server -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
	}

	@Override
	public <E extends Throwable> void runOnServer(FailableConsumer<MinecraftServer, E> action) throws E {
		ShimThreading.checkOnTestThread("runOnServer");
		Objects.requireNonNull(action, "action");
		ShimThreading.onServer(server, () -> {
			action.accept(server);
			return null;
		});
	}

	@Override
	public <T, E extends Throwable> T computeOnServer(FailableFunction<MinecraftServer, T, E> function) throws E {
		ShimThreading.checkOnTestThread("computeOnServer");
		Objects.requireNonNull(function, "function");
		return ShimThreading.onServer(server, () -> function.apply(server));
	}
}
