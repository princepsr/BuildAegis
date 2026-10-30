package com.riskscanner.dependencyriskanalyzer.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FileBrowseControllerTest {

    private final FileBrowseController controller = new FileBrowseController();

    @TempDir
    Path tmp;

    @Test
    void listsFoldersFirstThenBuildFilesAndSkipsOtherFiles() throws IOException {
        Files.createDirectory(tmp.resolve("zeta"));
        Files.createDirectory(tmp.resolve("Alpha"));
        Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        Files.writeString(tmp.resolve("build.gradle"), "");
        Files.writeString(tmp.resolve("notes.txt"), "x");

        var listing = controller.listDirectory(tmp.toString());

        assertEquals(tmp.toAbsolutePath().normalize().toString(), listing.path());
        assertEquals(tmp.getParent().toString(), listing.parent());
        var names = listing.entries().stream().map(FileBrowseController.FsEntry::name).toList();
        assertEquals(java.util.List.of("Alpha", "zeta", "build.gradle", "pom.xml"), names);
        assertTrue(listing.entries().get(0).directory());
        assertFalse(listing.entries().get(2).directory());
    }

    @Test
    void topLevelListsRootsWhenNoPath() throws IOException {
        var listing = controller.listDirectory("");
        assertEquals("", listing.path());
        assertNull(listing.parent());
        assertFalse(listing.entries().isEmpty());
    }

    @Test
    void rejectsPathThatIsNotAFolder() throws IOException {
        Path file = Files.writeString(tmp.resolve("pom.xml"), "<project/>");
        assertThrows(IllegalArgumentException.class, () -> controller.listDirectory(file.toString()));
        assertThrows(IllegalArgumentException.class, () -> controller.listDirectory(tmp.resolve("missing").toString()));
    }
}
