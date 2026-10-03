package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.link.SkyLink;
import dev.skycraft.net.SkyNet;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;

/**
 * Server-side Skyrim loot UI coordinator.
 *
 * <p>The server mirrors the local Skyrim snapshot into a normal Minecraft chest screen. The
 * displayed stacks are never themselves authoritative: every extraction becomes a Skyrim
 * transaction and the next shared-memory snapshot replaces the display.</p>
 */
public final class SkyLoot {
	private SkyLoot() {
	}

	public static void init() {
		SkyrimItemComponents.init();
	}

	public static void applySnapshot(ServerPlayer player, SkyNet.LootState snapshot) {
		if (player == null || snapshot == null) {
			return;
		}
		if (snapshot.items() == null || snapshot.items().size() > Proto.LOOT_MAX_ITEMS) {
			return;
		}

		if (snapshot.phase() == Proto.LOOT_CLOSED) {
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
		SkyCraft.LOG.info("SkyCraft: opened Skyrim loot UI for {} (source {:08X}, session {}, revision {})",
			player.getName().getString(), snapshot.sourceFormId(), snapshot.sessionId(), snapshot.revision());
	}

	/**
	 * Called only by the authoritative server-side loot menu. A host writes directly into its local
	 * Skyrim mapping; a guest gets the same request forwarded to its own client, which writes its
	 * local mapping.
	 */
	public static boolean dispatchServerRequest(ServerPlayer player, SkyLootMenu menu, SkyNet.LootRequest request) {
		if (player == null || menu == null || request == null || player.containerMenu != menu || !menu.acceptsRequest(request)) {
			return false;
		}

		if (SkyNet.isHost(player)) {
			SkyLink.pushLootRequest(
				request.type(), request.requestId(), request.sessionId(), request.sourceFormId(),
				request.formId(), request.baseFormId(), request.count(), request.revision()
			);
			return true;
		}

		if (ServerPlayNetworking.canSend(player, SkyNet.LootRequest.TYPE)) {
			ServerPlayNetworking.send(player, request);
			return true;
		}
		return false;
	}

	public static void dispatchClose(ServerPlayer player, SkyLootMenu menu) {
		if (player == null || menu == null || player.containerMenu != menu) {
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
		dispatchServerRequest(player, menu, request);
	}
}
