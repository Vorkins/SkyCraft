#pragma once

#include "Game.h"

namespace skycraft::WorldObjects
{
	enum class Kind : std::uint16_t
	{
		kUnknown = 0,
		kDoor,
		kContainer,
		kFurniture,
		kLever,
		kButton,
		kTrap,
		kWeb,
		kFlora,
		kTree,
		kStatic,
		kMovableStatic,
		kActivator,
	};

	enum Flags : std::uint32_t
	{
		kNone = 0,
		kActivatable = 1u << 0,
		kContainer = 1u << 1,
		kCanOpen = 1u << 2,
		kCanHarvest = 1u << 3,
		kBreakable = 1u << 4,
		kThinGeometry = 1u << 5,
	};

	struct Description
	{
		Kind kind{ Kind::kUnknown };
		std::uint32_t flags{ kNone };
		RE::FormID formId{ 0 };
		RE::FormID baseFormId{ 0 };
		std::uint32_t formType{ 0 };
		std::array<float, 3> position{};
		float yaw{ 0.0f };
		std::string name;
		std::string modelPath;

		bool Valid() const { return formId != 0 && kind != Kind::kUnknown; }
	};

	// Classifies a live Skyrim reference into a stable semantic object description.
	// This intentionally does not mutate the reference or open/activate anything.
	Description Describe(RE::TESObjectREFR* a_ref);

	const char* KindName(Kind a_kind);
	const char* MinecraftHint(Kind a_kind);
}
