**6.1.0-beta.8 — BETA: major issues may occur. Back up your worlds.**

Fixes
- Fixed missing terrain in portal views when several portals in view lead to far-apart places of the same dimension (for example two nether portals to different parts of the overworld): the views showed no terrain at all, or only a small patch.

New
- `/imm_ptl_client_debug report_portal_views` (run it twice): tells, for each portal view, why terrain is missing (chunks not loaded, terrain not built, low performance level). Please include its output when reporting missing terrain.

Tested
- Every portal view in the automated visual test is now checked for missing terrain, on OpenGL, Vulkan, Sodium (both) and Iris with Complementary Reimagined and BSL.
