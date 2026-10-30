package com.riskscanner.dependencyriskanalyzer.controller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

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

    private static MockHttpServletRequest from(String remoteAddr) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddr);
        return request;
    }

    @Test
    void loopbackAddressesAreRecognized() {
        assertTrue(FileBrowseController.isLoopback(from("127.0.0.1")));
        assertTrue(FileBrowseController.isLoopback(from("::1")));
        assertTrue(FileBrowseController.isLoopback(from("0:0:0:0:0:0:0:1")));
    }

    @Test
    void remoteAddressesAreRejected() {
        assertFalse(FileBrowseController.isLoopback(from("192.168.1.20")));
        assertFalse(FileBrowseController.isLoopback(from("10.0.0.5")));
        assertFalse(FileBrowseController.isLoopback(from("8.8.8.8")));
        assertFalse(FileBrowseController.isLoopback(from("not-an-address")));
        assertFalse(FileBrowseController.isLoopback(null));
    }

    @Test
    void listingIsForbiddenForNonLocalRequests() {
        var response = controller.list(tmp.toString(), from("192.168.1.20"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }

    @Test
    void listingWorksForLocalRequests() throws IOException {
        Files.createDirectory(tmp.resolve("proj"));
        var response = controller.list(tmp.toString(), from("127.0.0.1"));
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void browseIsForbiddenForNonLocalRequests() throws Exception {
        var response = controller.browse(null, from("203.0.113.9"));
        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
    }
}
