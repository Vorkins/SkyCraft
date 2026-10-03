package dev.skycraft.world;

import dev.skycraft.SkyCraft;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.data.DataComponentType;
import net.minecraft.resources.Identifier;

/** Registry entry for persistent Skyrim item provenance and combat metadata. */
public final class SkyrimItemComponents {
	public static final DataComponentType<SkyrimItemComponent> SKYRIM_ITEM = Registry.register(
		BuiltInRegistries.DATA_COMPONENT_TYPE,
		Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "skyrim_item"),
		DataComponentType.<SkyrimItemComponent>builder()
			.persistent(SkyrimItemComponent.CODEC)
			.networkSynchronized(SkyrimItemComponent.STREAM_CODEC)
			.build()
	);

	private SkyrimItemComponents() {
	}

	public static void init() {
		// Forces static initialization during common mod initialization.
	}
}
