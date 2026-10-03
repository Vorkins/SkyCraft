#include "WorldObjectAdapter.h"

#include <cctype>

namespace skycraft::WorldObjects
{
	namespace
	{
		bool StartsWith(const char* a_text, const char* a_prefix)
		{
			if (!a_text || !a_prefix) {
				return false;
			}
			while (*a_prefix) {
				const auto a = static_cast<unsigned char>(*a_text++);
				const auto b = static_cast<unsigned char>(*a_prefix++);
				if (std::tolower(a) != std::tolower(b)) {
					return false;
				}
			}
			return true;
		}

		bool Contains(const std::string& a_text, std::string_view a_part)
		{
			if (a_part.empty() || a_text.size() < a_part.size()) {
				return false;
			}
			for (std::size_t i = 0; i + a_part.size() <= a_text.size(); ++i) {
				bool same = true;
				for (std::size_t j = 0; j < a_part.size(); ++j) {
					const auto lhs = static_cast<unsigned char>(a_text[i + j]);
					const auto rhs = static_cast<unsigned char>(a_part[j]);
					if (std::tolower(lhs) != std::tolower(rhs)) {
						same = false;
						break;
					}
				}
				if (same) {
					return true;
				}
			}
			return false;
		}

		std::string ModelPath(RE::TESForm* a_base)
		{
			auto* model = a_base ? a_base->As<RE::TESModel>() : nullptr;
			const char* path = model ? model->GetModel() : nullptr;
			if (!path) {
				return {};
			}
			std::string out(path);
			if (StartsWith(out.c_str(), "meshes\\") || StartsWith(out.c_str(), "meshes/")) {
				out.erase(0, 7);
			}
			return out;
		}

		bool IsWeb(const std::string& a_model, const std::string& a_name)
		{
			// Webs are thin static/activator geometry and are frequently not a full Minecraft-sized
			// solid. Detect them semantically before voxel sampling gets a chance to classify them as air.
			return Contains(a_model, "web") ||
			       Contains(a_model, "spiderweb") ||
			       Contains(a_name, "web") ||
			       Contains(a_name, "паутин");
		}

		Kind ActivatorKind(const std::string& a_model, const std::string& a_name)
		{
			if (Contains(a_model, "lever") || Contains(a_name, "lever") || Contains(a_name, "рычаг")) {
				return Kind::kLever;
			}
			if (Contains(a_model, "button") || Contains(a_model, "pullbar") || Contains(a_name, "button") ||
			    Contains(a_name, "кноп") || Contains(a_name, "задвиж") || Contains(a_name, "ручк")) {
				return Kind::kButton;
			}
			if (Contains(a_model, "trap") || Contains(a_name, "trap") || Contains(a_name, "ловуш")) {
				return Kind::kTrap;
			}
			return Kind::kActivator;
		}

		void AddFlags(Description& a_out)
		{
			switch (a_out.kind) {
			case Kind::kDoor:
				a_out.flags |= kActivatable | kCanOpen;
				break;
			case Kind::kContainer:
				a_out.flags |= kActivatable | kContainer;
				break;
			case Kind::kFurniture:
				a_out.flags |= kActivatable;
				break;
			case Kind::kLever:
			case Kind::kButton:
				a_out.flags |= kActivatable;
				break;
			case Kind::kTrap:
				a_out.flags |= kActivatable | kBreakable;
				break;
			case Kind::kWeb:
				a_out.flags |= kBreakable | kThinGeometry;
				break;
			case Kind::kFlora:
				a_out.flags |= kActivatable | kCanHarvest | kBreakable;
				break;
			case Kind::kTree:
				a_out.flags |= kBreakable;
				break;
			case Kind::kMovableStatic:
			case Kind::kStatic:
				a_out.flags |= kBreakable;
				break;
			case Kind::kActivator:
				a_out.flags |= kActivatable;
				break;
			default:
				break;
			}
		}
	}

	Description Describe(RE::TESObjectREFR* a_ref)
	{
		Description out;
		if (!a_ref) {
			return out;
		}

		auto* base = a_ref->GetBaseObject();
		if (!base) {
			return out;
		}

		out.formId = a_ref->GetFormID();
		out.baseFormId = base->GetFormID();
		out.formType = static_cast<std::uint32_t>(base->GetFormType());
		out.position = { a_ref->GetPosition().x, a_ref->GetPosition().y, a_ref->GetPosition().z };
		out.yaw = a_ref->GetAngleZ();
		out.modelPath = ModelPath(base);

		if (const auto* displayName = a_ref->GetDisplayFullName(); displayName) {
			out.name = displayName;
		}

		const auto type = base->GetFormType();
		switch (type) {
		case RE::FormType::Door:
			out.kind = Kind::kDoor;
			break;
		case RE::FormType::Container:
			out.kind = Kind::kContainer;
			break;
		case RE::FormType::Furniture:
			out.kind = Kind::kFurniture;
			break;
		case RE::FormType::Flora:
			out.kind = IsWeb(out.modelPath, out.name) ? Kind::kWeb : Kind::kFlora;
			break;
		case RE::FormType::Tree:
			out.kind = Kind::kTree;
			break;
		case RE::FormType::MovableStatic:
			out.kind = IsWeb(out.modelPath, out.name) ? Kind::kWeb : Kind::kMovableStatic;
			break;
		case RE::FormType::Activator:
			out.kind = IsWeb(out.modelPath, out.name) ? Kind::kWeb : ActivatorKind(out.modelPath, out.name);
			break;
		case RE::FormType::Static:
			out.kind = IsWeb(out.modelPath, out.name) ? Kind::kWeb : Kind::kStatic;
			break;
		default:
			return out;
		}

		AddFlags(out);
		return out;
	}

	const char* KindName(Kind a_kind)
	{
		switch (a_kind) {
		case Kind::kDoor: return "door";
		case Kind::kContainer: return "container";
		case Kind::kFurniture: return "furniture";
		case Kind::kLever: return "lever";
		case Kind::kButton: return "button";
		case Kind::kTrap: return "trap";
		case Kind::kWeb: return "web";
		case Kind::kFlora: return "flora";
		case Kind::kTree: return "tree";
		case Kind::kStatic: return "static";
		case Kind::kMovableStatic: return "movable_static";
		case Kind::kActivator: return "activator";
		default: return "unknown";
		}
	}

	const char* MinecraftHint(Kind a_kind)
	{
		switch (a_kind) {
		case Kind::kDoor: return "minecraft:oak_door";
		case Kind::kContainer: return "minecraft:chest";
		case Kind::kFurniture: return "skycraft:skyrim_furniture";
		case Kind::kLever: return "minecraft:lever";
		case Kind::kButton: return "minecraft:stone_button";
		case Kind::kTrap: return "skycraft:skyrim_trap";
		case Kind::kWeb: return "minecraft:cobweb";
		case Kind::kFlora: return "minecraft:grass";
		case Kind::kTree: return "minecraft:oak_log";
		case Kind::kStatic: return "skycraft:skyrim_static";
		case Kind::kMovableStatic: return "skycraft:skyrim_movable_static";
		case Kind::kActivator: return "skycraft:skyrim_activator";
		default: return "";
		}
	}
}
