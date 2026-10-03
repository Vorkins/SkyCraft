package dev.skycraft.net;

import dev.skycraft.SkyCraft;
import dev.skycraft.combat.SkyCombat;
import dev.skycraft.world.SkyDig;
import dev.skycraft.world.SkyLoot;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ClientPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * Multiplayer bridge between each player's local Skyrim and the Minecraft server.
 *
 * <p>The server never trusts a loot snapshot as an authority over the actual inventory. It only
 * mirrors the snapshot for UI. Loot transactions are forwarded back to the player's Skyrim,
 * which remains authoritative and confirms the result through the next snapshot.</p>
 */
public final class SkyNet {
	private SkyNet() {
	}

	public record Hurt(int kind, float skyrimDamage, int attackerFormId, int flags) implements CustomPacketPayload {
		public static final Type<Hurt> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "hurt"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Hurt> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, Hurt::kind,
			ByteBufCodecs.FLOAT, Hurt::skyrimDamage,
			ByteBufCodecs.INT, Hurt::attackerFormId,
			ByteBufCodecs.VAR_INT, Hurt::flags,
			Hurt::new
		);
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record Died(int attackerFormId) implements CustomPacketPayload {
		public static final Type<Died> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "died"));
		public static final StreamCodec<RegistryFriendlyByteBuf, Died> CODEC =
			StreamCodec.composite(ByteBufCodecs.INT, Died::attackerFormId, Died::new);
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Guest/host client -> server: the local Skyrim loot snapshot shown in the GUI. */
	public record LootItem(
		int formId,
		int baseFormId,
		int count,
		int flags,
		int category,
		int value,
		float weight,
		float damage,
		float armor,
		int enchantmentFormId,
		int soulLevel,
		String name
	) {
		public static final StreamCodec<RegistryFriendlyByteBuf, LootItem> CODEC = new StreamCodec<>() {
			@Override
			public LootItem decode(RegistryFriendlyByteBuf b) {
				return new LootItem(
					b.readInt(), b.readInt(), b.readVarInt(), b.readInt(), b.readVarInt(), b.readInt(),
					b.readFloat(), b.readFloat(), b.readFloat(), b.readInt(), b.readVarInt(), b.readUtf(Proto.LOOT_ITEM_NAME_BYTES)
				);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf b, LootItem v) {
				b.writeInt(v.formId());
				b.writeInt(v.baseFormId());
				b.writeVarInt(v.count());
				b.writeInt(v.flags());
				b.writeVarInt(v.category());
				b.writeInt(v.value());
				b.writeFloat(v.weight());
				b.writeFloat(v.damage());
				b.writeFloat(v.armor());
				b.writeInt(v.enchantmentFormId());
				b.writeVarInt(v.soulLevel());
				b.writeUtf(v.name() == null ? "" : v.name(), Proto.LOOT_ITEM_NAME_BYTES);
			}
		};
	}

	/** Local Skyrim loot state mirrored to the server. The server uses it only for the UI. */
	public record LootState(
		int phase,
		int sessionId,
		int revision,
		int worldId,
		int sourceFormId,
		int flags,
		String title,
		List<LootItem> items
	) implements CustomPacketPayload {
		public static final Type<LootState> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "loot_state"));

		private static final StreamCodec<RegistryFriendlyByteBuf, List<LootItem>> ITEMS =
			LootItem.CODEC.apply(ByteBufCodecs.list(Proto.LOOT_MAX_ITEMS));

		public static final StreamCodec<RegistryFriendlyByteBuf, LootState> CODEC = new StreamCodec<>() {
			@Override
			public LootState decode(RegistryFriendlyByteBuf b) {
				return new LootState(
					b.readVarInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(), b.readInt(),
					b.readUtf(Proto.LOOT_TITLE_BYTES), ITEMS.decode(b)
				);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf b, LootState v) {
				b.writeVarInt(v.phase());
				b.writeInt(v.sessionId());
				b.writeInt(v.revision());
				b.writeInt(v.worldId());
				b.writeInt(v.sourceFormId());
				b.writeInt(v.flags());
				b.writeUtf(v.title() == null ? "" : v.title(), Proto.LOOT_TITLE_BYTES);
				ITEMS.encode(b, v.items() == null ? List.of() : v.items());
			}
		};

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/**
	 * Server -> guest: forward a validated click from the Minecraft loot menu into that guest's
	 * local Skyrim. On the host the server writes directly to shared memory instead.
	 */
	public record LootRequest(
		int type,
		int requestId,
		int sessionId,
		int sourceFormId,
		int formId,
		int baseFormId,
		int count,
		int revision
	) implements CustomPacketPayload {
		public static final Type<LootRequest> TYPE =
			new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "loot_request"));

		public static final StreamCodec<RegistryFriendlyByteBuf, LootRequest> CODEC = new StreamCodec<>() {
			@Override
			public LootRequest decode(RegistryFriendlyByteBuf b) {
				return new LootRequest(
					b.readVarInt(), b.readInt(), b.readInt(), b.readInt(),
					b.readInt(), b.readInt(), b.readVarInt(), b.readInt()
				);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf b, LootRequest v) {
				b.writeVarInt(v.type());
				b.writeInt(v.requestId());
				b.writeInt(v.sessionId());
				b.writeInt(v.sourceFormId());
				b.writeInt(v.formId());
				b.writeInt(v.baseFormId());
				b.writeVarInt(v.count());
				b.writeInt(v.revision());
			}
		};

		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record DigOpen(int world, BlockPos pos, int material) implements CustomPacketPayload {
		public static final Type<DigOpen> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_open"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigOpen> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigOpen::world,
			BlockPos.STREAM_CODEC, DigOpen::pos,
			ByteBufCodecs.VAR_INT, DigOpen::material,
			DigOpen::new
		);
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public record DigReveal(int world, List<BlockPos> cells, List<Integer> materials) implements CustomPacketPayload {
		public static final Type<DigReveal> TYPE = new Type<>(Identifier.fromNamespaceAndPath(SkyCraft.MOD_ID, "dig_reveal"));
		public static final StreamCodec<RegistryFriendlyByteBuf, DigReveal> CODEC = StreamCodec.composite(
			ByteBufCodecs.INT, DigReveal::world,
			BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list(64)), DigReveal::cells,
			ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(64)), DigReveal::materials,
			DigReveal::new
		);
		@Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public static void init() {
		PayloadTypeRegistry.serverboundPlay().register(Hurt.TYPE, Hurt.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigOpen.TYPE, DigOpen.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(DigReveal.TYPE, DigReveal.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(LootState.TYPE, LootState.CODEC);

		PayloadTypeRegistry.clientboundPlay().register(Died.TYPE, Died.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(LootRequest.TYPE, LootRequest.CODEC);

		ServerPlayNetworking.registerGlobalReceiver(LootState.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> SkyLoot.applySnapshot(player, payload));
		});

		ServerPlayNetworking.registerGlobalReceiver(DigOpen.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			context.server().execute(() -> SkyDig.open(player, payload.world(), payload.pos(), payload.material()));
		});
		ServerPlayNetworking.registerGlobalReceiver(DigReveal.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			int[] materials = payload.materials().stream().mapToInt(Integer::intValue).toArray();
			context.server().execute(() -> SkyDig.reveal(player, payload.world(), payload.cells(), materials));
		});

		ServerPlayNetworking.registerGlobalReceiver(Hurt.TYPE, (payload, context) -> {
			ServerPlayer player = context.player();
			float damage = Math.max(0.0F, Math.min(payload.skyrimDamage(), 10000.0F));
			context.server().execute(() -> SkyCombat.hurtPlayer(player, payload.kind(), damage, payload.attackerFormId(), payload.flags()));
		});

		// A guest's client receives its own validated Skyrim transaction request and writes it into
		// its local shared-memory mapping.
		ClientPlayNetworking.registerGlobalReceiver(LootRequest.TYPE, (payload, context) -> {
			context.client().execute(() -> dev.skycraft.link.SkyLink.pushLootRequest(
				payload.type(), payload.requestId(), payload.sessionId(), payload.sourceFormId(),
				payload.formId(), payload.baseFormId(), payload.count(), payload.revision()
			));
		});
	}

	/** True if this player plays on this machine (their Skyrim owns the local shared memory). */
	public static boolean isHost(ServerPlayer player) {
		var server = player.level().getServer();
		return server != null && server.isSingleplayerOwner(player.nameAndId());
	}
}
