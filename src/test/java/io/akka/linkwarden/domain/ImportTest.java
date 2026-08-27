package io.akka.linkwarden.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.linkwarden.domain.Importers.Plan;
import io.akka.linkwarden.domain.Importers.PlannedLink;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R78–R81: what each of the five import formats is read as. */
class ImportTest {

  private static final String BOOKMARKS =
      "<!DOCTYPE NETSCAPE-Bookmark-file-1><DL><p>"
          + "<DT><H3>Folder</H3><DL><p>"
          + "<DT><A HREF=\"https://example.com/one\" ADD_DATE=\"1600000000\" TAGS=\"a,b\">One</A>"
          + "<DD>described"
          + "<DT><A HREF=\"https://example.com/two\">Two</A>"
          + "</DL><p>"
          + "<DT><A HREF=\"https://example.com/loose\">Loose</A>"
          + "<DT><A HREF=\"notaurl\">Broken</A>"
          + "</DL><p>";

  private static PlannedLink named(Plan plan, String name) {
    return plan.links().stream().filter(l -> name.equals(l.name())).findFirst().orElseThrow();
  }

  @Test
  void aFolderBecomesACollectionAndItsLinksGoInside() {
    Plan plan = Importers.fromHtml(BOOKMARKS);
    assertTrue(plan.collections().stream().anyMatch(c -> c.name().equals("Folder")));
    int folder =
        plan.collections().indexOf(
            plan.collections().stream()
                .filter(c -> c.name().equals("Folder"))
                .findFirst()
                .orElseThrow());
    assertEquals(folder, named(plan, "One").collectionIndex());
    assertEquals(folder, named(plan, "Two").collectionIndex());
  }

  @Test
  void aLooseLinkGoesToACollectionCalledImports() {
    Plan plan = Importers.fromHtml(BOOKMARKS);
    int imports =
        plan.collections().indexOf(
            plan.collections().stream()
                .filter(c -> c.name().equals("Imports"))
                .findFirst()
                .orElseThrow());
    assertEquals(imports, named(plan, "Loose").collectionIndex());
  }

  @Test
  void aUrlThatDoesNotParseIsSkipped() {
    Plan plan = Importers.fromHtml(BOOKMARKS);
    assertFalse(plan.links().stream().anyMatch(l -> "Broken".equals(l.name())));
    assertEquals(3, plan.linkCount());
  }

  @Test
  void theAddDateIsSecondsAndTheTagAttributeIsCommaSeparated() {
    PlannedLink one = named(Importers.fromHtml(BOOKMARKS), "One");
    assertEquals(Instant.ofEpochSecond(1_600_000_000L), one.importDate());
    assertEquals(List.of("a", "b"), one.tags());
    assertNull(named(Importers.fromHtml(BOOKMARKS), "Two").importDate());
  }

  @Test
  void aBookmarkFileImportsWithNoDescription() {
    // The description is read from a DD *inside* the anchor, and an HTML parser puts a DD
    // beside the anchor whichever way the file writes it — so the field is always empty,
    // even for the bookmark that carries one.
    assertEquals("", named(Importers.fromHtml(BOOKMARKS), "One").description());
    assertEquals("", named(Importers.fromHtml(BOOKMARKS), "Two").description());
    assertEquals(
        "",
        Importers.fromHtml("<DL><DT><A HREF=\"https://example.com/d\">D<DD>inside</DD></A></DL>")
            .links()
            .get(0)
            .description());
  }

  @Test
  void entitiesAreDecodedInTheUrlAndTheTags() {
    Plan plan =
        Importers.fromHtml(
            "<DL><DT><A HREF=\"https://example.com/?a=1&amp;b=2\" TAGS=\"x&amp;y\">E</A></DL>");
    assertEquals("https://example.com/?a=1&b=2", plan.links().get(0).url());
    assertEquals(List.of("x&y"), plan.links().get(0).tags());
  }

  @Test
  void anEmptyFolderNameBecomesUntitledCollection() {
    Plan plan =
        Importers.fromHtml(
            "<DL><DT><H3></H3><DL><DT><A HREF=\"https://example.com/a\">A</A></DL></DL>");
    assertEquals("Untitled Collection", plan.collections().get(0).name());
  }

  @Test
  void aFolderOfTheSameNameIsReusedRatherThanCreatedTwice() {
    Plan plan =
        Importers.fromHtml(
            "<DL>"
                + "<DT><H3>Same</H3><DL><DT><A HREF=\"https://example.com/a\">A</A></DL>"
                + "<DT><H3>Same</H3><DL><DT><A HREF=\"https://example.com/b\">B</A></DL>"
                + "</DL>");
    assertEquals(1, plan.collections().size());
    assertEquals(2, plan.linkCount());
  }

