package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import dev.skycraft.link.Proto;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Prediction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Converts Skyrim inventory entries into useful Minecraft items.
 *
 * Skyrim keeps ownership of the source inventory: the Skyrim side removes the original stack before
 * sending this event, so a corpse or container cannot be looted twice. The Minecraft item is an
 * intentionally semantic translation rather than a byte-for-byte recreation of every Skyrim item.
 */
public final class SkyLoot {
	private static final int MAX_PER_EVENT = 1024;

	private SkyLoot() {
	}

	/**
	 * Inserts one Skyrim inventory stack into the player's Minecraft inventory. The source FormID is
	 * retained in the custom display name so different Skyrim items do not become indistinguishable.
	 */
	public static void receive(ServerPlayer player, int category, int formId, int count) {
		if (player == null || count <= 0 || count > MAX_PER_EVENT) {
			return;
		}

		Item item = itemFor(category, formId);
		int remaining = count;
		int inserted = 0;
		while (remaining > 0) {
			int amount = Math.min(remaining, item.getDefaultMaxStackSize());
			ItemStack stack = new ItemStack(item, amount);
			stack.set(DataComponents.CUSTOM_NAME, Component.literal("Skyrim loot #" + String.format("%08X", formId)));
			if (!player.getInventory().add(stack)) {
				player.drop(stack, false, Prediction.PREDICTED);
			}
			remaining -= amount;
			inserted += amount;
		}

		SkyCraft.LOG.info("SkyCraft: imported {} Skyrim loot (form {:08X}, category {}) for {}", inserted, formId, category,
			player.getName().getString());
	}

	private static Item itemFor(int category, int formId) {
		// Skyrim's two universally recognizable stack IDs.
		if (formId == 0x0000000F) { // Gold
			return Items.GOLD_NUGGET;
		}
		if (formId == 0x0000000A) { // Lockpick
			return Items.TRIPWIRE_HOOK;
		}
		if (formId == 0x0001D4EC) { // Torch
			return Items.TORCH;
		}

		return switch (category) {
			case Proto.LOOT_GOLD -> Items.GOLD_NUGGET;
			case Proto.LOOT_WEAPON -> Items.IRON_SWORD;
			case Proto.LOOT_ARMOR -> Items.IRON_CHESTPLATE;
			case Proto.LOOT_AMMO -> Items.ARROW;
			case Proto.LOOT_POTION -> Items.POTION;
			case Proto.LOOT_INGREDIENT -> Items.WHEAT;
			case Proto.LOOT_BOOK -> Items.BOOK;
			case Proto.LOOT_KEY -> Items.TRIPWIRE_HOOK;
			case Proto.LOOT_SOUL_GEM -> Items.AMETHYST_SHARD;
			default -> Items.GOLD_NUGGET;
		};
	}
}
