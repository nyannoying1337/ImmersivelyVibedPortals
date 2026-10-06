package qouteall.imm_ptl.core.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Front clipping for portal views: geometry on the near side of the portal's destination plane is discarded.
 * 26.3 has no clip planes, so this is done in the shaders (see docs/rendering-26.3.md):
 * <ul>
 *     <li>The Projection uniform block gets an extra {@code vec4 ImmPtlClipPlane} (view space).
 *     It's written for the "level" projection buffer and zero (disabled) for all others.</li>
 *     <li>World vertex shaders output {@code immptl_ClipDistance = dot(ImmPtlClipPlane, viewPos)}.</li>
 *     <li>The paired fragment shaders discard when it's negative.</li>
 * </ul>
 */
public class PortalClipping {
    // vertex output / fragment input location, above anything vanilla uses
    private static final int VARYING_LOCATION = 15;

    // shaders that must never be clipped
    private static final Set<String> EXCLUDED_VERTEX_SHADERS = Set.of(
        "minecraft:core/sky", "minecraft:core/stars", "minecraft:core/panorama",
        "minecraft:core/gui", "minecraft:core/screenquad", "minecraft:core/animate_sprite"
    );

    private static final Pattern GL_POSITION_PATTERN =
        Pattern.compile("gl_Position\\s*=\\s*ProjMat\\s*\\*\\s*([^;]+);");
    // Sodium's terrain shader (the plane is added to its u_Globals block, see transformSodiumGlobalsInclude)
    private static final Pattern SODIUM_GL_POSITION_PATTERN =
        Pattern.compile("gl_Position\\s*=\\s*u_ProjectionMatrix\\s*\\*\\s*([^;]+);");
    private static final Pattern MAIN_PATTERN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");

    private static final Vector4f currentPlane = new Vector4f(0, 0, 0, 0);
    
    private static final double CLIP_PLANE_OFFSET = 0.01;

    /**
     * Compute the clip plane of the portal view being rendered, in view space.
     * Must be called after the view's camera render state is extracted.
     */
    public static void setupForCurrentView() {
        currentPlane.set(0, 0, 0, 0);

        if (!PortalRendering.isRendering()) {
            return;
        }

        Plane plane = PortalRendering.getActiveClippingPlane();
        if (plane == null) {
            return;
        }

        CameraRenderState cameraState =
            Minecraft.getInstance().gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        Vec3 cameraPos = cameraState.pos;

        // plane in camera-relative world coordinates: keep points p where n . (p + cameraPos - planePos) >= 0.
        // The plane is moved slightly towards the camera (like the original mod's FrontClipping.ADJUSTMENT),
        // so that geometry exactly at the portal plane (e.g. the destination frame's faces) isn't cut,
        // which otherwise leaves 1-pixel cracks along the portal edges.
        Vec3 normal = plane.normal();
        double d = normal.dot(cameraPos.subtract(plane.pos())) + CLIP_PLANE_OFFSET;
        Vector4f relPlane = new Vector4f((float) normal.x, (float) normal.y, (float) normal.z, (float) d);

        // to view space: viewPos = V * relPos, so plane_view = transpose(inverse(V)) * plane_rel
        Matrix4f inverseView = new Matrix4f(cameraState.viewRotationMatrix).invert();
        inverseView.transformTranspose(relPlane);

        currentPlane.set(relPlane);
    }

    public static void resetAfterView() {
        currentPlane.set(0, 0, 0, 0);
    }

    public static Vector4fc getCurrentPlane() {
        return currentPlane;
    }

    // ---- shader source transformation ----

    public static String transformProjectionInclude(String source) {
        // add the plane after ProjMat inside the Projection block
        return source.replaceFirst(
            "(uniform\\s+Projection\\s*\\{[^}]*mat4\\s+ProjMat\\s*;)",
            "$1\n    vec4 ImmPtlClipPlane;"
        );
    }

    // The plane's name in Sodium's u_Globals block. A shader can include both blocks (Sodium's terrain with
    // "Improved Transparency" also includes minecraft:projection.glsl), and both are nameless (their members are
    // global), so the names must differ.
    private static final String SODIUM_CLIP_PLANE = "ImmPtlSodiumClipPlane";

    /**
     * Sodium's terrain shaders read their uniforms from the u_Globals block (sodium:globals.glsl);
     * the plane is appended to it, and Sodium's writer is extended accordingly (MixinSodiumGlobalUniforms).
     */
    public static String transformSodiumGlobalsInclude(String source) {
        return source.replaceFirst(
            "(uniform\\s+u_Globals\\s*\\{[^}]*?)(\\s*\\};)",
            "$1\n    vec4 " + SODIUM_CLIP_PLANE + ";$2"
        );
    }

