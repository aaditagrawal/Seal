import static org.junit.Assert.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.IOException;
import org.gradle.api.GradleException;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class SealWebp16kTransformTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private Path directory;
    private Path manifest;

    private String fixture() throws IOException {
        directory = temporary.newFolder().toPath();
        manifest = temporary.newFile().toPath();
        for (String abi : SealWebp16kTransform.ABIS) {
            Files.createDirectories(directory.resolve(abi));
            for (String name : SealWebp16kTransform.NAMES) {
                // Metadata-only ELF fixture. It is never executed as a shared object.
                ByteBuffer elf = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
                elf.put(new byte[] {0x7f, 'E', 'L', 'F', 2, 1});
                elf.putShort(18, (short) (abi.equals("arm64-v8a") ? 183 : 62));
                elf.putLong(32, 64); elf.putShort(54, (short) 56); elf.putShort(56, (short) 1);
                elf.putInt(64, 1); elf.putLong(64 + 48, 16384);
                Files.write(directory.resolve(abi).resolve(name), elf.array());
            }
        }
        return rehash();
    }

    private String rehash() throws IOException {
        StringBuilder lines = new StringBuilder();
        for (String abi : SealWebp16kTransform.ABIS.stream().sorted().toList()) {
            for (String name : SealWebp16kTransform.NAMES.stream().sorted().toList()) {
                lines.append(SealWebp16kTransform.sha256(Files.readAllBytes(directory.resolve(abi).resolve(name))))
                        .append("  overlay/").append(abi).append('/').append(name).append('\n');
            }
        }
        byte[] bytes = lines.toString().getBytes(StandardCharsets.UTF_8);
        Files.write(manifest, bytes);
        return SealWebp16kTransform.sha256(bytes);
    }

    @Test public void completeVerifiedOverlayIsAccepted() throws IOException {
        String hash = fixture();
        assertEquals(10, SealWebp16kTransform.loadOverlay(directory, manifest, hash).size());
    }

    @Test public void corruptLibraryIsRejectedEvenWhenManifestIsUnchanged() throws IOException {
        String hash = fixture();
        Files.write(directory.resolve("x86_64/libwebp.so"), new byte[] {1, 2, 3});
        GradleException error = assertThrows(GradleException.class,
                () -> SealWebp16kTransform.loadOverlay(directory, manifest, hash));
        assertTrue(error.getMessage().contains("integrity failure"));
    }

    @Test public void missingLibraryIsRejected() throws IOException {
        String hash = fixture();
        Files.delete(directory.resolve("x86_64/libwebp.so"));
        assertThrows(GradleException.class, () -> SealWebp16kTransform.loadOverlay(directory, manifest, hash));
    }

    @Test public void changedManifestAndUnexpectedFilesAreRejected() throws IOException {
        String hash = fixture();
        assertThrows(GradleException.class, () -> SealWebp16kTransform.loadOverlay(directory, manifest, "0".repeat(64)));
        Files.write(directory.resolve("unexpected.so"), new byte[] {0});
        assertThrows(GradleException.class, () -> SealWebp16kTransform.loadOverlay(directory, manifest, hash));
    }

    @Test public void digestCorrectFourKSegmentsAndIncongruentOffsetsAreRejected() throws IOException {
        fixture();
        Path path = directory.resolve("x86_64/libwebp.so");
        byte[] data = Files.readAllBytes(path);
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(112, 4096);
        Files.write(path, data);
        String fourK = rehash();
        assertThrows(GradleException.class, () -> SealWebp16kTransform.loadOverlay(directory, manifest, fourK));
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).putLong(112, 16384).putLong(80, 4096);
        Files.write(path, data);
        String incongruent = rehash();
        assertThrows(GradleException.class, () -> SealWebp16kTransform.loadOverlay(directory, manifest, incongruent));
    }
}
