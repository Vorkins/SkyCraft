package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import dev.skycraft.net.SkyNet;
import java.util.Optional;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Server-side controller for the vanilla 9x6 chest screen used as Skyrim's loot UI.
 *
 * <p>The client receives the standard Minecraft chest menu because this menu advertises
 * GENERIC_9x6. The server instance is a different class so it can turn loot clicks into
 * authoritative Skyrim requests instead of allowing the client to extract the display stacks.</p>
 */
public final class SkyLootMenu extends AbstractContainerMenu {
	public static final int LOOT_SLOTS = Proto.LOOT_MAX_ITEMS;
	private final SimpleContainer loot;
	private int sessionId;
	private int sourceFormId;
	private int revision;
	private boolean suppressCloseRequest;
	private int nextRequestId;
	private final ServerPlayer owner;

	public SkyLootMenu(int containerId, Inventory playerInventory, SimpleContainer loot, SkyNet.LootState snapshot) {
		super(MenuType.GENERIC_9x6, containerId);
		this.loot = loot;
		this.sessionId = snapshot.sessionId();
		this.sourceFormId = snapshot.sourceFormId();
		this.revision = snapshot.revision();
		this.owner = playerInventory.player instanceof ServerPlayer sp ? sp : null;

		for (int row = 0; row < 6; row++) {
			for (int col = 0; col < 9; col++) {
				int slot = row * 9 + col;
				this.addSlot(new SkyrimLootSlot(loot, slot, 8 + col * 18, 18 + row * 18));
			}
		}
		this.addStandardInventorySlots(playerInventory, 8, 140);
		applySnapshot(snapshot, false);
	}

	public int sessionId() {
		return sessionId;
	}

	public int sourceFormId() {
		return sourceFormId;
	}

	public int revision() {
		return revision;
	}

	public boolean matches(SkyNet.LootState snapshot) {
		return snapshot.phase() == Proto.LOOT_OPEN &&
			snapshot.sessionId() == sessionId &&
			snapshot.sourceFormId() == sourceFormId;
	}

	public int nextRequestId() {
		++nextRequestId;
		if (nextRequestId == 0) {
			nextRequestId = 1;
		}
		return nextRequestId;
	}

	public boolean acceptsRequest(SkyNet.LootRequest request) {
		return request.sessionId() == sessionId
			&& request.sourceFormId() == sourceFormId
			&& request.revision() == revision;
	}

	public void applySnapshot(SkyNet.LootState snapshot, boolean broadcast) {
		if (snapshot.phase() != Proto.LOOT_OPEN) {
			return;
		}
		this.sessionId = snapshot.sessionId();
		this.sourceFormId = snapshot.sourceFormId();
		this.revision = snapshot.revision();

		for (int i = 0; i < LOOT_SLOTS; i++) {
			if (i < snapshot.items().size()) {
				loot.setItem(i, SkyrimItemAdapter.create(snapshot.items().get(i)));
			} else {
				loot.setItem(i, ItemStack.EMPTY);
			}
		}
		if (broadcast) {
			this.broadcastChanges();
		}
	}

	public void closeFromSkyrim() {
		if (owner == null) {
			return;
		}
		this.suppressCloseRequest = true;
		owner.closeContainer();
		this.suppressCloseRequest = false;
	}

	public boolean takeFromSlot(Player player, int slotId, int requestedCount, boolean takeAll) {
		if (slotId < 0 || slotId >= LOOT_SLOTS) {
			return false;
		}
		ItemStack stack = this.slots.get(slotId).getItem();
		if (stack.isEmpty()) {
			return false;
		}
		SkyrimItemComponent data = stack.get(SkyrimItemComponents.SKYRIM_ITEM);
		if (data == null) {
			return false;
		}
		int count = takeAll ? stack.getCount() : Math.max(1, Math.min(requestedCount, stack.getCount()));
		int requestId = nextRequestId();
		SkyNet.LootRequest request;
		if (takeAll) {
			request = new SkyNet.LootRequest(
				Proto.LOOT_TAKE_ALL, requestId, sessionId, sourceFormId,
				data.formId(), data.baseFormId(), count, revision
			);
		} else {
			request = new SkyNet.LootRequest(
				Proto.LOOT_TAKE, requestId, sessionId, sourceFormId,
				data.formId(), data.baseFormId(), count, revision
			);
		}
		return SkyLoot.dispatchServerRequest((net.minecraft.server.level.ServerPlayer) player, this, request);
	}

	@Override
	public void clicked(int slotId, int button, ClickType clickType, Player player) {
		if (slotId >= 0 && slotId < LOOT_SLOTS) {
			if (clickType == ClickType.PICKUP) {
				takeFromSlot(player, slotId, button == 1 ? 1 : Integer.MAX_VALUE, false);
				return;
			}
			if (clickType == ClickType.QUICK_MOVE) {
				takeFromSlot(player, slotId, Integer.MAX_VALUE, false);
				return;
			}
			if (clickType == ClickType.PICKUP_ALL) {
				takeFromSlot(player, slotId, Integer.MAX_VALUE, true);
				return;
			}
			// Dragging, dropping, swapping and number-key operations cannot directly extract the
			// display item. Use left click, right click, shift-click, or double-click.
			return;
		}
		super.clicked(slotId, button, clickType, player);
	}

	@Override
	public ItemStack quickMoveStack(Player player, int slotIndex) {
		// Loot slots are action targets, not an actual mutable Container.
		if (slotIndex >= 0 && slotIndex < LOOT_SLOTS) {
			takeFromSlot(player, slotIndex, Integer.MAX_VALUE, false);
		}
		return ItemStack.EMPTY;
	}

	@Override
	public boolean stillValid(Player player) {
		return player.isAlive();
	}

	@Override
	public boolean canTakeItemForPickAll(ItemStack carried, Slot target) {
		int index = this.slots.indexOf(target);
		return index >= LOOT_SLOTS && super.canTakeItemForPickAll(carried, target);
	}

	@Override
	public void removed(Player player) {
		super.removed(player);
		if (!suppressCloseRequest && player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
			SkyLoot.dispatchClose(serverPlayer, this);
		}
	}

	private static final class SkyrimLootSlot extends Slot {
		private SkyrimLootSlot(Container container, int index, int x, int y) {
			super(container, index, x, y);
		}

		@Override
		public boolean mayPlace(ItemStack stack) {
			return false;
		}

		@Override
		public boolean mayPickup(Player player) {
			return false;
		}
	}

	/** Small callback used only to close the actual server player container safely. */
}
