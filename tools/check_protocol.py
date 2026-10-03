#!/usr/bin/env python3
"""Verify that protocol/skycraft_protocol.h and Proto.java agree.

The protocol is duplicated by design because the Skyrim side is C++ and the Minecraft
side is Java. This check catches accidental drift before a binary build is attempted.
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


def eval_int(expr: str) -> int:
    expr = re.sub(r"(?<![\w.])(?:0[xX][0-9a-fA-F]+|\d+)(?:ULL|ull|UL|ul|LL|ll|u|U|L|l)\b",
                  lambda m: re.sub(r"[A-Za-z]+$", "", m.group(0)), expr.strip())
    expr = re.sub(r"\b(?:ULL|ull|UL|ul|LL|ll|u|U|L|l)\b", "", expr)
    tree = ast.parse(expr, mode="eval")

    def walk(node: ast.AST) -> int:
        if isinstance(node, ast.Expression):
            return walk(node.body)
        if isinstance(node, ast.Constant) and isinstance(node.value, int):
            return int(node.value)
        if isinstance(node, ast.BinOp) and type(node.op) in BINOPS:
            return BINOPS[type(node.op)](walk(node.left), walk(node.right))
        if isinstance(node, ast.UnaryOp) and type(node.op) in UNARY:
            return UNARY[type(node.op)](walk(node.operand))
        raise ValueError(expr)

    return walk(tree)


def camel_to_upper(name: str) -> str:
    if name.startswith("k"):
        name = name[1:]
    name = re.sub(r"([A-Z]+)([A-Z][a-z])", r"\1_\2", name)
    name = re.sub(r"([a-z0-9])([A-Z])", r"\1_\2", name)
    return name.upper()


def extract_cpp_scalars(text: str) -> dict[str, int]:
    out: dict[str, int] = {}
    pattern = re.compile(
        r"\b(?:static\s+)?(?:inline\s+)?(?:constexpr\s+)?"
        r"(?:std::uint8_t|std::uint16_t|std::uint32_t|std::uint64_t|int|long|size_t)\s+"
        r"(k[A-Za-z0-9_]+)\s*=\s*([^;,\n]+)"
    )
    for name, expr in pattern.findall(text):
        try:
            out[name] = eval_int(expr)
        except (SyntaxError, ValueError):
            pass
    return out


def extract_cpp_enums(text: str) -> dict[str, int]:
    out: dict[str, int] = {}
    enum_re = re.compile(r"\benum(?:\s+class)?\s+\w+(?:\s*:\s*[^\{]+)?\s*\{(.*?)\};", re.DOTALL)
    item_re = re.compile(r"\b(k[A-Za-z0-9_]+)(?:\s*=\s*([^,\n]+))?\s*,?")
    for body in enum_re.findall(text):
        current = -1
        for name, expr in item_re.findall(body):
            if expr:
                try:
                    current = eval_int(expr)
                except (SyntaxError, ValueError):
                    continue
            else:
                current += 1
            out[name] = current
    return out


def extract_java_scalars(text: str) -> dict[str, int]:
    out: dict[str, int] = {}
    # Works for both single declarations and comma-separated declarations.
    declaration = re.compile(
        r"\bpublic\s+static\s+final\s+(?:int|long)\s+([^;]+);"
    )
    assignment = re.compile(r"\b([A-Z][A-Z0-9_]*)\s*=\s*([^,]+)")
    for declaration_body in declaration.findall(text):
        for name, expr in assignment.findall(declaration_body):
            try:
                out[name] = eval_int(expr)
            except (SyntaxError, ValueError):
                pass
    return out


def check_constants(cpp: dict[str, int], java: dict[str, int]) -> list[str]:
    errors: list[str] = []
    for cpp_name, cpp_value in sorted({**cpp}.items()):
        java_name = camel_to_upper(cpp_name)
        # The Java mirror has extra helper offsets that do not exist as named C++ constants.
        if java_name not in java:
            continue
        java_value = java[java_name]
        if cpp_value != java_value:
            errors.append(f"{cpp_name}={cpp_value} != {java_name}={java_value}")
    return errors


def main() -> int:
    if not CPP.exists() or not JAVA.exists():
        print("protocol-check: protocol sources not found", file=sys.stderr)
        return 2

    cpp_text = CPP.read_text(encoding="utf-8")
    java_text = JAVA.read_text(encoding="utf-8")

    cpp = {**extract_cpp_scalars(cpp_text), **extract_cpp_enums(cpp_text)}
    java = extract_java_scalars(java_text)

    errors = check_constants(cpp, java)

    version_match = re.search(r"\bkVersion\s*=\s*([^;]+)", cpp_text)
    if not version_match or java.get("VERSION") is None:
        errors.append("could not read protocol VERSION")
    elif eval_int(version_match.group(1)) != java["VERSION"]:
        errors.append(f"kVersion={eval_int(version_match.group(1))} != VERSION={java['VERSION']}")

    # Validate the fixed-size records that Java explicitly mirrors with byte-count constants.
    size_map = {
        "ActorRecord": "ACTOR_RECORD_BYTES",
        "McEvent": "EVENT_BYTES",
        "WorldEntity": "WORLD_ENTITY_BYTES",
        "RenVertex": "REN_VERTEX_BYTES",
        "ColTri": "COL_TRI_BYTES",
        "ColRegion": "COL_REGION_HEADER_BYTES",
        "ColBlock": "COL_BLOCK_BYTES",
        "OverlaySlotHdr": "SLOT_HDR_SIZE",
    }
    for struct_name, java_name in size_map.items():
        m = re.search(
            rf"static_assert\s*\(\s*sizeof\(\s*{re.escape(struct_name)}\s*\)\s*==\s*([^;]+)\)",
            cpp_text,
        )
        if not m:
            errors.append(f"missing C++ size assert for {struct_name}")
            continue
        expected = eval_int(m.group(1))
        actual = java.get(java_name)
        if actual is None:
            errors.append(f"missing Java size constant {java_name}")
        elif expected != actual:
            errors.append(f"{struct_name} size {expected} != {java_name}={actual}")

    if errors:
        print("protocol-check: FAILED")
        for error in errors:
            print(f"  - {error}")
        return 1

    print("protocol-check: OK")
    print(f"  version: {java['VERSION']}")
    print(f"  checked C++ numeric constants: {len(cpp)}")
    print(f"  checked Java numeric constants: {len(java)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
