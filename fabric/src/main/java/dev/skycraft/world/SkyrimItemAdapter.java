package dev.skycraft.world;

import dev.skycraft.link.Proto;
import dev.skycraft.net.SkyNet;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Maps a Skyrim inventory definition to a usable Minecraft ItemStack while retaining the original
 * FormID and all inventory metadata in a persistent custom data component.
 */
public final class SkyrimItemAdapter {
	private SkyrimItemAdapter() {
	}

	public static ItemStack create(SkyNet.LootItem source) {
		Item base = baseItem(source);
		int count = Math.max(1, Math.min(source.count(), base.getDefaultMaxStackSize()));
		ItemStack stack = new ItemStack(base, count);
		stack.set(SkyrimItemComponents.SKYRIM_ITEM, new SkyrimItemComponent(
			source.formId(),
			source.baseFormId(),
			source.category(),
			source.value(),
			source.weight(),
			source.damage(),
			source.armor(),
			source.enchantmentFormId(),
			source.soulLevel(),
			source.flags()
		));
		String name = source.name() == null || source.name().isBlank()
			? "Skyrim item #" + String.format("%08X", source.formId())
			: source.name();
		stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
		return stack;
	}

	public static Item baseItem(SkyNet.LootItem source) {
		int formId = source.formId();
		String name = source.name() == null ? "" : source.name().toLowerCase(java.util.Locale.ROOT);

		// Canonical Skyrim forms that have an obvious Minecraft equivalent.
		if (formId == 0x0000000F || source.category() == Proto.LOOT_GOLD) {
			return Items.GOLD_NUGGET;
		}
		if (formId == 0x0000000A || source.category() == Proto.LOOT_KEY) {
			return Items.TRIPWIRE_HOOK;
		}
		if (formId == 0x0001D4EC) {
			return Items.TORCH;
		}

		return switch (source.category()) {
			case Proto.LOOT_WEAPON -> weapon(name);
			case Proto.LOOT_ARMOR -> armor(name);
			case Proto.LOOT_AMMO -> Items.ARROW;
			case Proto.LOOT_POTION -> Items.POTION;
			case Proto.LOOT_INGREDIENT -> ingredient(name);
			case Proto.LOOT_BOOK -> Items.BOOK;
			case Proto.LOOT_SOUL_GEM -> Items.AMETHYST_SHARD;
			default -> Items.PAPER;
		};
	}

	private static Item weapon(String name) {
		if (name.contains("crossbow")) {
			return Items.CROSSBOW;
		}
		if (name.contains("bow") || name.contains("longbow") || name.contains("daedric bow")) {
			return Items.BOW;
		}
		if (name.contains("axe")) {
			return Items.IRON_AXE;
		}
		if (name.contains("mace") || name.contains("warhammer")) {
			return Items.MACE;
		}
		if (name.contains("staff")) {
			return Items.BLAZE_ROD;
		}
		return Items.IRON_SWORD;
	}

	private static Item armor(String name) {
		if (name.contains("shield")) {
			return Items.SHIELD;
		}
		if (name.contains("helmet") || name.contains("helm") || name.contains("circlet") || name.contains("hood")) {
			return Items.IRON_HELMET;
		}
		if (name.contains("boots") || name.contains("boot")) {
			return Items.IRON_BOOTS;
		}
		if (name.contains("gauntlet") || name.contains("glove") || name.contains("bracer")) {
			return Items.IRON_LEGGINGS;
		}
		return Items.IRON_CHESTPLATE;
	}

	private static Item ingredient(String name) {
		if (name.contains("apple")) {
			return Items.APPLE;
		}
		if (name.contains("garlic")) {
			return Items.POISONOUS_POTATO;
		}
		return Items.WHEAT;
	}

	/** Optional debug helper used by future combat/item systems. */
	public static SkyrimItemComponent metadata(ItemStack stack) {
		return stack.get(SkyrimItemComponents.SKYRIM_ITEM);
	}
}
