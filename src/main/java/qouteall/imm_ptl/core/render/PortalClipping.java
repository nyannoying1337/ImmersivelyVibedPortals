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
    private static final Pattern MAIN_PATTERN = Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");

    private static final Vector4f currentPlane = new Vector4f(0, 0, 0, 0);

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

        // plane in camera-relative world coordinates: keep points p where n . (p + cameraPos - planePos) >= 0
        Vec3 normal = plane.normal();
        double d = normal.dot(cameraPos.subtract(plane.pos()));
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

    public static boolean shouldTransformVertexShader(Identifier vertexShaderId, @Nullable String vertexSource) {
        if (vertexSource == null) {
            return false;
        }
        if (EXCLUDED_VERTEX_SHADERS.contains(vertexShaderId.toString())) {
            return false;
        }
        return GL_POSITION_PATTERN.matcher(vertexSource).find();
    }

    public static String transformVertexShader(String source) {
        Matcher matcher = GL_POSITION_PATTERN.matcher(source);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String viewPosExpr = matcher.group(1);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(
                matcher.group(0) + "\n    immptl_ClipDistance = dot(ImmPtlClipPlane, " + viewPosExpr + ");"
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
