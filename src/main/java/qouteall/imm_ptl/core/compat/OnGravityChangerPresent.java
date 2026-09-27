package qouteall.imm_ptl.core.compat;

import gravity_changer.api.GravityChangerAPI;
import gravity_changer.util.RotationUtil;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.apache.commons.lang3.Validate;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.q_misc_util.my_util.DQuaternion;

// Only loaded when the mod is present. Excluded from the build until its compat is ported to 26.3.
public class OnGravityChangerPresent extends GravityChangerInterface.Invoker {
    @Override
    public boolean isGravityChangerPresent() {
        return true;
    }
    
    @Override
    public Vec3 getEyeOffset(Entity entity) {
        return GravityChangerAPI.getEyeOffset(entity);
    }
    
    @Override
    public Direction getGravityDirection(Entity entity) {
        return GravityChangerAPI.getGravityDirection(entity);
    }
    
    @Override
    public Direction getBaseGravityDirection(Entity entity) {
        return GravityChangerAPI.getBaseGravityDirection(entity);
    }
    
    @Override
    public void setBaseGravityDirectionServer(Entity entity, Direction direction) {
        GravityChangerAPI.setBaseGravityDirection(entity, direction);
    }
    
    @Override
    public void setClientPlayerGravityDirection(Player player, Direction direction) {
        setClientPlayerGravityDirectionClientOnly(player, direction);
    }
    
    @Environment(EnvType.CLIENT)
    private void setClientPlayerGravityDirectionClientOnly(
        Player player, Direction direction
    ) {
        Validate.isTrue(Minecraft.getInstance().isSameThread());
        
        GravityChangerAPI.instantlySetClientBaseGravityDirection(player, direction);
    }
    
    @Nullable
    @Override
    public DQuaternion getExtraCameraRotation(Direction gravityDirection) {
        if (gravityDirection == Direction.DOWN) {
            return null;
        }
        
        return DQuaternion.fromMcQuaternion(RotationUtil.getWorldRotationQuaternion(gravityDirection));
    }
    
    @Override
    public Vec3 getWorldVelocity(Entity entity) {
        return GravityChangerAPI.getWorldVelocity(entity);
    }
    
    @Override
    public void setWorldVelocity(Entity entity, Vec3 newVelocity) {
        GravityChangerAPI.setWorldVelocity(entity, newVelocity);
    }
    
    @Override
    public Vec3 transformPlayerToWorld(Direction gravity, Vec3 vec3d) {
        return RotationUtil.vecPlayerToWorld(vec3d, gravity);
    }
    
    @Override
    public Vec3 transformWorldToPlayer(Direction gravity, Vec3 vec3d) {
        return RotationUtil.vecWorldToPlayer(vec3d, gravity);
    }
    
    @Override
    public Direction transformDirPlayerToWorld(Direction gravity, Direction direction) {
        return RotationUtil.dirPlayerToWorld(direction, gravity);
    }
    
    @Override
    public Direction transformDirWorldToPlayer(Direction gravity, Direction direction) {
        return RotationUtil.dirWorldToPlayer(direction, gravity);
    }
}
