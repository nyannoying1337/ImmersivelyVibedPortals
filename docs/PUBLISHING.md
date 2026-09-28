# Publishing the 26.3 port

## 1. GitHub

1. On GitHub, fork `iPortalTeam/ImmersivePortalsMod` and `iPortalTeam/DimLib` (keeps the history and the link to the original).
2. Replace `YOUR_GITHUB_USER` with your GitHub username in:
   - `ImmersivePortalsMod/gradle.properties` (`github_repo`) and `ImmersivePortalsMod/README.md`
   - `DimLib/gradle.properties` (`github_repo`) and `DimLib/README.md`
3. Push the `port/26.3` branch of both repos to your forks, and make it the default branch.
   The Immersive Portals CI checks out `<your user>/DimLib` (branch `port/26.3`), so DimLib must be pushed too.
4. Check the Actions tab: both builds should be green and upload the jar as an artifact.

## 2. Modrinth

Relevant rules (https://modrinth.com/legal/rules):
- Forks are allowed if they diverge substantially and credit the original (Section 4). A port to a new
  Minecraft version with a rewritten renderer qualifies; credit qouteall in the description.
- The title must not suggest it's the official project or endorsed by its author (Sections 1.8 to 1.9).
- Remote requests must be disclosed (Section 1.11). The port makes none (the upstream update check is disabled).

Suggested listing:

- **Name:** Immersive Portals (Unofficial 26.3 Port)
- **Slug:** e.g. `immersive-portals-26-3-port`
- **Summary:** Unofficial port of Immersive Portals to Minecraft 26.3: see-through portals and seamless teleportation.
- **License:** Apache-2.0
- **Source:** your GitHub fork; **Issues:** your fork's issue tracker
- **Loaders / versions:** Fabric, Minecraft 26.3
- **Dependencies:** Fabric API (required). DimLib and Cloth Config are bundled.
  Mark Sodium and Iris as incompatible (the mod declares `breaks` for them).
- **Version:** upload `build/libs/immersive-portals-<version>-mc26.3-fabric.jar`, channel **alpha**.

Description template:

> Unofficial port of [Immersive Portals](https://modrinth.com/mod/immersiveportals) by **qouteall** to Minecraft 26.3.
> The original project is no longer maintained. This port is not made or endorsed by qouteall:
> please report problems to this project, not to the original.
>
> See-through portals, seamless teleportation, and non-Euclidean spaces.
>
> **Alpha:** Sodium/Iris are not supported yet; global portals (dimension stacks), mirror culling and
> cross-portal entity clipping are unfinished. Back up your worlds.
>
> Licensed under Apache-2.0. Original work © qouteall and contributors; see NOTICE for the changes.
