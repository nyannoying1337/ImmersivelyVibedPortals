package qouteall.imm_ptl.gametest;

import net.irisshaders.iris.Iris;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Installs and enables the test shaderpack (src/gametest/resources/immptl_test_shaderpack).
 * Only loaded when Iris is present.
 */
public class IrisTestPack {
    static final String NAME = "ImmPtlTestPack";
    static final List<String> FILES = List.of(
        "shaders/final.vsh", "shaders/final.fsh",
        "shaders/gbuffers_terrain.vsh", "shaders/gbuffers_terrain.fsh",
        // (entities, block entities, particles... fall back to it)
        "shaders/gbuffers_textured_lit.vsh", "shaders/gbuffers_textured_lit.fsh"
    );
    
    static void enable() {
        Path dir = Iris.getShaderpacksDirectory().resolve(NAME);
        try {
            for (String file : FILES) {
                Path target = dir.resolve(file);
                Files.createDirectories(target.getParent());
                try (InputStream in = IrisTestPack.class.getResourceAsStream("/immptl_test_shaderpack/" + file)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Iris.getIrisConfig().setShaderPackName(NAME);
            Iris.getIrisConfig().setShadersEnabled(true);
            Iris.getIrisConfig().save();
            Iris.reload();
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
