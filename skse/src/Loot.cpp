#include "Loot.h"

#include <algorithm>
#include <cstring>
#include <cstdio>

namespace skycraft::Loot
{
	namespace
	{
		RE::ObjectRefHandle sessionHandle;
		std::uint32_t sessionId = 0;
		std::uint32_t revision = 0;
		std::uint32_t lastRequestId = 0;
		bool           open = false;
		float          refreshTimer = 0.0f;
		proto::LootState state{};

		std::uint32_t NextSessionId()
		{
			++sessionId;
			if (sessionId == 0) {
				++sessionId;
			}
			return sessionId;
		}

		std::uint32_t WorldIdFor(RE::TESObjectREFR* a_ref)
		{
			if (!a_ref) {
				return 0;
			}
			if (auto* cell = a_ref->GetParentCell()) {
				// The cell is a stronger anti-dupe identity than a raw pointer and is stable for
				// the lifetime of the loaded Skyrim source reference. The protocol calls this
				// worldId because it is used for the same session/world boundary checks.
				return cell->GetFormID();
			}
			return 0;
		}

		std::uint32_t LootCategoryFor(RE::TESBoundObject* a_object)
		{
			if (!a_object) {
				return proto::kLootMisc;
			}
			if (a_object->IsGold()) {
				return proto::kLootGold;
			}
			if (a_object->IsAmmo()) {
				return proto::kLootAmmo;
			}
			if (a_object->IsArmor()) {
				return proto::kLootArmor;
			}
			if (a_object->IsBook()) {
				return proto::kLootBook;
			}
			if (a_object->IsKey()) {
				return proto::kLootKey;
			}
			if (a_object->IsSoulGem()) {
				return proto::kLootSoulGem;
			}
			switch (a_object->GetFormType()) {
			case RE::FormType::Weapon:
				return proto::kLootWeapon;
			case RE::FormType::AlchemyItem:
				return proto::kLootPotion;
			case RE::FormType::Ingredient:
				return proto::kLootIngredient;
			default:
				return proto::kLootMisc;
			}
		}

		void CopyString(char* a_dst, std::size_t a_capacity, const char* a_src)
		{
			if (!a_dst || a_capacity == 0) {
				return;
			}
			std::memset(a_dst, 0, a_capacity);
			if (!a_src) {
				return;
			}
			std::strncpy(a_dst, a_src, a_capacity - 1);
			a_dst[a_capacity - 1] = '\0';
		}

		void FillItem(proto::LootItem& a_out, RE::TESBoundObject* a_object, RE::InventoryEntryData* a_entry, std::int32_t a_count)
		{
			std::memset(&a_out, 0, sizeof(a_out));
			if (!a_object || a_count <= 0) {
				return;
			}

			a_out.formId = a_object->GetFormID();
			a_out.baseFormId = a_object->GetFormID();
			a_out.count = static_cast<std::uint32_t>(std::min<std::int32_t>(a_count, 0x7FFFFFFF));
			a_out.category = LootCategoryFor(a_object);

			if (a_entry) {
				if (a_entry->IsEnchanted()) {
					a_out.flags |= proto::kLootItemEnchanted;
				}
				if (a_entry->IsFavorited()) {
					a_out.flags |= proto::kLootItemFavorited;
				}
				if (a_entry->IsWorn()) {
					a_out.flags |= proto::kLootItemWorn;
				}
				if (a_entry->IsPoisoned()) {
					a_out.flags |= proto::kLootItemPoisoned;
				}
				if (a_entry->IsLeveled()) {
					a_out.flags |= proto::kLootItemLeveled;
				}

				a_out.value = a_entry->GetValue();
				a_out.weight = a_entry->GetWeight();
				a_out.soulLevel = static_cast<std::uint32_t>(a_entry->GetSoulLevel());

				if (auto* enchantment = a_entry->GetEnchantment()) {
					a_out.enchantmentFormId = enchantment->GetFormID();
				}

				if (const char* name = a_entry->GetDisplayName(); name && *name) {
					CopyString(a_out.name, sizeof(a_out.name), name);
				}
			}

			if (auto* weapon = a_object->As<RE::TESObjectWEAP>()) {
				a_out.damage = static_cast<float>(weapon->GetAttackDamage());
			}
			if (auto* armor = a_object->As<RE::TESObjectARMO>()) {
				a_out.armor = armor->GetArmorRating();
			}

			if (a_out.name[0] == '\0') {
				char fallback[32];
				std::snprintf(fallback, sizeof(fallback), "Skyrim item %08X", a_out.formId);
				CopyString(a_out.name, sizeof(a_out.name), fallback);
			}
		}