    private static final Pattern VERSION_PATTERN = Pattern.compile("#version\\s+(\\d+)");
    private static final Pattern SODIUM_GLOBALS_LAST_FIELD_PATTERN = Pattern.compile("(bool\\s+u_UseRGSS\\s*;)");

    /**
     * Iris compiles shaderpack terrain programs from its own generated source (Sodium's vertex format,
     * Sodium's u_Globals block, which MixinSodiumGlobalUniforms fills with the plane).
     * Adds the clip distance to such a program: computed in the vertex shader from the vertex position
     * (independent of how the pack computes gl_Position), and a discard in the fragment shader.
     * The stages are linked into one program, so the varying matches by name and the u_Globals block is
     * extended in every stage.
     *
     * @param sources the patched sources by stage name ("VERTEX", "FRAGMENT", ...)
     * @return the transformed sources, or null if the program is not handled
     */
    public static @Nullable java.util.Map<String, String> transformIrisSodiumProgram(java.util.Map<String, String> sources) {
        String vertex = sources.get("VERTEX");
        if (vertex == null || !vertex.contains("getVertexPosition") || !vertex.contains("u_ModelViewMatrix")) {
            return null;
        }
        return transformIrisProgram(
            sources, SODIUM_GLOBALS_LAST_FIELD_PATTERN,
            "u_ModelViewMatrix * getVertexPosition()"
        );
    }

    // the vanilla Projection block as Iris declares it (IrisBindings binds it to the vanilla buffer, which has the plane)
    private static final Pattern IRIS_PROJECTION_BLOCK_PATTERN =
        Pattern.compile("(uniform\\s+iris_Projection\\s*\\{\\s*mat4\\s+iris_ProjMat\\s*;)");

    /**
     * Like {@link #transformIrisSodiumProgram}, for the shaderpack programs of vanilla render types
     * (entities, block entities, particles, clouds...). The plane comes from the vanilla Projection block
     * (iris_Projection), the view space position is computed like the vanilla shaders do.
     * Shadow and sky programs are not transformed.
     *
     * @param name the Iris shader key name (e.g. "entities_cutout", "shadow_entities_cutout")
     */
    public static @Nullable java.util.Map<String, String> transformIrisVanillaProgram(
        String name, java.util.Map<String, String> sources
    ) {
        if (name.startsWith("shadow") || name.contains("_shadow") || name.startsWith("sky") || name.startsWith("hand")) {
            return null;
        }
        String vertex = sources.get("VERTEX");
        if (vertex == null || !vertex.contains("iris_Position") || !vertex.contains("iris_transforms")) {
            return null;
        }
        return transformIrisProgram(
            sources, IRIS_PROJECTION_BLOCK_PATTERN,
            "iris_transforms.ModelViewMat * vec4(iris_Position + iris_transforms.ModelOffset, 1.0)"
        );
    }

    // the vanilla Projection block as Iris' fallback shaders (ShaderSynthesizer) declare it
    private static final Pattern IRIS_FALLBACK_PROJECTION_BLOCK_PATTERN =
        Pattern.compile("(uniform\\s+Projection\\s*\\{\\s*mat4\\s+ProjMat\\s*;)");

    /**
     * Like {@link #transformIrisVanillaProgram}, for the programs Iris synthesizes when a shaderpack has no program
     * for a render type (Iris' ShaderSynthesizer: vanilla uniform names, {@code Position + ModelOffset}).
     */
    public static @Nullable java.util.Map<String, String> transformIrisFallbackProgram(java.util.Map<String, String> sources) {
        String vertex = sources.get("VERTEX");
        if (vertex == null || !vertex.contains("ModelViewMat") || !vertex.contains("ModelOffset")) {
            return null;
        }
        return transformIrisProgram(
            sources, IRIS_FALLBACK_PROJECTION_BLOCK_PATTERN,
            "ModelViewMat * vec4(Position + ModelOffset, 1.0)"
        );
    }

