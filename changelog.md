**6.1.0-beta.3 — BETA: major issues may occur. Back up your worlds.**

Fixes
- Fixed a crash when leaving the End through the exit portal (and other vanilla dimension changes).
- Fixed the client teleporting by itself after teleportation was disabled and enabled again.

New
- Entities halfway through a portal are cut at the portal plane (the part that went through shows on the other side). Works with Sodium and Iris.
- Third-person view: when the camera is behind a portal, the view shows the other side.
- Shaderpacks (Iris, experimental): entities, block entities, particles and clouds are now clipped at portals too, not only terrain.

Performance
- Several portals into the same other dimension are much cheaper without Sodium (8 portals: 7.2 ms to 1.7 ms CPU per frame in the benchmark).
