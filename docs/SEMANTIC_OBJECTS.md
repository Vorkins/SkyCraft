# Semantic Skyrim objects

`WorldObjectAdapter` provides a side-effect-free semantic description for Skyrim world references.

It deliberately sits above raw collision/voxel sampling. Geometry answers "what is physically here"; this adapter answers "what game object is this".

## Supported semantic kinds

- door
- container
- furniture
- lever
- button
- trap
- web
- flora
- tree
- static
- movable static
- generic activator

## Web handling

Thin web meshes are classified as `web` before they are reduced to block-scale geometry. The Minecraft representation hint is `minecraft:cobweb`.

This is intended to complement, not replace, `Dig`'s material sampling. A web can therefore have a semantic identity even when fewer than 27 voxel samples land inside the mesh.

## Activation hints

The adapter exposes flags such as `kActivatable`, `kContainer`, `kCanOpen`, `kCanHarvest`, `kBreakable` and `kThinGeometry`.

The module does not activate references, mutate inventories, remove world objects or write to shared memory. A later IPC layer can serialize `Description` into a world-object message without changing classification logic.

## Why this is separate

Keeping semantic classification out of `Input.cpp` avoids coupling object detection to the G-key routing and makes the same classifier usable for:

- Minecraft-side object proxies
- crosshair interaction
- semantic block rendering
- future object state synchronization
- special cases such as Arvel's web
