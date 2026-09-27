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

### Per-view buffer instances
- Fog: `GameRenderer.fogRenderer` is swapped to a pooled `FogRenderer` per view index; call `endFrame()` on all pooled instances each frame.
- Lightmap: one `Lightmap` per dimension; render it once per frame per dimension (first view that needs it).
- Clouds: `CloudRenderer` is per `LevelRenderer` (per dimension) and single-slot. MVP: no clouds in portal views. (TODO: per-view cloud buffers.)
- Projection / globals: shared, written with `writeToBuffer` per view (safe).

## MVP scope (first playable build)
In: views for normal portals and one+ nesting levels, front clipping, portal surface drawing, per-dimension renderers, entities in views.
Out for now (TODO(26.3)): clouds in views, stencil-like exact portal-shape culling of view contents beyond front clipping,
occlusion-query based skipping, Sodium/Iris, cross-portal entity rendering polish, GUI portal rendering, fuse-view/isometric edge cases.

Old classes to delete or reduce once replaced: `RendererUsingStencil`, `QueryManager`, `GlQueryObject`, `SecondaryFrameBuffer`,
`MixinGlStateManager`, `MixinRenderSystem_Clipping`, `framebuffer/MixinRenderTarget`, `framebuffer/MixinMainTarget`, stencil parts of `MyRenderHelper`/`ViewAreaRenderer`.
