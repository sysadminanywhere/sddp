package ru.sddp.platform.service;

import org.junit.jupiter.api.Test;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class KubernetesOutputTest {
    private String archive(String name) throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes); var tar = new TarArchiveOutputStream(gzip)) {
            byte[] contents = "generated".getBytes(StandardCharsets.UTF_8);
            var entry = new TarArchiveEntry(name); entry.setSize(contents.length);
            tar.putArchiveEntry(entry); tar.write(contents); tar.closeArchiveEntry();
        }
        return "plugin log\nSDDP_OUTPUT_BEGIN\n" + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\nSDDP_OUTPUT_END\n";
    }
    @Test void readsGeneratedFiles() throws Exception { assertEquals("generated", KubernetesJobPluginExecutor.extract(archive("./client.java")).get("client.java")); }
    @Test void rejectsPathTraversal() throws Exception { String logs = archive("../outside"); assertThrows(IllegalArgumentException.class, () -> KubernetesJobPluginExecutor.extract(logs)); }
    @Test void rejectsMissingArchive() { assertThrows(IllegalArgumentException.class, () -> KubernetesJobPluginExecutor.extract("failed")); }
    @Test void rejectsOversizedEncodedArchive() {
        String logs = "SDDP_OUTPUT_BEGIN\n" + "A".repeat(6_666_669) + "\nSDDP_OUTPUT_END";
        assertThrows(IllegalArgumentException.class, () -> KubernetesJobPluginExecutor.extract(logs));
    }
    @Test void preservesOutputDirectoryName() throws Exception {
        assertEquals("generated", KubernetesJobPluginExecutor.extract(archive("./output/client.java")).get("output/client.java"));
    }
    @Test void rejectsSymbolicLinks() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes); var tar = new TarArchiveOutputStream(gzip)) {
            var entry = new TarArchiveEntry("link", org.apache.commons.compress.archivers.tar.TarConstants.LF_SYMLINK);
            entry.setLinkName("outside");
            tar.putArchiveEntry(entry); tar.closeArchiveEntry();
        }
        String logs = "SDDP_OUTPUT_BEGIN\n" + Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\nSDDP_OUTPUT_END";
        assertThrows(IllegalArgumentException.class, () -> KubernetesJobPluginExecutor.extract(logs));
    }
}
