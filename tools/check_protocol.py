#!/usr/bin/env python3
"""Check that the generated Java protocol mirror still matches the C++ protocol.

This is intentionally dependency-free so it can run before either side is built.
It checks constants that affect shared-memory addresses, message IDs, flags and
fixed-layout record sizes. The C++ compiler remains authoritative for sizeof().
"""

from __future__ import annotations

import ast
import operator
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CPP = ROOT / "protocol" / "skycraft_protocol.h"
JAVA = ROOT / "fabric" / "src" / "main" / "java" / "dev" / "skycraft" / "link" / "Proto.java"


def expression_value(expr: str) -> int:
    expr = expr.strip()
    expr = re.sub(r"\b(?:ULL|ull|UL|ul|LL|ll|L|u|U)\b", "", expr)
    expr = expr.replace("std::uint32_t(", "(").replace("std::uint64_t(", "(")
    expr = expr.replace("int(", "(")
    tree = ast.parse(expr, mode="eval")

    allowed_bin = {
        ast.Add: operator.add,
        ast.Sub: operator.sub,
        ast.Mult: operator.mul,
        ast.LShift: operator.lshift,
        ast.RShift: operator.rshift,
        ast.BitOr: operator.or_,
        ast.BitAnd: operator.and_,
    }
    allowed_unary = {ast.USub: operator.neg, ast.UAdd: operator.pos, ast.Invert: operator.invert}

    def visit(node: ast.AST) -> int:
        if isinstance(node, ast.Expression):
            return visit(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, int):
            return int(node.value)
        if isinstance(node, ast.BinOp) and type(node.op) in allowed_bin:
            return allowed_bin[type(node.op)](visit(node.left), visit(node.right))
        if isinstance(node, ast.UnaryOp) and type(node.op) in allowed_unary:
            return allowed_unary[type(node.op)](visit(node.operand))
        if isinstance(node, ast.ParenExpr):  # Python 3.12+
            return visit(node.expression)
        raise ValueError(f"unsupported expression: {expr!r}")

    return visit(tree)


def extract_constants(text: str, prefix_pattern: str) -> dict[str, int]:
    out: dict[str, int] = {}
    pattern = re.compile(
        rf"\b(static\s+)?(?:inline\s+)?(?:constexpr\s+)?"
        rf"(?:std::uint(?:8|16|32|64)_t|long|int|double|float|[A-Z_]+)\s+"
        rf"({prefix_pattern}[A-Za-z0-9_]*)\s*=\s*([^;,\n]+)"
    )
    for match in pattern.finditer(text):
        name, expr = match.group(1), match.group(2)
        try:
            out[name] = expression_value(expr)
        except Exception:
            pass
    return out


def extract_java_constants(text: str, prefix_pattern: str) -> dict[str, int]:
    out: dict[str, int] = {}
    pattern = re.compile(
        rf"\bpublic\s+static\s+final\s+(?:int|long)\s+"
        rf"({prefix_pattern}[A-Za-z0-9_]*)\s*=\s*([^;,\n]+)"
    )
    for match in pattern.finditer(text):
        name, expr = match.group(1), match.group(2)
        try:
            out[name] = expression_value(expr)
        except Exception:
            pass

    # Java has combined declarations such as:
    # public static final int A = 1, B = 2;
    combined = re.compile(
        rf"\b({prefix_pattern}[A-Za-z0-9_]*)\s*=\s*([^;,]+)"
    )
    for match in combined.finditer(text):
        name, expr = match.group(1), match.group(2)
        try:
            out[name] = expression_value(expr)
        except Exception:
            pass
    return out


def check_group(title: str, cpp: dict[str, int], java: dict[str, int], renames: dict[str, str]) -> list[str]:
    errors: list[str] = []
    for cpp_name, java_name in renames.items():
        if cpp_name not in cpp:
            errors.append(f"{title}: missing C++ constant {cpp_name}")
            continue
        if java_name not in java:
            errors.append(f"{title}: missing Java constant {java_name}")
            continue
        if cpp[cpp_name] != java[java_name]:
            errors.append(
                f"{title}: {cpp_name}={cpp[cpp_name]} != {java_name}={java[java_name]}"
            )
    return errors


