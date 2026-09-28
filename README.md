# Immersive Portals: unofficial Minecraft 26.3 port

This is an **unofficial** port of [Immersive Portals](https://github.com/iPortalTeam/ImmersivePortalsMod) by qouteall
to **Minecraft 26.3 (Fabric)**. The original project stopped at Minecraft 1.21.1 and is no longer maintained
("Forks are welcomed"). This port is not made or endorsed by the original author:
**please report problems here, not to the original project.**

> **Status: alpha.** Seamless see-through portals and teleportation work. Expect bugs.

### Requirements
- Minecraft 26.3, Fabric Loader 0.19.5+, Fabric API 0.161.0+26.3, Java 25
- DimLib and Cloth Config are bundled

### Known issues / not ported yet
- Sodium and Iris are **not supported** yet (the mod refuses to load with them)
- Global portals (dimension stacks, world wrapping) are not drawn
- Mirrors may show wrong faces; no clouds inside portal views
- Entities crossing a portal are not cut at the portal plane
- Old worlds: global portals saved by 1.21.1 are not migrated
- Only tested on the OpenGL backend

### What changed in the port
Minecraft 26.x replaced its OpenGL renderer with a new graphics layer (OpenGL or Vulkan, no stencil buffer),
so portal rendering was redesigned: portal views are rendered into offscreen targets before the main view,
and portal surfaces sample them. See [docs/rendering-26.3.md](docs/rendering-26.3.md) and [NOTICE](NOTICE).

### Building
Clone the [DimLib 26.3 port](https://github.com/YOUR_GITHUB_USER/DimLib) next to this repo (`../DimLib`, branch `port/26.3`),
or pass `-Pdimlib_path=<path>`, then run with a JDK 25:

```
./gradlew build
```

The jar is in `build/libs/`.

---

*Original README below.*

Update: this repo is not being maintained now. Forks are welcomed.

# Immersive Portals Mod

It's a Minecraft mod that provides see-through portals and seamless teleportation. It also can create "Non-Euclidean" (Uneuclidean) space effect.

![immptl.png](https://i.loli.net/2021/09/30/chHMG45dsnZNqep.png)

[On CurseForge](https://www.curseforge.com/minecraft/mc-mods/immersive-portals-mod)     [On Modrinth](https://modrinth.com/mod/immersiveportals)     [Website](https://qouteall.fun/immptl/)

This mod changes a lot of underlying Minecraft mechanics. This mod allows the client to load multiple dimensions at the same time and synchronize remote world information(blocks/entities) to client. It can render portal-in-portals. The portal rendering is roughly compatible with some versions of Sodium and Iris. The portal can transform player scale and gravity direction.  [Implementation Details](https://qouteall.fun/immptl/wiki/Implementation-Details)

(This is the Fabric version of Immersive Portals. [The Forge version](https://github.com/iPortalTeam/ImmersivePortalsModForNeo))

## API

This mod also provides some API for:

* Manage see-through portals
* Dynamically add dimensions
* Synchronize remote chunks to client
* Render the world into GUI
* Other utilities

[API description](https://qouteall.fun/immptl/wiki/API-for-Other-Mods.html).

## How to run this code
https://fabricmc.net/wiki/tutorial:setup

## Other

[Wiki](https://qouteall.fun/immptl/wiki/)

[Discord Server](https://discord.gg/BZxgURK)

[Support qouteall on Patreon](https://www.patreon.com/qouteall)

