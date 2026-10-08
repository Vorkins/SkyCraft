package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.SkyLink;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.clock.WorldClocks;

/**
 * Keeps Minecraft's world clock aligned with Skyrim's GameHour.
 *
 * Skyrim owns time while SkyCraft is active. Minecraft's daylight cycle remains disabled;
 * this class only mirrors the current time of day so systems that inspect Minecraft time
 * see the same clock as Skyrim.
 */
public final class SkyTime {
	private static final SkyLink.SkyState SKY = new SkyLink.SkyState();
	private static long lastSyncSeq = -1;
	private static int lastGeneration = -1;
	private static int lastWeather = Integer.MIN_VALUE;
	private static MinecraftServer lastServer;

	private SkyTime() {
	}

	public static void init() {
		ServerTickEvents.END_SERVER_TICK.register(SkyTime::sync);
		SkyCraft.LOG.info("SkyCraft: Skyrim world clock synchronization registered");
	}

	private static void sync(MinecraftServer server) {
		if (!SkyLink.active() || !SkyLink.readSkyState(SKY) || !SKY.inGame() || SKY.loading()) {
			return;
		}
		int generation = SkyLink.generation();
		if (generation != lastGeneration || server != lastServer) {
			lastGeneration = generation;
			lastServer = server;
			lastSyncSeq = -1;
			lastWeather = Integer.MIN_VALUE;
		}
		if (SKY.seq == lastSyncSeq) {
			return;
		}
		lastSyncSeq = SKY.seq;

		float hour = SKY.gameHour;
		if (!Float.isFinite(hour)) {
			return;
		}
		hour %= 24.0F;
		if (hour < 0.0F) {
			hour += 24.0F;
		}

		// Minecraft 0 = sunrise, while Skyrim GameHour 6 = sunrise.
		// Therefore: Skyrim 0:00 -> MC 18:00, Skyrim 6:00 -> MC 0:00.
		long timeOfDay = Math.round((hour / 24.0F) * 24000.0F + 18000.0F) % 24000L;
		syncClock(server, timeOfDay);
		syncWeather(server, SKY.flags);
	}

	private static void syncWeather(MinecraftServer server, int flags) {
		int weather = flags & (dev.skycraft.link.Proto.SKY_RAINING | dev.skycraft.link.Proto.SKY_SNOWING);
		if (weather == lastWeather) {
			return;
		}
		lastWeather = weather;
		boolean raining = weather != 0;
		// Skyrim does not expose a universal thunder boolean through SkyState yet. Preserve the
		// important part (clear vs precipitation) without inventing thunderstorms.
		server.setWeatherParameters(raining ? 0 : 1200, raining ? 1200 : 0, raining, false);
		SkyCraft.LOG.info("SkyCraft: Skyrim weather -> Minecraft {}", raining
			? ((weather & dev.skycraft.link.Proto.SKY_SNOWING) != 0 ? "snow/precipitation" : "rain")
			: "clear");
	}

	private static void syncClock(MinecraftServer server, long timeOfDay) {
		long current = server.overworld().getOverworldClockTime();
		long day = Math.floorDiv(current, 24000L);

		// Keep the existing day counter whenever possible, avoiding large jumps for
		// mechanics that use absolute game time. Choose the nearest equivalent cycle.
		long target = day * 24000L + timeOfDay;
		long delta = target - current;
		if (delta > 12000L) {
			target -= 24000L;
		} else if (delta < -12000L) {
			target += 24000L;
		}

		if (target != current) {
			var clock = server.registryAccess().lookupOrThrow(Registries.WORLD_CLOCK).getOrThrow(WorldClocks.OVERWORLD);
			server.clockManager().setTotalTicks(clock, target);
		}
	}
}
