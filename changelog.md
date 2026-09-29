**6.1.0-beta.4 — BETA: major issues may occur. Back up your worlds.**

New
- Portals to far-away places of the same dimension (beyond the render distance) now show the destination without Sodium too.
- Shaderpacks (Iris, experimental): render types a pack has no program for (Iris' fallback shaders) are clipped at portals too.
- Checked with real shaderpacks in the automated test: Complementary Reimagined r5.9.3 and BSL v10.1.8.

Fixes
- Fixed the portal views of a frame being skipped (with an error in the log) when several portals lead to different places of the same other dimension.
- No more false "Chunk loading failure" messages in the log.
