# Portal rendering on Minecraft 26.3: design

Status: design for the 26.3 port. Replaces the 1.21.1 stencil/recursive approach.
Decompiled MC sources: `F:\Immersive Portals\mc-src\26.3\`. Shader assets: in the loom-cache MC jar under `assets/minecraft/shaders/`.

## What changed in 26.3 (constraints)

1. Rendering goes through `com.mojang.renderpearl` (OpenGL or Vulkan backend; OpenGL is the default). No raw GL.
2. No stencil, no clip planes (`gl_ClipDistance`), no occlusion queries in the API.
3. A frame is `GameRenderer.update` -> `GameRenderer.extract` (game objects -> `GameRenderState`/`LevelRenderState`) -> `GameRenderer.render` -> one GPU submit.
   `LevelRenderer.render` builds a frame graph with one big "main" pass. A world render cannot be nested inside it
   (`FeatureRenderDispatcher` has a single `PreparedFrame`; extracted entity lists are consumed).
4. Depth is reversed-Z: clear depth = 0.0, nearer = larger, test `GREATER_THAN_OR_EQUAL`.
5. Buffers written through `MappableRingBuffer.currentBuffer()` (fog, lightmap, clouds, post pass info) hold one value per frame.
   Writing them twice per frame overwrites data earlier draws use. Rotating them mid-frame can deadlock (fences only complete after the frame's submit).
   Buffers written with `CommandEncoder.writeToBuffer` (projection, `GlobalSettingsUniform`) are ordered in the command stream and are safe to rewrite between views.
6. `LevelRenderer` always outputs to `GameRenderer.mainRenderTarget()`. `SkyRenderer` caches its target at construction.

## Approach: pre-render portal views, then composite

Old: while rendering the outer world, at the portal, swap global state and call `GameRenderer.renderLevel` again (recursion), stencil-masked.
New: no recursion inside a render. Each frame:

1. **Plan** (start of `GameRenderer.extract`, after `update`; main camera is up to date):
   build a tree of `PortalView`s. Root = main view. Children of a view = portals visible from that view's camera
   (CPU culling: frustum, distance, `Portal.isRoughlyVisibleTo`, `PortalRenderer.PORTAL_RENDERING_PREDICATE`, limits `IPGlobal.maxPortalLayer`, `IPGlobal.portalRenderLimit`).
2. **Render views in post-order** (deepest first), each fully sequential:
   - acquire a `TextureTarget` (RGBA8 + D32_FLOAT, window size) from `PortalViewTargetPool`;
   - enter the view context (`PortalViewContext.push`): swap in the destination `ClientLevel`, that dimension's `LevelRenderer` + `LevelExtractor`,
     a `Camera` placed by the portal transform, the view's fog buffer instance, the dimension lightmap, and redirect `mainRenderTarget()` (and the sky renderer's target) to the view target;
   - run: `LevelExtractor.extract(...)` for that camera -> set projection -> `GlobalSettingsUniform.update` (camera pos + clip plane) -> `LevelRenderer.render(...)`;
   - pop the context (restore everything).
3. **Main render** runs as vanilla. Wherever a portal is drawn (in the main view or inside a child view), its surface is rendered with the
   portal-surface render type, which samples that portal's view texture in screen space (`gl_FragCoord.xy / ScreenSize`) with depth test + write.
   Because children render first, a parent view can sample its children's textures (nested portals).

Everything happens on the render thread in order, so the per-dimension `LevelRenderer`s can share `GameRenderer.gameRenderState()`
(each view overwrites it; the main extract runs last).

## Components (new code lives in `qouteall.imm_ptl.core.render`)

| Component | Responsibility |
|---|---|
| `PortalViewPlanner` | Builds the view tree for the frame from the main camera. CPU culling only (no GPU queries). |
| `PortalView` | One node: portal, parent, depth, `WorldRenderInfo`, camera pos/rotation, clip plane, assigned target. |
| `PortalViewTargetPool` | Pool of window-sized `TextureTarget`s; resize on window resize; indexes for this frame. |
| `PortalViewRenderer` | Runs the post-order render of the tree (step 2). Entry point called from the `GameRenderer.extract` HEAD mixin. |
| `PortalViewContext` | Push/pop of the swapped client state (replaces `MyGameRenderer.switchAndRenderTheWorld`). Knows `isRenderingPortalView()` / current view. |
| `DimensionRenderResources` | Per-dimension `LevelRenderer` + `LevelExtractor` + `Lightmap`, created lazily in `ClientWorldLoader` (replaces `WORLD_RENDERER_MAP` + `DimensionRenderHelper`). `endFrame()` for all of them each frame. |
| `PortalSurfaceRenderType` | Pipeline + render type drawing a portal's mesh sampling its view texture. Used by the portal entity renderer and for global portals (submitted in `LevelRenderer.submitFeatures`). |
| `PortalClipping` | Front clipping: extends the `Globals` UBO with `vec4 ImmPtlClipPlane` (view space; w=0 plane disabled) and injects `discard` into world shaders. |

### Front clipping
- `GlobalSettingsUniform`: mixin grows the UBO by one vec4 and writes the current view's plane in `update`.
- Shader source hook: `ShaderManager.loadShader` / `loadInclude` (or `PipelineBuilder.loadShaderSource`). Port `ShaderCodeTransformation`:
  - override `include/globals.glsl` to add `vec4 ImmPtlClipPlane;` at the end of the block;
  - vertex shaders of world pipelines: after the line assigning `gl_Position = ProjMat * X`, add `immptl_ViewPos = X;` (output varying);
    X is already view-space (terrain: `TerrainUniform.ModelViewMat` = view rotation; entities: `DynamicTransforms.ModelViewMat`);
  - fragment shaders: `if (dot(ImmPtlClipPlane.xyz, immptl_ViewPos.xyz) + ImmPtlClipPlane.w < 0.0) discard;`.
  - Varyings need explicit `layout(location = N)`; pick N above the shader's highest used location.
- Shaders are Vulkan-style GLSL compiled to SPIR-V by shaderc; test on both backends.

### Culling and section visibility for views
- `SectionOcclusionGraph` is rooted at one camera per `LevelRenderer`. When rendering a portal view, skip `sectionOcclusionGraph.update` and the
  translucent resort / compile reprioritisation for that view (mixin checks `PortalViewContext.isRenderingPortalView()`), so the main view's graph is not disturbed.
- Visible sections for a view come from IP's own synchronous BFS (`VisibleSectionDiscovery`, ported), injected at `LevelExtractor.applyFrustum` /
  `SectionOcclusionGraph.addSectionsInFrustum` while in a view.

### Entities crossing a portal
An entity halfway through a portal is clipped by the portal plane: the original by the outer clipping plane
(the part that went through is hidden), its projection on the other side by the inner one. There is no per-entity
clip plane on the GPU (entities are batched per render type into one vertex buffer), so this is done on the CPU
(`EntityClipping`): extracted render states get their planes, the submits made while such a state is submitted
are tagged, and `RenderTypeFeatureRenderer` builds tagged submits with a vertex consumer that cuts every quad by
the planes (quads; triangle strips like leashes are clipped per triangle). Works the same with Sodium and Iris.

### Renderers and grids for portal views
A LevelRenderer's ViewArea is a fixed grid (the render distance around a center) and only shows the sections in it.
Moving it resets the sections that move in it, and `LevelExtractor.extract` looks up the view's sections before
`LevelRenderer.render` would move it (vanilla lags one frame; a view sharing a renderer would always use the grid of
the previous view). So `DimensionRenderHelper.selectForView` picks the renderer of each view before it is extracted:
a renderer whose grid covers the view camera (at least 4 sections from its edge) as it is; else the nearest renderer
of that dimension not used by another view in this frame, its grid moved to the camera now (`IELevelRenderer_ViewGrid`);
else a new extra renderer (up to 3 per dimension: own LevelRenderer and LevelExtractor on the same ClientLevel, which
forwards block changes to them, `IEClientWorld.ip_setExtraExtractors`; they share the dimension's lightmap and are
released after 600 frames without use); else the nearest one as it is (partial terrain, never grids moving every frame).
Before, two views of one dimension far apart moved one grid back and forth every frame and showed no terrain at all
(feature test "terrain behind a far portal"). The main view's renderer stays centered on the player and is never moved
by views. `LevelRenderer.repositionCamera` is skipped in all portal views.
All extractors of a level take the section emptiness and chunk load changes from the same ClientLevel, and each change
is given out once, so an extra renderer's extraction passes them on to the dimension's own renderer's graph
(MixinLevelRenderer `wrapSectionOcclusionGraphUpdate`); otherwise sections that got blocks stayed "empty" for the main
view and were not rendered (you could see through them).
The occlusion graph of a renderer is not updated in views (views use `VisibleSectionDiscovery`); it's rebuilt when that
renderer becomes the main one. The dirty sections of a view are collected around its camera, so some may be outside the
grid; they're skipped when compiling (`MixinLevelRenderer_ForceMainThreadRebuild`) and reset/compiled when the grid
moves over them. Not with Sodium, whose renderer is not bound to a grid. Visual test: `far_portal`, `far_portal_changed`.

How far a view is rendered: the server loads the other side of a portal less far than the render distance
(`ChunkVisibility`: the full render distance within 5 blocks of the portal, 2/3 within 15, 1/3 beyond; capped by
`indirectLoadingRadiusCap` (8) except within 5 blocks, and by the client's performance level). A view rendered to the
full render distance showed the sky past the loaded chunks, with a hard edge. So `PortalRenderer.getPortalRenderDistance`
limits a view to the terrain loaded around the portal's destination (`LoadedTerrainRadius`: the largest ring of chunks
that is at least 90% loaded, measured every 10 frames), and the view's fog is set at that distance
(`MixinGameRenderer.modifyFogRenderDistance`). Not for global portals. Feature tests: "terrain behind a far portal"
(render distance 12), and `-Pimm_ptl.featureTest.userWorld=<save dir> -Pimm_ptl.featureTest.userWorldPos="<dim> x y z yaw pitch"`
to open a copy of a reported world at the reported position.

Why terrain is missing in a view: `ViewDiagnostics` (`/imm_ptl_client_debug report_portal_views`, run twice) reports per
view the visible sections without a loaded chunk (chunk loading: the server loads less behind a portal the further the
player is from it, and much less at a low client performance level, `ChunkVisibility`), the unbuilt sections of loaded
chunks (section compiling) and the grid moves.

### Hidden portals
1.21.1 skipped portals hidden behind blocks with GL occlusion queries; renderpearl has none. `PortalOcclusionCulling`
uses vanilla's cave culling graph instead: `SectionOcclusionGraph` is a BFS from the camera section through section
faces that can see each other, independent of the camera rotation, so a section without a node can't be seen.
A portal of the main view whose box only touches sections without a node is skipped (no view, no surface).
It is rendered when unsure: the graph is being rebuilt (the camera moved), right after a teleport, global or huge portals,
portals nearer than 8 blocks, sections outside the grid. A portal found visible stays rendered for 10 more frames
(`PortalRenderInfo.lastVisibleFrame`). Only for the main view (portal views don't update the graph).
With Sodium (which replaces the graph): Sodium's culling result of the main view (`SodiumWorldRenderer.isBoxVisible`,
frustum and occlusion; sections without blocks count as visible). It is from the last frame or older (async culling),
so a portal is only skipped after it has been in the frustum for 10 frames in a row (else a portal turning into view
would be skipped for a moment). Switch: `IPCGlobal.cullHiddenPortals`. Feature test: "hidden portal culling" (also
with `-PwithSodium`).

### Cropped views
A view is rendered only for the screen rectangle its portal covers (`PortalViewCrop`), into a target of that size
(`PortalViewTargetPool`: sizes in 64 px steps, grow at once, shrink after 60 frames), at the window's pixel density,
so a small or far portal costs few pixels. "Screen space" is the window NDC of a view's camera (not cropped, not
flipped); it is the same for a view and the views behind its portals. A view's `Mapping` maps screen space to its
target's NDC (crop and mirror flip); it's applied to the extracted projection in `renderCurrentView`, and the portal
surface samples with the texture matrix `PortalViewCrop.samplingMatrix(parent, child)` (`TextureMat` in
`portal_view.vsh`, a `TextureTransform` per target). The rectangle: the portal's surface mesh projected with the
view's projection, view bobbing/damage tilt (`IEGameRenderer.ip_applyViewBobbing`, as `GameRenderer.renderLevel`) and
rotation, intersected with what the parent view shows (a portal outside it is skipped), 2 px margin, snapped to the
window's pixel grid. Full-size when a part of the portal is behind the camera or it covers more than 60% of the screen.
Off with a shaderpack (Iris' buffers are window-sized), in panorama mode, during the nausea/portal screen effect
(its skew isn't computed), and for cross-portal and GUI views. The cull frustum is not cropped. Switch:
`IPCGlobal.cropPortalViews`. Feature test: "hidden portal culling" (cropped view pixels, same image as full-size).

### Fewer updates for far portals
`FarPortalViewReuse`: a portal of the main view farther than 32 blocks covering less than 5% of the screen is rendered
every 2nd frame (beyond 64 blocks and under 2%: every 3rd); in between its last image is drawn again. Its target is kept
for it in the pool (`PortalViewTargetPool.acquire(..., keeper)` / `reuse`), with the mapping it was rendered with. Only
while the camera (position, and the screen clip matrix: projection, bobbing, rotation), the crop rectangle and the
portal (origin, axes, destination) are exactly as when it was rendered; otherwise the old image would be misaligned.
So it only saves GPU time when standing still. Needs cropped views (the rectangle). Config `reduceFarPortalUpdates`
(on in the Performance and Balanced presets). Feature test: "hidden portal culling" (far portal updates checks).

### Invalidating a renderer mid-frame
`LevelExtractor.extract` invalidates the compiled geometry when requested (allChanged), which recreates the
renderer's buffers; with Sodium that waits for their last use. If a portal view already used that renderer in this
frame (e.g. the main renderer, used by a view through a portal back into the player's dimension, right after a
dimension change), its buffers belong to the frame being recorded and waiting crashed ("Cannot wait on a fence for the
current submit"). So then the request is postponed to the start of the next frame (`MixinLevelExtractor`
`modifyShouldInvalidate`, `DimensionRenderHelper.processPostponedInvalidation`). A renderer counts as used after its
view is rendered, so a view's own first extraction still invalidates normally (postponing that left the new
dimension without terrain after a teleport). Not when the renderer has no ViewArea (just reset).

### Shaderpack uniforms in views
Iris measures the light at the eyes (eyeBrightness, eyeBrightnessM: cave fog, exposure) at the camera entity in the
level being rendered; in a portal view that's the player's position in the destination level (often underground), so
views got cave fog. `PortalViewEyeLight` (mixins `MixinIrisCommonUniforms`, `MixinIrisHardcodedCustomUniforms`)
measures it at the view camera. Biome uniforms still use the player. Portal view cameras use near plane 0.05 with a
shaderpack (Iris' fixed `near` uniform), 0.005 otherwise.

### Per-view buffer instances
- Fog: `GameRenderer.fogRenderer` is swapped to a pooled `FogRenderer` per view index; call `endFrame()` on all pooled instances each frame.
- Lightmap: one `Lightmap` per dimension; render it once per frame per dimension (first view that needs it).
- Clouds: `CloudRenderer` holds one cloud position per frame, so each portal view swaps a pooled `CloudRenderer` into its `LevelRenderer` (`IELevelRenderer_Clouds`), like the fog renderers. Otherwise views sharing a dimension with the main view or with each other overwrite its cloud offsets.
- Projection / globals: shared, written with `writeToBuffer` per view (safe).

## MVP scope (first playable build)
In: views for normal portals and one+ nesting levels, front clipping, portal surface drawing, per-dimension renderers, entities in views.
Out for now (TODO(26.3)): stencil-like exact portal-shape culling of view contents beyond front clipping,
Sodium/Iris, cross-portal entity rendering polish, GUI portal rendering, fuse-view/isometric edge cases.

Old classes to delete or reduce once replaced: `RendererUsingStencil`, `QueryManager`, `GlQueryObject`, `SecondaryFrameBuffer`,
`MixinGlStateManager`, `MixinRenderSystem_Clipping`, `framebuffer/MixinRenderTarget`, `framebuffer/MixinMainTarget`, stencil parts of `MyRenderHelper`/`ViewAreaRenderer`.

## Camera at the portal plane (walking through)
- The portal surface emulates GL depth clamp per fragment (`portal_view.vsh/.fsh`: z is set to 0.5w so only
  w>0 clips, and the real depth is written to gl_FragDepth). The mesh then covers exactly the rays that pass
  through the opening, at any camera distance, like the original mod's stencil + GL_DEPTH_CLAMP.
  Do not replace this with a full-screen quad or a per-vertex `min(z, w)`: both were tried and were wrong.
- Portal view cameras use a 0.005 near plane (the original mod used depth clamp inside views).
- The front clip plane is moved 0.01 towards the camera (upstream `FrontClipping.ADJUSTMENT`) to avoid
  1-pixel cracks along the portal edges.
- The portal layer is pushed before the view camera is set up, because preparing its cull frustum runs
  `FrustumCuller`, which reads the current portal.

## Shared render state and background threads
`GameRenderer.gameRenderState()` is shared by the main view and all portal views (they run sequentially).
Anything that reads it on another thread must get a snapshot: `SectionOcclusionGraph.scheduleFullUpdate`
reads the camera state asynchronously, so `MixinSectionOcclusionGraph` passes it a copy
(without it the main view intermittently lost its terrain).

## Visual test
`./gradlew runClientGameTest [-PipBackend=opengl|vulkan]` (Fabric client gametest, `src/gametest`) builds a
nether->overworld portal scene with coloured marker walls, takes screenshots from fixed camera poses around
and inside the portal plane plus a walk through it, and writes `build/visual-test/<backend>/*.png` and
`poses.csv` (with section-visibility diagnostics). Expected images are described in `PortalVisualTest`.

## Mirrors
A mirror view's rotation contains a reflection, which reverses triangle winding, so backface culling would
remove the front faces. A view whose combined camera transformation has a negative determinant
(`ViewNode.isMirrored`) is rendered with its projection flipped horizontally (on the extracted camera state in
`PortalViewRenderer.renderCurrentView`, the only source of the level projection, so Sodium gets it too),
which restores the winding. The image is then mirrored left-right, so a portal surface samples its view with x flipped
when exactly one of the two views (the one drawing the surface and the portal's own) is mirrored (part of the view's
`PortalViewCrop.Mapping`, see Cropped views). Nested mirrors and portals seen in mirrors follow from that rule.

## Global portals
Global portals (world wrapping, dimension stacks) are not in the level's entity list. Their surfaces are
extracted at the end of `LevelExtractor.extractVisibleEntities`, and their views are planned with the others.
On the client they are created from NBT with entity id 0, and in 26.3 `Entity.getId()`/`hashCode()`/`equals()`
throw for id 0, so `GlobalPortalStorage` gives them unique negative ids. Maps keyed by portals use identity.

## Sodium
Sodium replaces the terrain renderer; its `SodiumWorldRenderer` is attached per `LevelRenderer`, so each
dimension renderer has its own. Things the port does for it (`compat/mixin/sodium`, `OnSodiumPresent`):
- `LevelExtractor.setLevel` binds the level to the Sodium renderer of `Minecraft.levelRenderer`, so a dimension
  renderer is made current while its extractor's level is set (`DimensionRenderHelper.setExtractorLevel`).
- ImmPtl replaces the client chunk cache, so chunk loads are reported to Sodium's chunk tracker (`OnSodiumPresent`).
- Front clipping: the plane is appended to Sodium's `u_Globals` block (`sodium:globals.glsl` + `MixinSodiumGlobalUniforms`),
  and its terrain shader is transformed like vanilla's. The plane is part of the uniform record's equality, because
  `DynamicGpuDataStorageMapped` reuses the previous slot for equal data.
- Culling: Sodium culls asynchronously and keeps the results per renderer. Portal views skip that and collect their
  sections synchronously with Sodium's fallback traversal, and their camera doesn't count as a camera movement for
  the renderer (`MixinSodiumRenderSectionManager`, `MixinSodiumWorldRenderer`).
- A cull task still running when a renderer stops being the main view's (dimension change) is finished by its
  portal views (`MixinSodiumRenderSectionManager.onPrepareRenderTrees`): until its result is consumed, Sodium queues
  section adds/removes (QueuedSectionStorage safe read phase), so the renderer never got new chunks.
- Mirror views get the flipped projection because it is flipped on the extracted camera state, which Sodium reads.
Run the visual test with Sodium: `./gradlew runClientGameTest -PwithSodium` (output in `build/visual-test/<backend>-sodium`).

## Iris (shaders)
Iris runs shaderpacks only on the OpenGL backend. Without a shaderpack it behaves like Sodium.
With a shaderpack, each portal view runs the pack's pipeline for that view (the views render sequentially).
Front clipping for pack terrain programs: Iris compiles them from its own generated source, so
`MixinIrisTransformPatcher` post-processes the output of `TransformPatcher.patchSodium` (non-shadow programs):
the plane is added to Iris' `u_Globals` declaration (the buffer is Sodium's, which carries it), the clip distance
is computed from `getVertexPosition()` after the pack's `main`, and the fragment shader discards.
Pack programs of vanilla render types (entities, block entities, particles, clouds...) are post-processed the same way
after `TransformPatcher.patchVanilla`: the plane is added to Iris' `iris_Projection` block (bound to the vanilla
Projection buffer, which carries it), the view position is `ModelViewMat * (Position + ModelOffset)`.
Shadow, sky and hand programs are left alone. Programs missing from the pack use Iris' own fallback shaders, which are
not clipped. The test pack has `gbuffers_textured_lit` (entities fall back to it) for the `entity_front_ow` shot.
Run the visual test with the test shaderpack (`src/gametest/resources/immptl_test_shaderpack`, magenta border):
`./gradlew runClientGameTest -PwithShaders` (OpenGL only; Iris crashes when Vulkan is forced).
With a real pack: `-PshaderPack=<path to the zip>` (output in build/visual-test/opengl-pack-<name>).
Checked this way: Complementary Reimagined r5.9.3, BSL v10.1.8.