		bool BuildSnapshot(RE::TESObjectREFR* a_source, proto::LootState& a_out, bool a_forceRevision)
		{
			if (!a_source) {
				return false;
			}

			proto::LootState next{};
			next.phase = proto::kLootOpen;
			next.sessionId = sessionId;
			next.revision = revision;
			next.worldId = WorldIdFor(a_source);
			next.sourceFormId = a_source->GetFormID();
			next.flags = 0;

			if (auto* actor = a_source->As<RE::Actor>(); actor && actor->IsDead()) {
				next.flags |= proto::kLootStateDeadActor;
			}
			if (a_source->GetContainer()) {
				next.flags |= proto::kLootStateContainer;
			}

			CopyString(next.title, sizeof(next.title), a_source->GetDisplayFullName());
			auto inventory = a_source->GetInventory();

			std::uint32_t count = 0;
			for (auto& [object, entry] : inventory) {
				if (!object || entry.first <= 0 || count >= proto::kLootMaxItems) {
					continue;
				}
				if (entry.second && entry.second->IsQuestObject()) {
					continue;
				}
				FillItem(next.items[count], object, entry->second, entry->first);
				++count;
			}
			next.count = count;

			if (!a_forceRevision) {
				if (std::memcmp(
						reinterpret_cast<const std::uint8_t*>(&next) + sizeof(next.seq),
						reinterpret_cast<const std::uint8_t*>(&a_out) + sizeof(a_out.seq),
						sizeof(next) - sizeof(next.seq)) == 0) {
					return false;
				}
			}

			next.revision = revision;
			a_out = next;
			return true;
		}

		RE::TESObjectREFR* CurrentSource()
		{
			return sessionHandle.get();
		}

		bool ValidRequest(const proto::LootRequest& a_request, RE::TESObjectREFR* a_source)
		{
			if (!open || !a_source) {
				return false;
			}
			if (a_request.sessionId != sessionId || a_request.sourceFormId != a_source->GetFormID()) {
				return false;
			}
			if (a_request.requestId == 0 || a_request.requestId <= lastRequestId) {
				return false;
			}
			lastRequestId = a_request.requestId;
			return true;
		}

		bool TakeOne(RE::TESObjectREFR* a_source, const proto::LootRequest& a_request)
		{
			if (a_request.formId == 0 || a_request.count == 0 || a_request.count > 1024) {
				return false;
			}
			if (a_request.revision != revision) {
				logger::warn("loot request {} rejected: stale revision {} (current {})", a_request.requestId, a_request.revision, revision);
				return false;
			}

			auto inventory = a_source->GetInventory();
			for (auto& [object, entry] : inventory) {
				if (!object || entry.first <= 0 || object->GetFormID() != a_request.formId) {
					continue;
				}
				if (entry->second && entry->second->IsQuestObject()) {
					return false;
				}
				if (a_request.baseFormId != 0 && a_request.baseFormId != object->GetFormID()) {
					return false;
				}
				const auto count = std::min<std::uint32_t>(a_request.count, static_cast<std::uint32_t>(entry->first));
				if (count == 0) {
					return false;
				}
				a_source->RemoveItem(object, static_cast<std::int32_t>(count), RE::ITEM_REMOVE_REASON::kRemove, nullptr, nullptr);
				return true;
			}
			return false;
		}

		bool TakeAll(RE::TESObjectREFR* a_source, const proto::LootRequest& a_request)
		{
			if (a_request.revision != revision) {
				logger::warn("loot take-all {} rejected: stale revision {} (current {})", a_request.requestId, a_request.revision, revision);
				return false;
			}
			auto inventory = a_source->GetInventory();
			bool removed = false;
			for (auto& [object, entry] : inventory) {
				if (!object || entry.first <= 0) {
					continue;
				}
				if (entry->second && entry->second->IsQuestObject()) {
					continue;
				}
				a_source->RemoveItem(object, entry->first, RE::ITEM_REMOVE_REASON::kRemove, nullptr, nullptr);
				removed = true;
			}
			return removed;
		}

