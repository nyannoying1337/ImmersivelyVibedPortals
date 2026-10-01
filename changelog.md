**6.1.0-beta.9 — BETA: major issues may occur. Back up your worlds.**

Fixes
- Terrain behind the portal you stand at was loaded only 8 chunks far (the "indirect loading radius cap"), so beyond that the view showed sky with a hard edge. The portal in front of you (within 5 blocks) is now loaded as far as your render distance.
- Portal views are now only rendered as far as the terrain behind them is loaded, and end in fog there, like the normal view at the render distance (no more hard edges of sky or floating chunks in views of portals further away).

Changes
- In-game texts (chat messages, config screen, creative tab, command stick) now use the name Immersively Vibed Portals.
- The first-start screen is rewritten for this port (BETA notice, where to report problems, Sodium and Iris notes) and fixed (its text was off-center and cut off at the right edge). It is shown once more after updating.