def main() -> int:
    if not CPP.exists() or not JAVA.exists():
        print("protocol-check: source files not found", file=sys.stderr)
        return 2

    cpp_text = CPP.read_text(encoding="utf-8")
    java_text = JAVA.read_text(encoding="utf-8")

    cpp_off = extract_constants(cpp_text, r"kOff")
    java_off = extract_java_constants(java_text, r"OFF_")

    errors: list[str] = []
    errors += check_group(
        "shared offsets",
        cpp_off,
        java_off,
        {
            "kOffHeader": "OFF_HEADER",
            "kOffSkyState": "OFF_SKY_STATE",
            "kOffMcState": "OFF_MC_STATE",
            "kOffOverlayCtl": "OFF_OVERLAY_CTL",
            "kOffOverlaySlotHdr": "OFF_OVERLAY_SLOT_HDR",
            "kOffWaterGrid": "OFF_WATER_GRID",
            "kOffInputRing": "OFF_INPUT_RING",
            "kOffCollisionRing": "OFF_COLLISION_RING",
            "kOffOverlayPixels": "OFF_OVERLAY_PIXELS",
            "kOffActorTable": "OFF_ACTOR_TABLE",
            "kOffEventRing": "OFF_EVENT_RING",
            "kOffWorldEntities": "OFF_WORLD_ENTITIES",
            "kOffRenderRing": "OFF_RENDER_RING",
        },
    )

    version_cpp = re.search(r"\bkVersion\s*=\s*([^;]+)", cpp_text)
    version_java = re.search(r"\bVERSION\s*=\s*([^;]+)", java_text)
    if not version_cpp or not version_java:
        errors.append("protocol version constant is missing")
    else:
        if expression_value(version_cpp.group(1)) != expression_value(version_java.group(1)):
            errors.append(
                f"protocol version mismatch: C++={version_cpp.group(1).strip()} "
                f"Java={version_java.group(1).strip()}"
            )

    cpp_in = extract_constants(cpp_text, r"kIn")
    java_in = extract_java_constants(java_text, r"IN_")
    errors += check_group(
        "input message IDs",
        cpp_in,
        java_in,
        {
            "kInKey": "IN_KEY",
            "kInMouseButton": "IN_MOUSE_BUTTON",
            "kInScroll": "IN_SCROLL",
            "kInCursor": "IN_CURSOR",
            "kInText": "IN_TEXT",
            "kInReleaseAll": "IN_RELEASE_ALL",
            "kInHurt": "IN_HURT",
            "kInOpenMenu": "IN_OPEN_MENU",
            "kInLootItem": "IN_LOOT_ITEM",
        },
    )

    cpp_sky = extract_constants(cpp_text, r"kSky")
    java_sky = extract_java_constants(java_text, r"SKY_")
    errors += check_group(
        "Skyrim flags",
        cpp_sky,
        java_sky,
        {
            "kSkyInGame": "SKY_IN_GAME",
            "kSkyMenuOpen": "SKY_MENU_OPEN",
            "kSkyLoading": "SKY_LOADING",
            "kSkyRaining": "SKY_RAINING",
            "kSkySnowing": "SKY_SNOWING",
        },
    )

    cpp_mc = extract_constants(cpp_text, r"kMc")
    java_mc = extract_java_constants(java_text, r"MC_")
    errors += check_group(
        "Minecraft flags",
        cpp_mc,
        java_mc,
        {
            "kMcInWorld": "MC_IN_WORLD",
            "kMcScreenOpen": "MC_SCREEN_OPEN",
            "kMcOnGround": "MC_ON_GROUND",
            "kMcSneaking": "MC_SNEAKING",
            "kMcSprinting": "MC_SPRINTING",
            "kMcDead": "MC_DEAD",
            "kMcSwimming": "MC_SWIMMING",
            "kMcFlying": "MC_FLYING",
        },
    )

    cpp_ren = extract_constants(cpp_text, r"kRen")
    java_ren = extract_java_constants(java_text, r"REN_")
    errors += check_group(
        "render message IDs",
        cpp_ren,
        java_ren,
        {
            "kRenPad": "REN_PAD",
            "kRenAtlas": "REN_ATLAS",
            "kRenSection": "REN_SECTION",
            "kRenClearAll": "REN_CLEAR_ALL",
            "kRenTexture": "REN_TEXTURE",
            "kRenAvatar": "REN_AVATAR",
            "kRenScene": "REN_SCENE",
            "kRenAtlasRegion": "REN_ATLAS_REGION",
            "kRenLights": "REN_LIGHTS",
            "kRenRagdoll": "REN_RAGDOLL",
            "kRenSolids": "REN_SOLIDS",
            "kRenDug": "REN_DUG",
        },
    )

    expected_cpp_java = {
        "kMaxActors": "MAX_ACTORS",
        "kMaxWorldEntities": "MAX_WORLD_ENTITIES",
        "kInputRingEntries": "INPUT_RING_ENTRIES",
        "kEventRingEntries": "EVENT_RING_ENTRIES",
        "kCollisionRingBytes": "COLLISION_RING_BYTES",
        "kRenderRingBytes": "RENDER_RING_BYTES",
        "kUnitsPerBlock": "UNITS_PER_BLOCK",
    }

    for cpp_name, java_name in expected_cpp_java.items():
        # kUnitsPerBlock is a double, not extracted by the integer parser.
        if cpp_name == "kUnitsPerBlock":
            a = re.search(r"\bkUnitsPerBlock\s*=\s*([0-9.]+)", cpp_text)
            b = re.search(r"\bUNITS_PER_BLOCK\s*=\s*([0-9.]+)", java_text)
            if not a or not b or float(a.group(1)) != float(b.group(1)):
                errors.append(f"fundamental constant mismatch: {cpp_name} vs {java_name}")
            continue
        cpp_values = extract_constants(cpp_text, re.escape(cpp_name))
        java_values = extract_java_constants(java_text, re.escape(java_name))
        if cpp_name not in cpp_values or java_name not in java_values:
            # These use the exact name pattern and may not be caught by the generic extractor.
            continue
        if cpp_values[cpp_name] != java_values[java_name]:
            errors.append(f"fundamental constant mismatch: {cpp_name} != {java_name}")

    if errors:
        print("protocol-check: FAILED")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("protocol-check: OK")
    print(f"  version: {expression_value(version_cpp.group(1)) if version_cpp else '?'}")
    print(f"  C++: {CPP.relative_to(ROOT)}")
    print(f"  Java: {JAVA.relative_to(ROOT)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