		void CloseInternal(bool a_publish)
		{
			if (!open) {
				return;
			}

			auto* source = CurrentSource();
			const auto nextRevision = ++revision == 0 ? ++revision : revision;
			proto::LootState closed{};

			if (source && BuildSnapshot(source, closed, true)) {
				closed.phase = proto::kLootClosed;
				closed.sessionId = sessionId;
				closed.revision = nextRevision;
				closed.worldId = WorldIdFor(source);
				closed.sourceFormId = source->GetFormID();
				state = closed;
			} else {
				state = {};
				state.phase = proto::kLootClosed;
				state.sessionId = sessionId;
				state.revision = nextRevision;
				if (source) {
					state.worldId = WorldIdFor(source);
					state.sourceFormId = source->GetFormID();
				}
			}

			open = false;
			sessionHandle = {};
			refreshTimer = 0.0f;
			lastRequestId = 0;
			if (a_publish) {
				Link::Get().WriteLootState(state);
			}
		}

	}

	bool OpenTarget(RE::TESObjectREFR* a_target, RE::PlayerCharacter* a_player)
	{
		if (!a_target || !a_player || a_target == a_player) {
			return false;
		}
		auto* actor = a_target->As<RE::Actor>();
		const bool deadActor = actor && actor->IsDead();
		const bool container = a_target->GetContainer() != nullptr;
		if (!deadActor && !container) {
			return false;
		}

		if (open) {
			CloseInternal(true);
		}

		sessionId = NextSessionId();
		revision = 1;
		lastRequestId = 0;
		sessionHandle = a_target->GetHandle();
		open = true;
		refreshTimer = 0.0f;

		state = {};
		state.phase = proto::kLootOpen;
		state.sessionId = sessionId;
		state.revision = revision;
		state.worldId = WorldIdFor(a_target);
		state.sourceFormId = a_target->GetFormID();
		state.flags = deadActor ? proto::kLootStateDeadActor : 0u;
		if (container) {
			state.flags |= proto::kLootStateContainer;
		}
		CopyString(state.title, sizeof(state.title), a_target->GetDisplayFullName());

		if (!BuildSnapshot(a_target, state, true)) {
			// BuildSnapshot only returns false on invalid source or an unchanged snapshot.
			CloseInternal(true);
			return true;
		}
		Link::Get().WriteLootState(state);
		logger::info("loot session {} opened for {:08X} ({})", sessionId, state.sourceFormId, state.title);
		return true;
	}

	void PerFrame(RE::PlayerCharacter* a_player, float a_delta)
	{
		if (!open) {
			return;
		}
		if (!a_player || !Link::Get().Valid() || !Link::Get().McAlive()) {
			CloseInternal(true);
			return;
		}

		auto* source = CurrentSource();
		if (!source || source->GetFormID() != state.sourceFormId || WorldIdFor(source) != state.worldId) {
			CloseInternal(true);
			return;
		}

		proto::LootRequest request{};
		while (Link::Get().ReadLootRequest(request)) {
			if (!ValidRequest(request, source)) {
				continue;
			}

			if (request.type == proto::kLootClose) {
				CloseInternal(true);
				return;
			}

			bool changed = false;
			switch (request.type) {
			case proto::kLootTake:
				changed = TakeOne(source, request);
				break;
			case proto::kLootTakeAll:
				changed = TakeAll(source, request);
				break;
			default:
				break;
			}

			if (changed) {
				++revision;
				if (revision == 0) {
					++revision;
				}
				if (!BuildSnapshot(source, state, true)) {
					CloseInternal(true);
					return;
				}
				state.revision = revision;
				Link::Get().WriteLootState(state);
				logger::info("loot session {} accepted request {} (type {}, revision {})", sessionId, request.requestId, request.type, revision);
			} else if (request.type == proto::kLootTake || request.type == proto::kLootTakeAll) {
				// A rejected/stale transaction gets a fresh snapshot. The client can correct its view
				// without ever being allowed to consume the same Skyrim item twice.
				++revision;
				if (revision == 0) {
					++revision;
				}
				BuildSnapshot(source, state, true);
				state.revision = revision;
				Link::Get().WriteLootState(state);
			}
		}

		refreshTimer -= a_delta;
		if (refreshTimer > 0.0f) {
			return;
		}
		refreshTimer = 0.25f;

		if (BuildSnapshot(source, state, false)) {
			++revision;
			if (revision == 0) {
				++revision;
			}
			state.revision = revision;
			Link::Get().WriteLootState(state);
		}
	}

	void Close()
	{
		CloseInternal(true);
	}
}
