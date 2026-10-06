**6.1.0-beta.12 — BETA: major issues may occur. Back up your worlds.**

New
- With Sodium, the main settings (preset, portals in portals, view distance, far portal updates, ...) are also in Sodium's Video Settings screen, on their own "Immersively Vibed Portals" page, with a button to all settings.

**6.1.0-beta.11 — BETA: major issues may occur. Back up your worlds.**

Fixes
- Fixed a crash with Sodium and "Improved Transparency" (Video Settings) as soon as water or other translucent blocks are in view ("Failed to find or load pipeline ... oit_depth_bounds_sodium_terrain"). It happened on all graphics cards, not only NVIDIA.

**6.1.0-beta.10 — BETA: major issues may occur. Back up your worlds.**

New
- Portals hidden behind blocks are now also skipped with Sodium (more FPS in bases with many portals).
- Portal views are rendered only for the part of the screen the portal covers: small and distant portals cost much less GPU time (not with shaderpacks).
- Fewer updates for far portals: small portals far away are rendered every 2nd or 3rd frame while you stand still (on in the Performance and Balanced presets).
- Tested together with Sodium, Lithium, FerriteCore, Entity Culling, Xaero's Minimap, C2ME and ImmediatelyFast. With Entity Culling, entities seen through portals are no longer hidden by it.
- Config presets: Performance, Balanced (the defaults), Quality and Custom, at the top of the config screen. A preset sets the settings that cost the most performance (portals inside portals, portal view distance, terrain loaded behind distant portals, showing yourself in portals, loading less at low FPS); changing one of them by hand makes it Custom.

Fixes
- Fixed a disconnect ("Network Protocol Error") when changing dimension (e.g. with /tp or /execute in another dimension) while looking at a portal.
- Fixed a crash with Sodium or Iris right after changing dimension ("Cannot wait on a fence for the current submit").
- The "You are using Nvidia videocard" chat warning only shows with the OpenGL backend (the driver issue it links to is OpenGL-only).
- Fixed a crash "Frame not in use" after an error while rendering a portal view (the error is now only logged).
- Sodium: portal views of the dimension you just left could stay without terrain (depending on timing).
- Shaderpacks: portal views no longer get thick cave fog (the light at the eyes was measured at the player's position in the other dimension).

Known issue
- NVIDIA graphics cards with "Improved Transparency" (Video Settings) turned on: crashes ("Failed to find or load pipeline ... oit_depth_bounds ...") and terrain missing around portals. Turn Improved Transparency off for now. (The crash is fixed in 6.1.0-beta.11; it was not NVIDIA-only.)
