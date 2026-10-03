#!/usr/bin/env python3
"""Verify that protocol/skycraft_protocol.h and Proto.java agree.

This checker deliberately resolves arithmetic expressions instead of only comparing literal
numbers. That matters for derived offsets such as kOffLootState.
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

BINOPS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.LShift: operator.lshift,
    ast.RShift: operator.rshift,
    ast.BitOr: operator.or_,
    ast.BitAnd: operator.and_,
}
UNARY = {ast.USub: operator.neg, ast.UAdd: operator.pos, ast.Invert: operator.invert}


def eval_int(expr: str, names: dict[str, int] | None = None) -> int:
    names = names or {}
    expr = re.sub(r"\b(?:ULL|ull|UL|ul|LL|ll|u|U|L|l)\b", "", expr.strip())
    tree = ast.parse(expr, mode="eval")

    def walk(node: ast.AST) -> int:
        if isinstance(node, ast.Expression):
            return walk(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, int):
            return int(node.value)
        if isinstance(node, ast.Name) and node.id in names:
            return names[node.id]
        if isinstance(node, ast.BinOp) and type(node.op) in BINOPS:
            return BINOPS[type(node.op)](walk(node.left), walk(node.right))
        if isinstance(node, ast.UnaryOp) and type(node.op) in UNARY:
            return UNARY[type(node.op)](walk(node.operand))
        raise ValueError(expr)

    return walk(tree)


def resolve(raw: dict[str, str], initial: dict[str, int] | None = None) -> dict[str, int]:
    resolved = dict(initial or {})
    pending = dict(raw)
    for _ in range(len(pending) + 4):
        progress = False
        for name, expr in list(pending.items()):
            try:
                resolved[name] = eval_int(expr, resolved)
            except (SyntaxError, ValueError):
                continue
            del pending[name]
            progress = True
        if not pending or not progress:
            break
    return resolved


def camel_to_upper(name: str) -> str:
    if name.startswith("k"):
        name = name[1:]
    name = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", name)
    name = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    return name.upper()


def extract_cpp_scalars(text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    pattern = re.compile(
        r"\b(?:static\s+)?(?:inline\s+)?(?:constexpr\s+)?"
        r"(?:std::uint8_t|std::uint16_t|std::uint32_t|std::uint64_t|int|long|size_t)\s+"
        r"(k[A-Za-z0-9_]+)\s*=\s*([^;,\n]+)"
    )
    for name, expr in pattern.findall(text):
        out[name] = expr
    return out


def extract_cpp_enums(text: str) -> dict[str, int]:
    out: dict[str, int] = {}
    enum_re = re.compile(r"\benum(?:\s+class)?\s+\w+(?:\s*:\s*[^\{]+)?\s*\{(.*?)\};", re.DOTALL)
    item_re = re.compile(r"^\s*(k[A-Za-z0-9_]+)(?:\s*=\s*([^,\n]+))?\s*,?\s*(?://.*)?$", re.MULTILINE)
    for body in enum_re.findall(text):
        current = -1
        for name, expr in item_re.findall(body):
            if expr:
                try:
                    current = eval_int(expr, out)
                except (SyntaxError, ValueError):
                    continue
            else:
                current += 1
            out[name] = current
    return out


def extract_java_scalars(text: str) -> dict[str, str]:
    out: dict[str, str] = {}
    declaration = re.compile(r"\bpublic\s+static\s+final\s+(?:int|long)\s+([^;]+);")
    assignment = re.compile(r"\b([A-Z][A-Z0-9_]*)\s*=\s*([^,]+)")
    for body in declaration.findall(text):
        for name, expr in assignment.findall(body):
            out[name] = expr
    return out


def compare_numeric(cpp: dict[str, int], java: dict[str, int]) -> list[str]:
    errors: list[str] = []
    for cpp_name, cpp_value in sorted(cpp.items()):
        java_name = camel_to_upper(cpp_name)
        if java_name not in java:
            continue
        if cpp_value != java[java_name]:
            errors.append(f"{cpp_name}={cpp_value} != {java_name}={java[java_name]}")
    return errors


def main() -> int:
    if not CPP.exists() or not JAVA.exists():
        print("protocol-check: protocol sources not found", file=sys.stderr)
        return 2

    cpp_text = CPP.read_text(encoding="utf-8")
    java_text = JAVA.read_text(encoding="utf-8")

    cpp_raw = extract_cpp_scalars(cpp_text)
    cpp_values = resolve(cpp_raw, extract_cpp_enums(cpp_text))
    java_raw = extract_java_scalars(java_text)
    java_values = resolve(java_raw)

    errors = compare_numeric(cpp_values, java_values)

    version_match = re.search(r"\bkVersion\s*=\s*([^;]+)", cpp_text)
    if not version_match or "VERSION" not in java_values:
        errors.append("could not read protocol VERSION")
    else:
        expected = eval_int(version_match.group(1), cpp_values)
        if expected != java_values["VERSION"]:
            errors.append(f"kVersion={expected} != VERSION={java_values['VERSION']}")

    size_map = {
        "Header": None,
        "OverlaySlotHdr": "SLOT_HDR_SIZE",
        "ActorRecord": "ACTOR_RECORD_BYTES",
        "McEvent": "EVENT_BYTES",
        "WorldEntity": "WORLD_ENTITY_BYTES",
        "LootItem": "LOOT_ITEM_BYTES",
        "LootState": "LOOT_STATE_BYTES",
        "LootRequest": "LOOT_REQUEST_BYTES",
        "RenVertex": "REN_VERTEX_BYTES",
        "ColTri": "COL_TRI_BYTES",
        "ColRegion": "COL_REGION_HEADER_BYTES",
        "ColBlock": "COL_BLOCK_BYTES",
    }
    for struct_name, java_name in size_map.items():
        if struct_name == "Header":
            expected = 0x20
        else:
            m = re.search(
                rf"static_assert\s*\(\s*sizeof\(\s*{re.escape(struct_name)}\s*\)\s*==\s*([^;]+)\)",
                cpp_text,
            )
            if not m:
                errors.append(f"missing C++ size assert for {struct_name}")
                continue
            try:
                expected = eval_int(m.group(1), cpp_values)
            except (SyntaxError, ValueError):
                errors.append(f"could not evaluate sizeof assert for {struct_name}")
                continue

        if java_name is not None:
            actual = java_values.get(java_name)
            if actual is None:
                errors.append(f"missing Java size constant {java_name}")
            elif expected != actual:
                errors.append(f"{struct_name} size {expected} != {java_name}={actual}")

    # Explicit layout invariants that must never overlap.
    try:
        world_off = cpp_values["kOffWorldEntities"]
        collision_off = cpp_values["kOffCollisionRing"]
        world_size = cpp_values["kMaxWorldEntities"] * 96 + 0x40
        loot_off = cpp_values["kOffLootState"]
        loot_req = cpp_values["kOffLootRequestRing"]
        mapping = cpp_values["kMappingBytes"]

        if world_off + world_size > collision_off:
            errors.append("WorldEntities overlaps the collision ring")
        if loot_off < cpp_values["kOffRenderRing"] + cpp_values["kRenderRingBytes"]:
            errors.append("LootState starts before the render ring ends")
        if loot_off < loot_req and loot_off + 0x1480 > loot_req:
            errors.append("LootState overlaps LootRequest ring")
        if loot_req + 0x1000 > mapping:
            errors.append("LootRequest ring exceeds mapping size")
        if loot_off < cpp_values["kOffCollisionRing"] + cpp_values["kCollisionRingBytes"]:
            errors.append("LootState overlaps the collision ring")
    except KeyError as exc:
        errors.append(f"missing layout constant: {exc.args[0]}")

    if errors:
        print("protocol-check: FAILED")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("protocol-check: OK")
    print(f"  version: {java_values['VERSION']}")
    print(f"  checked C++ constants: {len(cpp_values)}")
    print(f"  checked Java constants: {len(java_values)}")
    print(f"  loot state offset: 0x{cpp_values['kOffLootState']:X}")
    print(f"  loot request offset: 0x{cpp_values['kOffLootRequestRing']:X}")
    print(f"  mapping bytes: 0x{cpp_values['kMappingBytes']:X}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
