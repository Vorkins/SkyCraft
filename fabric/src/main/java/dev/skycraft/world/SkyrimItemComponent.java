package dev.skycraft.world;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;

/** Persistent metadata carried by Minecraft ItemStacks imported from Skyrim. */
public record SkyrimItemComponent(
	int formId,
	int baseFormId,
	int category,
	int value,
	float weight,
	float damage,
	float armor,
	int enchantmentFormId,
	int soulLevel,
	int flags
) {
	public static final Codec<SkyrimItemComponent> CODEC = RecordCodecBuilder.create(instance -> instance.group(
		Codec.INT.fieldOf("form_id").forGetter(SkyrimItemComponent::formId),
		Codec.INT.fieldOf("base_form_id").forGetter(SkyrimItemComponent::baseFormId),
		Codec.INT.fieldOf("category").forGetter(SkyrimItemComponent::category),
		Codec.INT.fieldOf("value").forGetter(SkyrimItemComponent::value),
		Codec.FLOAT.fieldOf("weight").forGetter(SkyrimItemComponent::weight),
		Codec.FLOAT.fieldOf("damage").forGetter(SkyrimItemComponent::damage),
		Codec.FLOAT.fieldOf("armor").forGetter(SkyrimItemComponent::armor),
		Codec.INT.fieldOf("enchantment_form_id").forGetter(SkyrimItemComponent::enchantmentFormId),
		Codec.INT.fieldOf("soul_level").forGetter(SkyrimItemComponent::soulLevel),
		Codec.INT.fieldOf("flags").forGetter(SkyrimItemComponent::flags)
	).apply(instance, SkyrimItemComponent::new));

	public static final StreamCodec<RegistryFriendlyByteBuf, SkyrimItemComponent> STREAM_CODEC =
		new StreamCodec<>() {
			@Override
			public SkyrimItemComponent decode(RegistryFriendlyByteBuf buf) {
				return new SkyrimItemComponent(
					buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
					buf.readFloat(), buf.readFloat(), buf.readFloat(),
					buf.readInt(), buf.readInt(), buf.readInt()
				);
			}

			@Override
			public void encode(RegistryFriendlyByteBuf buf, SkyrimItemComponent value) {
				buf.writeInt(value.formId());
				buf.writeInt(value.baseFormId());
				buf.writeInt(value.category());
				buf.writeInt(value.value());
				buf.writeFloat(value.weight());
				buf.writeFloat(value.damage());
				buf.writeFloat(value.armor());
				buf.writeInt(value.enchantmentFormId());
				buf.writeInt(value.soulLevel());
				buf.writeInt(value.flags());
			}
		};
}
