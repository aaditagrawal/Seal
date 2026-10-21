import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.apache.commons.compress.archivers.zip.*;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.artifacts.transform.*;
import org.gradle.api.file.*;
import org.gradle.api.provider.*;
import org.gradle.api.tasks.*;

/** Changes only the exact FFmpeg artifact resolved from the dedicated reference configuration. */
public abstract class SealWebp16kTransform implements TransformAction<SealWebp16kTransform.Parameters> {
    static final Set<String> NAMES = Set.of(
            "libsharpyuv.so", "libwebp.so", "libwebpdecoder.so", "libwebpdemux.so", "libwebpmux.so");
    static final Set<String> ABIS = Set.of("arm64-v8a", "x86_64");

    public interface Parameters extends TransformParameters {
        @InputDirectory @PathSensitive(PathSensitivity.RELATIVE)
        DirectoryProperty getOverlayDirectory();
        @InputFile @PathSensitive(PathSensitivity.NONE)
        RegularFileProperty getOverlayManifest();
        @Input Property<String> getOverlayManifestSha256();
        @Input Property<String> getUpstreamSha256();
        @InputFile @PathSensitive(PathSensitivity.ABSOLUTE)
        RegularFileProperty getUpstreamArtifact();
    }

    @InputArtifact @PathSensitive(PathSensitivity.ABSOLUTE)
    public abstract Provider<FileSystemLocation> getInputArtifact();

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static Map<String, byte[]> loadOverlay(Path directory, Path manifest, String expectedManifest) throws IOException {
        byte[] manifestBytes = Files.readAllBytes(manifest);
        if (!sha256(manifestBytes).equals(expectedManifest)) {
            throw new GradleException("WebP overlay manifest changed; rebuild and review the pinned source and hashes.");
        }
        Map<String, byte[]> result = new HashMap<>();
        for (String line : new String(manifestBytes, java.nio.charset.StandardCharsets.UTF_8).lines().toList()) {
            if (!line.matches("[0-9a-f]{64}  overlay/[^/]+/[^/]+")) {
                throw new GradleException("Invalid WebP hash manifest entry");
            }
            String relative = line.substring(74); // SHA256 + two spaces + "overlay/"
            String[] parts = relative.split("/");
            if (!ABIS.contains(parts[0]) || !NAMES.contains(parts[1]) || result.containsKey(relative)) {
                throw new GradleException("Unexpected or duplicate WebP overlay path: " + relative);
            }
            Path file = directory.resolve(relative);
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file.getParent())) {
                throw new GradleException("WebP overlay must contain regular files: " + relative);
            }
            byte[] data = Files.readAllBytes(file);
            if (!sha256(data).equals(line.substring(0, 64))) {
                throw new GradleException("WebP overlay integrity failure: " + relative);
            }
            checkElf(data, parts[0]);
            result.put(relative, data);
        }
        Set<String> expected = new HashSet<>();
        for (String abi : ABIS) for (String name : NAMES) expected.add(abi + "/" + name);
        Set<String> actual = new HashSet<>();
        try (var paths = Files.walk(directory)) {
            paths.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS))
                    .forEach(p -> actual.add(directory.relativize(p).toString().replace(File.separatorChar, '/')));
        }
        if (!result.keySet().equals(expected) || !actual.equals(expected)) {
            throw new GradleException("Expected exactly ten WebP overlay files");
        }
        return result;
    }

    static void checkElf(byte[] data, String abi) {
        if (data.length < 64 || data[0] != 0x7f || data[1] != 'E' || data[2] != 'L' || data[3] != 'F'
                || data[4] != 2 || data[5] != 1) throw new GradleException("Expected little-endian ELF64 WebP library");
        ByteBuffer elf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int machine = Short.toUnsignedInt(elf.getShort(18));
        long offset = elf.getLong(32);
        int size = Short.toUnsignedInt(elf.getShort(54));
        int count = Short.toUnsignedInt(elf.getShort(56));
        if (machine != (abi.equals("arm64-v8a") ? 183 : 62) || offset < 64 || size < 56
                || count == 0 || offset > data.length - (long) size * count) {
            throw new GradleException("Invalid WebP ELF architecture or program header table");
        }
        int loads = 0;
        for (int i = 0; i < count; i++) {
            int p = Math.toIntExact(offset + (long) i * size);
            if (elf.getInt(p) != 1) continue;
            loads++;
            long fileOffset = elf.getLong(p + 8), address = elf.getLong(p + 16), alignment = elf.getLong(p + 48);
            if (alignment < 16384 || (alignment & (alignment - 1)) != 0
                    || Math.floorMod(address - fileOffset, 16384) != 0) {
                throw new GradleException("WebP ELF LOAD segments must support 16 KB pages");
            }
        }
        if (loads == 0) throw new GradleException("WebP ELF has no LOAD segments");
    }

    @Override public void transform(TransformOutputs outputs) {
        File input = getInputArtifact().get().getAsFile();
        try {
            // Identity, not a filename prefix: other modules may publish similarly named AARs.
            File upstream = getParameters().getUpstreamArtifact().get().getAsFile();
            if (!input.getCanonicalFile().equals(upstream.getCanonicalFile())) {
                outputs.file(input);
                return;
            }
            if (!input.getName().equals("ffmpeg-0.18.1.aar")
                    || !sha256(Files.readAllBytes(input.toPath())).equals(getParameters().getUpstreamSha256().get())) {
                throw new GradleException("FFmpeg AAR changed. Rebuild and review the WebP overlay before upgrading it.");
            }
            Map<String, byte[]> overlay = loadOverlay(getParameters().getOverlayDirectory().get().getAsFile().toPath(),
                    getParameters().getOverlayManifest().get().getAsFile().toPath(), getParameters().getOverlayManifestSha256().get());
            File output = outputs.file("ffmpeg-0.18.1.aar");
            int archives = 0;
            try (java.util.zip.ZipFile source = new java.util.zip.ZipFile(input);
                 ZipOutputStream dest = new ZipOutputStream(new FileOutputStream(output))) {
                Enumeration<? extends ZipEntry> entries = source.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    byte[] data;
                    try (InputStream in = source.getInputStream(entry)) { data = in.readAllBytes(); }
                    String abi = ABIS.stream().filter(a -> entry.getName().equals("jni/" + a + "/libffmpeg.zip.so")).findFirst().orElse(null);
                    if (abi != null) {
                        byte[] patched = patchNested(data, abi, overlay);
                        if (patched.length == data.length) throw new GradleException("FFmpeg extracts updates by archive size; patched size must change.");
                        data = patched;
                        archives++;
                    }
                    ZipEntry copy = new ZipEntry(entry);
                    copy.setCompressedSize(-1);
                    if (abi != null) { copy.setSize(data.length); CRC32 crc = new CRC32(); crc.update(data); copy.setCrc(crc.getValue()); }
                    dest.putNextEntry(copy); dest.write(data); dest.closeEntry();
                }
            }
            if (archives != 2) throw new GradleException("Expected both 64-bit FFmpeg archives.");
        } catch (GradleException e) { throw e; }
        catch (Exception e) { throw new GradleException("Cannot apply verified WebP 16 KB overlay", e); }
    }

    private static byte[] patchNested(byte[] source, String abi, Map<String, byte[]> overlay) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Set<String> replaced = new HashSet<>();
        try (org.apache.commons.compress.archivers.zip.ZipFile in = org.apache.commons.compress.archivers.zip.ZipFile.builder()
                     .setSeekableByteChannel(new SeekableInMemoryByteChannel(source)).get();
             ZipArchiveOutputStream out = new ZipArchiveOutputStream(bytes)) {
            Enumeration<ZipArchiveEntry> entries = in.getEntries();
            while (entries.hasMoreElements()) {
                ZipArchiveEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream content = in.getInputStream(entry)) { data = content.readAllBytes(); }
                String path = entry.getName();
                if (path.startsWith("usr/lib/") && NAMES.contains(path.substring(8))) {
                    if (!replaced.add(path.substring(8)) || entry.isUnixSymlink()) throw new GradleException("Duplicate or symlinked WebP library");
                    data = overlay.get(abi + "/" + path.substring(8));
                }
                // Central-directory entries retain Unix modes and symlink targets.
                ZipArchiveEntry copy = new ZipArchiveEntry(entry);
                copy.setSize(data.length); copy.setCompressedSize(-1);
                CRC32 crc = new CRC32(); crc.update(data); copy.setCrc(crc.getValue());
                out.putArchiveEntry(copy); out.write(data); out.closeArchiveEntry();
            }
        }
        if (!replaced.equals(NAMES)) throw new GradleException("Expected five WebP objects for " + abi);
        return bytes.toByteArray();
    }

    /** Gives the external packaged-APK verifier the original, untransformed reference AAR. */
    public abstract static class WriteReference extends DefaultTask {
        @InputFiles @PathSensitive(PathSensitivity.ABSOLUTE)
        public abstract ConfigurableFileCollection getUpstreamAar();
        @OutputFile public abstract RegularFileProperty getReferenceFile();
        @TaskAction public void write() throws IOException {
            File target = getReferenceFile().get().getAsFile();
            Files.createDirectories(target.toPath().getParent());
            Files.writeString(target.toPath(), getUpstreamAar().getSingleFile().getAbsolutePath());
        }
    }
}
