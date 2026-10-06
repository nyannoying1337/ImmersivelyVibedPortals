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
 * Installs and enables the test shaderpack (src/gametest/resources/immptl_test_shaderpack),
 * or the shaderpack zip given by -Dimm_ptl.visualTest.shaderpack (gradle -PshaderPack=...).
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
        String packZip = System.getProperty("imm_ptl.visualTest.shaderpack", "");
        if (!packZip.isEmpty()) {
            enablePackZip(Path.of(packZip));
            return;
        }
        Path dir = Iris.getShaderpacksDirectory().resolve(NAME);
        try {
            // gradle -Pimm_ptl.visualTest.packFallback=true: without gbuffers_textured_lit, so entities etc. use Iris'
            // fallback programs
            boolean fallback = Boolean.getBoolean("imm_ptl.visualTest.packFallback");
            for (String file : FILES) {
                if (fallback && file.contains("gbuffers_textured_lit")) {
                    Files.deleteIfExists(dir.resolve(file));
                    continue;
                }
                Path target = dir.resolve(file);
                Files.createDirectories(target.getParent());
                try (InputStream in = IrisTestPack.class.getResourceAsStream("/immptl_test_shaderpack/" + file)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            select(NAME);
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void enablePackZip(Path zip) {
        try {
            Files.createDirectories(Iris.getShaderpacksDirectory());
            Files.copy(
                zip, Iris.getShaderpacksDirectory().resolve(zip.getFileName().toString()),
                StandardCopyOption.REPLACE_EXISTING
            );
            // gradle -Pimm_ptl.visualTest.packOptions=NAME=value,NAME=value: the pack's options (Iris reads <pack>.txt)
            String options = System.getProperty("imm_ptl.visualTest.packOptions", "");
            Path optionsFile = Iris.getShaderpacksDirectory().resolve(zip.getFileName().toString() + ".txt");
            if (options.isEmpty()) {
                Files.deleteIfExists(optionsFile);
            }
            else {
                Files.writeString(optionsFile, String.join("\n", options.split(",")) + "\n");
            }
            select(zip.getFileName().toString());
        }
        catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void select(String packName) throws IOException {
        Iris.getIrisConfig().setShaderPackName(packName);
        Iris.getIrisConfig().setShadersEnabled(true);
        Iris.getIrisConfig().save();
        Iris.reload();
    }
}
