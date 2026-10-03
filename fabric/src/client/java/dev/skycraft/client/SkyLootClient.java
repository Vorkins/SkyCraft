package dev.skycraft.client;

import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;

/**
 * Mirrors local Skyrim loot snapshots to the Minecraft server.
 *
 * <p>This runs on the client because the Skyrim shared-memory mapping exists only on the machine
 * running that player's Skyrim. The server uses the snapshot solely to render the player's UI.</p>
 */
public final class SkyLootClient {
	private static int sentSession = Integer.MIN_VALUE;
	private static int sentRevision = Integer.MIN_VALUE;
	private static int sentPhase = Integer.MIN_VALUE;
	private static int sentGeneration = Integer.MIN_VALUE;

	private SkyLootClient() {
	}

	public static void init() {
		ClientTickEvents.END_CLIENT_TICK.register(SkyLootClient::tick);
	}

	private static void resetSent() {
		sentSession = Integer.MIN_VALUE;
		sentRevision = Integer.MIN_VALUE;
		sentPhase = Integer.MIN_VALUE;
	}

	private static void tick(Minecraft minecraft) {
		if (!SkyLink.active() || minecraft.player == null) {
			int generation = SkyLink.generation();
			if (generation != sentGeneration) {
				resetSent();
				sentGeneration = generation;
			}
			return;
		}
		int generation = SkyLink.generation();
		if (generation != sentGeneration) {
			resetSent();
			sentGeneration = generation;
		}
		SkyLink.LootState state = new SkyLink.LootState();
		if (!SkyLink.readLootState(state)) {
			return;
		}

		boolean changed = state.phase != sentPhase ||
			state.sessionId != sentSession ||
			state.revision != sentRevision;
		if (!changed || !ClientPlayNetworking.canSend(SkyNet.LootState.TYPE)) {
			return;
		}

		List<SkyNet.LootItem> items = new ArrayList<>(state.items.size());
		for (SkyLink.LootItem item : state.items) {
			items.add(new SkyNet.LootItem(
				item.formId(),
				item.baseFormId(),
				item.count(),
				item.flags(),
				item.category(),
				item.value(),
				item.weight(),
				item.damage(),
				item.armor(),
				item.enchantmentFormId(),
				item.soulLevel(),
				item.name()
			));
		}

		ClientPlayNetworking.send(new SkyNet.LootState(
			state.phase,
			state.sessionId,
			state.revision,
			state.worldId,
			state.sourceFormId,
			state.flags,
			state.title,
			items
		));

		sentPhase = state.phase;
		sentSession = state.sessionId;
		sentRevision = state.revision;
	}
}