    /**
     * @param blockFieldPattern matches the last field of the uniform block that the plane is appended to
     *                          (group 1 is kept)
     * @param viewPosExpression the view space position of the vertex (vec4)
     */
    private static @Nullable java.util.Map<String, String> transformIrisProgram(
        java.util.Map<String, String> sources, Pattern blockFieldPattern, String viewPosExpression
    ) {
        String vertex = sources.get("VERTEX");
        String fragment = sources.get("FRAGMENT");
        if (vertex == null || fragment == null) {
            return null;
        }
        // the clip distance would have to be passed through the other stages
        for (var e : sources.entrySet()) {
            if (e.getValue() != null && !e.getKey().equals("VERTEX") && !e.getKey().equals("FRAGMENT")) {
                return null;
            }
        }
        if (vertex.contains("immptl_ClipDistance")
            || !blockFieldPattern.matcher(vertex).find()
            || !MAIN_PATTERN.matcher(vertex).find() || !MAIN_PATTERN.matcher(fragment).find()
        ) {
            return null;
        }

        java.util.Map<String, String> result = new java.util.HashMap<>();
        for (var e : sources.entrySet()) {
            String source = e.getValue();
            if (source != null) {
                source = blockFieldPattern.matcher(source).replaceFirst("$1\n    vec4 ImmPtlClipPlane;");
            }
            result.put(e.getKey(), source);
        }

        boolean modern = getGlslVersion(vertex) >= 130;

        // wrap the pack's main, so the clip distance is written after it (and after Iris' vertex init)
        String v = result.get("VERTEX");
        v = insertBeforeMain(v, (modern ? "out" : "varying") + " float immptl_ClipDistance;\n");
        v = MAIN_PATTERN.matcher(v).replaceFirst("void immptl_originalMain() {");
        v = v + "\nvoid main() {\n    immptl_originalMain();\n"
            + "    immptl_ClipDistance = dot(ImmPtlClipPlane, " + viewPosExpression + ");\n}\n";
        result.put("VERTEX", v);

        String f = result.get("FRAGMENT");
        f = insertBeforeMain(f, (getGlslVersion(f) >= 130 ? "in" : "varying") + " float immptl_ClipDistance;\n");
        Matcher fMain = MAIN_PATTERN.matcher(f);
        fMain.find();
        f = f.substring(0, fMain.end()) + "\n    if (immptl_ClipDistance < 0.0) { discard; }\n" + f.substring(fMain.end());
        result.put("FRAGMENT", f);

        return result;
    }

    private static int getGlslVersion(String source) {
        Matcher m = VERSION_PATTERN.matcher(source);
        return m.find() ? Integer.parseInt(m.group(1)) : 110;
    }

    private static Pattern getGlPositionPattern(Identifier vertexShaderId) {
        return vertexShaderId.getNamespace().equals("sodium") ? SODIUM_GL_POSITION_PATTERN : GL_POSITION_PATTERN;
    }

    public static boolean shouldTransformVertexShader(Identifier vertexShaderId, @Nullable String vertexSource) {
        if (vertexSource == null) {
            return false;
        }
        if (EXCLUDED_VERTEX_SHADERS.contains(vertexShaderId.toString())) {
            return false;
        }
        return getGlPositionPattern(vertexShaderId).matcher(vertexSource).find();
    }

    public static String transformVertexShader(Identifier vertexShaderId, String source) {
        String planeName = vertexShaderId.getNamespace().equals("sodium") ? SODIUM_CLIP_PLANE : "ImmPtlClipPlane";
        Matcher matcher = getGlPositionPattern(vertexShaderId).matcher(source);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String viewPosExpr = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                matcher.group(0) + "\n    immptl_ClipDistance = dot(" + planeName + ", " + viewPosExpr + ");"
            ));
        }
        matcher.appendTail(sb);

        return insertBeforeMain(
            sb.toString(),
            "layout(location = " + VARYING_LOCATION + ") out float immptl_ClipDistance;\n"
        );
    }

    public static String transformFragmentShader(String source) {
        String withInput = insertBeforeMain(
            source,
            "layout(location = " + VARYING_LOCATION + ") in float immptl_ClipDistance;\n"
        );
        Matcher matcher = MAIN_PATTERN.matcher(withInput);
        if (!matcher.find()) {
            return source;
        }
        return withInput.substring(0, matcher.end())
            + "\n    if (immptl_ClipDistance < 0.0) { discard; }\n"
            + withInput.substring(matcher.end());
    }

    private static String insertBeforeMain(String source, String declaration) {
        Matcher matcher = MAIN_PATTERN.matcher(source);
        if (!matcher.find()) {
            return source;
        }
        return source.substring(0, matcher.start()) + declaration + "\n" + source.substring(matcher.start());
    }
}
