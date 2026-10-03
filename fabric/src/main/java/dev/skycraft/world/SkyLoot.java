package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side Skyrim loot UI coordinator.
 *
 * <p>Skyrim is authoritative for the source inventory. Minecraft displays a snapshot and sends
 * transaction requests back to that player's local Skyrim. A request only creates Minecraft items
 * after a later Skyrim snapshot proves that the requested amount actually disappeared.</p>
 */
public final class SkyLoot {
	private record PendingLoot(SkyNet.LootRequest request, List<SkyNet.LootItem> before) {
	}

	private static final Map<UUID, List<SkyNet.LootItem>> LAST_SNAPSHOTS = new HashMap<>();
	private static final Map<UUID, PendingLoot> PENDING = new HashMap<>();
	private static final Map<UUID, Integer> LOCALLY_CLOSED_SESSION = new HashMap<>();

	private SkyLoot() {
	}

	public static void init() {
		SkyrimItemComponents.init();
	}

	public static void applySnapshot(ServerPlayer player, SkyNet.LootState snapshot) {
		if (player == null || snapshot == null || snapshot.items() == null || snapshot.items().size() > Proto.LOOT_MAX_ITEMS) {
			return;
		}

		UUID playerId = player.getUUID();
		List<SkyNet.LootItem> current = List.copyOf(snapshot.items());

		// Confirm a previously sent request only when Skyrim advanced the session revision.
		PendingLoot pending = PENDING.get(playerId);
		if (pending != null &&
			snapshot.sessionId() == pending.request().sessionId() &&
			snapshot.sourceFormId() == pending.request().sourceFormId() &&
			snapshot.revision() > pending.request().revision()) {
			confirmTransfer(player, pending, current);
			PENDING.remove(playerId);
		}

		LAST_SNAPSHOTS.put(playerId, current);

		if (snapshot.phase() == Proto.LOOT_CLOSED) {
			LOCALLY_CLOSED_SESSION.remove(playerId);
			if (player.containerMenu instanceof SkyLootMenu menu &&
				menu.sessionId() == snapshot.sessionId() &&
				menu.sourceFormId() == snapshot.sourceFormId()) {
				menu.closeFromSkyrim();
			}
			return;
		}

		if (snapshot.phase() != Proto.LOOT_OPEN || snapshot.sessionId() == 0 || snapshot.sourceFormId() == 0) {
			return;
		}

		// The player already closed this Minecraft screen. Skyrim may still publish one last OPEN
		// snapshot while the close request is moving through the IPC ring; do not resurrect the UI.
		if (snapshot.sessionId() == LOCALLY_CLOSED_SESSION.getOrDefault(playerId, Integer.MIN_VALUE)) {
			return;
		}

		if (player.containerMenu instanceof SkyLootMenu menu) {
			if (!menu.matches(snapshot)) {
				menu.closeFromSkyrim();
			} else if (snapshot.revision() >= menu.revision()) {
				menu.applySnapshot(snapshot, true);
				return;
			} else {
				return;
			}
		}

		open(player, snapshot);
	}

	private static void confirmTransfer(ServerPlayer player, PendingLoot pending, List<SkyNet.LootItem> after) {
		if (pending.request().type() == Proto.LOOT_CLOSE) {
			return;
		}

		if (pending.request().type() == Proto.LOOT_TAKE) {
			SkyNet.LootItem template = firstByForm(pending.before(), pending.request().formId());
			if (template == null) {
				return;
			}
			int before = countByForm(pending.before(), pending.request().formId());
			int now = countByForm(after, pending.request().formId());
			give(player, template, Math.max(0, before - now));
			return;
		}

		if (pending.request().type() == Proto.LOOT_TAKE_ALL) {
			Map<Integer, SkyNet.LootItem> templates = new HashMap<>();
			for (SkyNet.LootItem item : pending.before()) {
				templates.putIfAbsent(item.formId(), item);
			}
			for (Map.Entry<Integer, SkyNet.LootItem> entry : templates.entrySet()) {
				int before = countByForm(pending.before(), entry.getKey());
				int now = countByForm(after, entry.getKey());
				give(player, entry.getValue(), Math.max(0, before - now));
			}
		}
	}

	private static SkyNet.LootItem firstByForm(List<SkyNet.LootItem> items, int formId) {
		for (SkyNet.LootItem item : items) {
			if (item.formId() == formId) {
				return item;
			}
		}
		return null;
	}