  @Test
  void nestedFoldersNestCollections() {
    Plan plan =
        Importers.fromHtml(
            "<DL><DT><H3>Outer</H3><DL>"
                + "<DT><H3>Inner</H3><DL><DT><A HREF=\"https://example.com/a\">A</A></DL>"
                + "</DL></DL>");
    assertEquals(2, plan.collections().size());
    assertEquals("Outer", plan.collections().get(0).name());
    assertEquals("Inner", plan.collections().get(1).name());
    assertEquals(0, plan.collections().get(1).parentIndex());
  }

  @Test
  void pocketSplitsTagsOnBarsAndReadsSecondsAndSkipsBadRows() {
    Plan plan =
        Importers.fromPocket(
            "title,url,time_added,tags,status\n"
                + "Pocket One,https://example.com/p1,1600000000,x|y,unread\n"
                + "Bad,notaurl,,,\n");
    assertEquals(1, plan.linkCount());
    assertEquals("Imports", plan.collections().get(0).name());
    assertEquals(List.of("x", "y"), plan.links().get(0).tags());
    assertEquals(Instant.ofEpochSecond(1_600_000_000L), plan.links().get(0).importDate());
  }

  @Test
  void pocketToleratesQuotedFieldsHoldingCommas() {
    Plan plan =
        Importers.fromPocket(
            "title,url,time_added,tags,status\n"
                + "\"One, two\",https://example.com/p1,,,\n");
    assertEquals("One, two", plan.links().get(0).name());
  }

  @Test
  void wallabagPinsStarredItemsAndKeepsTheirText() throws Exception {
    Plan plan =
        Importers.fromWallabag(
            "[{\"url\":\"https://example.com/w1\",\"title\":\"W1\",\"is_starred\":1,"
                + "\"tags\":[\"w\"],\"content\":\"body\"},"
                + "{\"url\":\"notaurl\",\"title\":\"skip\"}]");
    assertEquals(1, plan.linkCount());
    assertEquals("Imports", plan.collections().get(0).name());
    assertTrue(plan.links().get(0).pinned());
    assertEquals("body", plan.links().get(0).textContent());
  }

  @Test
  void omnivoreLabelsAreTagsAndItsThumbnailIsTheImage() throws Exception {
    Plan plan =
        Importers.fromOmnivore(
            "[{\"url\":\"https://example.com/o1\",\"title\":\"O1\",\"description\":\"d\","
                + "\"labels\":[\"o\"],\"thumbnail\":\"https://img\","
                + "\"savedAt\":\"2024-01-01T00:00:00.000Z\"}]");
    assertEquals("Omnivore Imports", plan.collections().get(0).name());
    assertEquals(List.of("o"), plan.links().get(0).tags());
    assertEquals("https://img", plan.links().get(0).image());
    assertEquals(Instant.parse("2024-01-01T00:00:00Z"), plan.links().get(0).importDate());
  }

  @Test
  void linkwardensOwnBackupKeepsCollectionsAndPinsByUrl() throws Exception {
    Plan plan =
        Importers.fromLinkwarden(
            "{\"pinnedLinks\":[{\"url\":\"https://example.com/a\"}],"
                + "\"collections\":[{\"name\":\"Kept\",\"links\":["
                + "{\"url\":\"https://example.com/a\",\"name\":\"A\",\"tags\":[{\"name\":\"t\"}],"
                + "\"createdAt\":\"2024-01-01T00:00:00.000Z\"},"
                + "{\"url\":\"https://example.com/b\",\"name\":\"B\",\"tags\":[]}]}]}");
    assertEquals(List.of("Kept"), plan.collections().stream().map(c -> c.name()).toList());
    assertTrue(named(plan, "A").pinned());
    assertFalse(named(plan, "B").pinned());
    assertEquals(List.of("t"), named(plan, "A").tags());
  }

  @Test
  void everyImporterTruncatesToTheSameLengths() throws Exception {
    String longUrl = "https://example.com/" + "a".repeat(3000);
    Plan plan =
        Importers.fromOmnivore(
            "[{\"url\":\""
                + longUrl
                + "\",\"title\":\""
                + "n".repeat(400)
                + "\",\"description\":\""
                + "d".repeat(3000)
                + "\",\"labels\":[\""
                + "t".repeat(80)
                + "\"]}]");
    assertEquals(2047, plan.links().get(0).url().length());
    assertEquals(254, plan.links().get(0).name().length());
    assertEquals(2047, plan.links().get(0).description().length());
    assertEquals(49, plan.links().get(0).tags().get(0).length());
  }
}
