#pragma once

#include "Game.h"

namespace skycraft::Loot
{
	// G on a dead actor or Skyrim container opens a Skyrim-authoritative loot session.
	// No inventory mutation occurs here; mutations happen only after a validated LootRequest.
	bool OpenTarget(RE::TESObjectREFR* a_target, RE::PlayerCharacter* a_player);

	// Main-thread transaction pump. Publishes snapshots and consumes Minecraft loot requests.
	void PerFrame(RE::PlayerCharacter* a_player, float a_delta);

	// Explicitly close the current session, if any.
	void Close();
}