	private static int countByForm(List<SkyNet.LootItem> items, int formId) {
		int total = 0;
		for (SkyNet.LootItem item : items) {
			if (item.formId() == formId) {
				total = Math.min(Integer.MAX_VALUE - total, total + Math.max(0, item.count()));
			}
		}
		return total;
	}

	private static void give(ServerPlayer player, SkyNet.LootItem source, int count) {
		if (count <= 0) {
			return;
		}

		ItemStack prototype = SkyrimItemAdapter.create(source);
		if (prototype.isEmpty()) {
			return;
		}

		int remaining = count;
		while (remaining > 0) {
			int amount = Math.min(remaining, prototype.getMaxStackSize());
			ItemStack stack = prototype.copy();
			stack.setCount(amount);
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false, Prediction.PREDICTED);
			}
			remaining -= amount;
		}

		SkyCraft.LOG.info(
			"SkyCraft: confirmed {} Skyrim item(s) form {:08X} for {}",
			count, source.formId(), player.getName().getString()
		);
	}

	private static void open(ServerPlayer player, SkyNet.LootState snapshot) {
		var container = new SimpleContainer(Proto.LOOT_MAX_ITEMS);
		var provider = new net.minecraft.world.MenuProvider() {
			@Override
			public Component getDisplayName() {
				String title = snapshot.title() == null || snapshot.title().isBlank()
					? "Skyrim Loot"
					: snapshot.title();
				return Component.literal(title);
			}

			@Override
			public net.minecraft.world.inventory.AbstractContainerMenu createMenu(
				int containerId,
				net.minecraft.world.entity.player.Inventory inventory,
				net.minecraft.world.entity.player.Player playerEntity
			) {
				return new SkyLootMenu(containerId, inventory, container, snapshot);
			}
		};
		player.openMenu(provider);
		SkyCraft.LOG.info(
			"SkyCraft: opened Skyrim loot UI for {} (source {:08X}, session {}, revision {})",
			player.getName().getString(), snapshot.sourceFormId(), snapshot.sessionId(), snapshot.revision()
		);
	}

	/**
	 * Sends a validated loot transaction to the player's local Skyrim and records a pending
	 * confirmation. Only one extraction request may be in flight per player.
	 */
	public static boolean dispatchServerRequest(ServerPlayer player, SkyLootMenu menu, SkyNet.LootRequest request) {
		if (player == null || menu == null || request == null || !menu.acceptsRequest(request)) {
			return false;
		}
		if (request.type() != Proto.LOOT_CLOSE && PENDING.containsKey(player.getUUID())) {
			return false;
		}

		if (request.type() != Proto.LOOT_CLOSE && player.containerMenu != menu) {
			return false;
		}

		if (request.type() != Proto.LOOT_CLOSE && !LAST_SNAPSHOTS.containsKey(player.getUUID())) {
			return false;
		}

		boolean sent;
		if (SkyNet.isHost(player)) {
			sent = SkyLink.pushLootRequest(
				request.type(), request.requestId(), request.sessionId(), request.sourceFormId(),
				request.formId(), request.baseFormId(), request.count(), request.revision()
			);
		} else {
			sent = ServerPlayNetworking.canSend(player, SkyNet.LootRequest.TYPE);
			if (sent) {
				ServerPlayNetworking.send(player, request);
			}
		}

		if (!sent) {
			return false;
		}

		if (request.type() != Proto.LOOT_CLOSE) {
			List<SkyNet.LootItem> before = LAST_SNAPSHOTS.getOrDefault(player.getUUID(), List.of());
			PENDING.put(player.getUUID(), new PendingLoot(request, List.copyOf(before)));
		}

		return true;
	}

	public static void dispatchClose(ServerPlayer player, SkyLootMenu menu) {
		if (player == null || menu == null) {
			return;
		}

		var request = new SkyNet.LootRequest(
			Proto.LOOT_CLOSE,
			menu.nextRequestId(),
			menu.sessionId(),
			menu.sourceFormId(),
			0,
			0,
			0,
			menu.revision()
		);
		if (dispatchServerRequest(player, menu, request)) {
			LOCALLY_CLOSED_SESSION.put(player.getUUID(), menu.sessionId());
		}
	}
}
