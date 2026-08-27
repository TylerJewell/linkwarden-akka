package io.akka.linkwarden.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.linkwarden.application.FileStore;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SPEC-001 R84–R86, R88 — where a preserved format lives and what a reader is told it is. */
class FileStoreTest {

  @TempDir static java.nio.file.Path root;

  private FileStore store() {
    return new FileStore(new Config(Map.of("STORAGE_FOLDER", root.toString())));
  }

  @Test
  void eachFormatHasItsOwnSuffixAndTheSixthHasNone() {
    assertEquals(".png", FilePaths.suffix(0), "R84");
    assertEquals(".jpeg", FilePaths.suffix(1));
    assertEquals(".pdf", FilePaths.suffix(2));
    assertEquals("_readability.json", FilePaths.suffix(3));
    assertEquals(".html", FilePaths.suffix(4));
    assertNull(FilePaths.suffix(5), "R84 — a number outside the five is not a format");
  }

  @Test
  void aPreviewSitsUnderItsOwnFolder() {
    assertEquals("archives/7/12.pdf", FilePaths.archive(7, 12, 2), "R84");
    assertEquals("archives/preview/7/12.jpeg", FilePaths.preview(7, 12));
    assertEquals("uploads/avatar/3.jpg", FilePaths.avatar(3));
  }

  @Test
  void theContentTypeComesFromTheSuffixAndFallsBackToAJpeg() {
    assertEquals("application/pdf", FilePaths.contentType("archives/1/2.pdf"), "R84");
    assertEquals("image/png", FilePaths.contentType("archives/1/2.png"));
    assertEquals("application/json", FilePaths.contentType("archives/1/2_readability.json"));
    assertEquals("text/html", FilePaths.contentType("archives/1/2.html"));
    assertEquals("image/jpeg", FilePaths.contentType("archives/1/2.anything"), "R84 — the default");
  }

  @Test
  void aDownloadIsNamedByItsFormat() {
    assertEquals("Webpage.html", FilePaths.downloadFilename(4, "archives/1/2.html"), "R88");
    assertEquals("PDF.pdf", FilePaths.downloadFilename(2, "archives/1/2.pdf"));
    assertEquals("Screenshot.png", FilePaths.downloadFilename(0, "archives/1/2.png"));
    assertEquals("Screenshot.jpeg", FilePaths.downloadFilename(1, "archives/1/2.jpeg"));
    assertEquals("Readable.json", FilePaths.downloadFilename(3, "archives/1/2_readability.json"));
  }

  @Test
  void removingALinksFilesReachesAllSevenPaths() {
    List<String> paths = FilePaths.allOf(4, 9);
    assertEquals(7, paths.size(), "R85");
    assertTrue(paths.contains("archives/4/9.jpg"), "R85 — the older spelling as well as the newer");
    assertTrue(paths.contains("archives/4/9.jpeg"));
    assertTrue(paths.contains("archives/preview/4/9.jpeg"));

    FileStore store = store();
    for (String path : paths) store.write(path, "x".getBytes(StandardCharsets.UTF_8));
    store.removeLinkFiles(4, 9);
    for (String path : paths) assertFalse(store.exists(path), path + " survived");
  }

  @Test
  void aMovedLinkTakesTheSameSevenPathsWithIt() {
    FileStore store = store();
    store.write(FilePaths.archive(1, 5, 2), "a pdf".getBytes(StandardCharsets.UTF_8));
    store.write(FilePaths.preview(1, 5), "a preview".getBytes(StandardCharsets.UTF_8));

    store.moveLinkFiles(5, 1, 2);

    assertFalse(store.exists(FilePaths.archive(1, 5, 2)), "R85");
    assertTrue(store.exists(FilePaths.archive(2, 5, 2)));
    assertTrue(store.exists(FilePaths.preview(2, 5)));
  }

  @Test
  void aFileThatIsNotThereIsAnOrdinaryAnswerRatherThanAFailure() {
    FileStore.Stored missing = store().read("archives/99/99.pdf");
    assertEquals(404, missing.status(), "R86");
    assertEquals("File not found.", new String(missing.bytes(), StandardCharsets.UTF_8));
  }

  @Test
  void aPathThatClimbsOutOfTheStorageFolderIsRefused() {
    assertThrows(
        IllegalArgumentException.class,
        () -> store().read("../../etc/passwd"),
        "a path is resolved inside the storage folder or not at all");
  }
}
