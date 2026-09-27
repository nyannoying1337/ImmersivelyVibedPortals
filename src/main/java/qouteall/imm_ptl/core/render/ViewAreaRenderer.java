package qouteall.imm_ptl.core.render;

import net.minecraft.world.phys.Vec3;
import qouteall.q_misc_util.my_util.TriangleConsumer;

/**
 * Triangle output helpers used by the portal shapes to generate the portal view area mesh.
 * The mesh is drawn by {@link PortalSurfaceRendering} in 26.3
 * (the 1.21.1 immediate-mode portal area drawing with GL state is gone).
 */
public class ViewAreaRenderer {

    public static void outputTriangle(
        TriangleConsumer vertexOutput, Vec3 center,
        Vec3 localXAxis, Vec3 localYAxis,
        double p0x, double p0y, double p1x, double p1y, double p2x, double p2y
    ) {
        vertexOutput.accept(
            center.x + p0x * localXAxis.x() + p0y * localYAxis.x(),
            center.y + p0x * localXAxis.y() + p0y * localYAxis.y(),
            center.z + p0x * localXAxis.z() + p0y * localYAxis.z(),
            center.x + p1x * localXAxis.x() + p1y * localYAxis.x(),
            center.y + p1x * localXAxis.y() + p1y * localYAxis.y(),
            center.z + p1x * localXAxis.z() + p1y * localYAxis.z(),
            center.x + p2x * localXAxis.x() + p2y * localYAxis.x(),
            center.y + p2x * localXAxis.y() + p2y * localYAxis.y(),
            center.z + p2x * localXAxis.z() + p2y * localYAxis.z()
        );
    }

    public static void outputFullQuad(
        TriangleConsumer vertexOutput, Vec3 posInPlayerCoordinate,
        Vec3 localXAxis, Vec3 localYAxis
    ) {
        outputTriangle(
            vertexOutput, posInPlayerCoordinate,
            localXAxis, localYAxis, 1, 1, -1, 1, 1, -1
        );
        outputTriangle(
            vertexOutput, posInPlayerCoordinate,
            localXAxis, localYAxis, -1, 1, -1, -1, 1, -1
        );
    }
}
