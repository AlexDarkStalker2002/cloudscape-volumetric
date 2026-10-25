package dev.cloudscape.volumetric;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/** Development tool. Never learn/accept a new contract automatically at runtime. */
public final class ExportContract {
    public static void main(String[] args) throws Exception {
        try (var fs = FileSystems.newFileSystem(Path.of(args[0]), Map.of())) {
            var contract = ComplementaryContract.fingerprint(fs);
            StringBuilder output = new StringBuilder("# Complementary source interface, captured from tested r5.9.3.\n"
                + "# Archive name/version and cloud implementation bodies are not part of this contract.\n");
            contract.stringPropertyNames().stream().sorted().forEach(key ->
                output.append(key).append('=').append(contract.getProperty(key)).append('\n'));
            Files.writeString(Path.of(args[1]), output.toString());
            System.out.println("Exported " + contract.size() + " interface checks");
        }
    }
}
