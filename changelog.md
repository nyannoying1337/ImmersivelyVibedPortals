**6.1.0-beta.5 — BETA: major issues may occur. Back up your worlds.**

Fixes
- Fixed a crash when clicking the Dimension Stack button on the create world screen.
- Fixed a crash when a portal view failed to render (for example through a broken portal); the view is skipped now.
- Portals with an invalid shape (zero scale, invalid position) are removed instead of rendered.
- Client worlds of other dimensions use their own sea level (rain/snow height).

New
- Leashes of entities crossing a portal are cut at the portal plane too.

Tested
- The portal wand, portal helper, command sticks, breakable mirrors, custom portal generation from datapacks, the dimension stack screen and every /portal subcommand are now covered by the automated in-game tests.
