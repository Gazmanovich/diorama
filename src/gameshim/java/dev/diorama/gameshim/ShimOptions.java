package dev.diorama.gameshim;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;

import net.minecraft.client.CloudStatus;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.tutorial.TutorialSteps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.sounds.SoundSource;

/**
 * The options Fabric API's client game tests start from (ClientGameTestContextImpl.initGameOptions),
 * plus two the shim needs because it runs at real speed instead of in lockstep: the game must not
 * pause when its window loses focus, nor (from 1.21.2) drop to 30 or 10 fps after a minute
 * without input.
 */
final class ShimOptions {
	private ShimOptions() {
	}

	static void apply(Options options) {
		options.tutorialStep = TutorialSteps.NONE;
		options.cloudStatus().set(CloudStatus.OFF);
		// The accessibility onboarding screen would show instead of the title screen.
		options.onboardAccessibility = false;
		// Chunks render sooner.
		options.renderDistance().set(5);
		options.getSoundSourceOptionInstance(SoundSource.MUSIC).set(0.0);
		options.pauseOnLostFocus = false;
		set(options, "options.inactivityFpsLimit", "minimized");
	}

	/**
	 * Sets an option that only some versions have, found by its caption's translation key, from
	 * its options.txt value. Mapping-independent: no names, only types and the option's codec.
	 */
	private static void set(Options options, String captionKey, String value) {
		try {
			Field caption = null;
			for (Field field : OptionInstance.class.getDeclaredFields()) {
				if (field.getType() == Component.class && !Modifier.isStatic(field.getModifiers())) {
					caption = field;
					caption.setAccessible(true);
				}
			}
			if (caption == null) {
				return;
			}
			for (Field field : Options.class.getDeclaredFields()) {
				if (field.getType() != OptionInstance.class || Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				field.setAccessible(true);
				OptionInstance<?> option = (OptionInstance<?>) field.get(options);
				if (option != null && caption.get(option) instanceof Component text
					&& text.getContents() instanceof TranslatableContents key && captionKey.equals(key.getKey())) {
					parseInto(option, value);
					return;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException e) {
			GameShim.LOGGER.warn("Couldn't set option {}", captionKey, e);
		}
	}

	private static <T> void parseInto(OptionInstance<T> option, String value) {
		// 1.20.1's DataFixerUpper has no ifSuccess/ifError; the result and the error are Optionals.
		//? if <1.21 {
		/*var parsed = option.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(value));
		parsed.result().ifPresent(option::set);
		parsed.error().ifPresent(error -> GameShim.LOGGER.warn("Couldn't set option {} to {}: {}", option, value, error.message()));
		*///?} else {
		option.codec().parse(JsonOps.INSTANCE, new JsonPrimitive(value))
			.ifSuccess(option::set)
			.ifError(error -> GameShim.LOGGER.warn("Couldn't set option {} to {}: {}", option, value, error.message()));
		//?}
	}
}
