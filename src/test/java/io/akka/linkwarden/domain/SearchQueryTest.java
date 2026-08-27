package io.akka.linkwarden.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.linkwarden.domain.SearchQuery.Token;
import java.util.List;
import org.junit.jupiter.api.Test;

/** SPEC-001 R60–R65: the query language a search box accepts. */
class SearchQueryTest {

  private static List<Token> parse(String query) {
    return SearchQuery.parse(query, 0);
  }

  @Test
  void plainWordsAreGeneralText() {
    assertEquals(
        List.of(new Token("general", "red", false), new Token("general", "balloon", false)),
        parse("red balloon"));
    assertEquals("red balloon", SearchQuery.freeText(parse("red balloon")));
  }

  @Test
  void quotesGroupWordsIntoOneToken() {
    assertEquals(List.of(new Token("name", "Blue Sky", false)), parse("name:\"Blue Sky\""));
    assertEquals(List.of(new Token("name", "Blue Sky", false)), parse("name:'Blue Sky'"));
  }

  @Test
  void aBackslashEscapesWhateverFollowsIt() {
    assertEquals(List.of(new Token("general", "a b", false)), parse("a\\ b"));
    assertEquals(List.of(new Token("general", "a\"b", false)), parse("a\\\"b"));
  }

  @Test
  void everyOneOfTheTenFieldsIsRecognised() {
    for (String field : SearchQuery.FIELDS) {
      assertEquals(List.of(new Token(field, "x", false)), parse(field + ":x"), field);
    }
  }

  @Test
  void aFieldWithNothingAfterTheColonIsGeneralText() {
    assertEquals(List.of(new Token("general", "name:", false)), parse("name:"));
  }

  @Test
  void anUnknownPrefixIsGeneralText() {
    assertEquals(List.of(new Token("general", "colour:red", false)), parse("colour:red"));
  }

  @Test
  void negationAppliesOnlyToARecognisedField() {
    assertEquals(List.of(new Token("name", "x", true)), parse("!name:x"));
    assertEquals(List.of(new Token("general", "!colour:red", false)), parse("!colour:red"));
    assertEquals(List.of(new Token("general", "!", false)), parse("!"));
  }

  @Test
  void aFilterLimitKeepsEveryGeneralTokenAndTruncatesTheRest() {
    List<Token> tokens = SearchQuery.parse("a b name:x url:y tag:z", 2);
    assertEquals(4, tokens.size());
    assertEquals("general", tokens.get(0).field());
    assertEquals("general", tokens.get(1).field());
    assertEquals("name", tokens.get(2).field());
    assertEquals("url", tokens.get(3).field());
  }

  @Test
  void theVisibilityFilterIsAlwaysFirst() {
    assertEquals(
        "(collectionOwnerId = 7) OR (collectionMemberIds = 7)",
        SearchQuery.filters(parse(""), 7, false).get(0));
    assertEquals("collectionIsPublic = true", SearchQuery.filters(parse(""), null, true).get(0));
  }

  @Test
  void aPinnedFilterIsAboutTheCallerAndFalseIsTheNegationOfTrue() {
    assertEquals("pinnedBy = 7", SearchQuery.filters(parse("pinned:true"), 7, false).get(1));
    assertEquals("NOT pinnedBy = 7", SearchQuery.filters(parse("pinned:false"), 7, false).get(1));
    assertEquals("NOT pinnedBy = 7", SearchQuery.filters(parse("!pinned:true"), 7, false).get(1));
    assertEquals("pinnedBy = 7", SearchQuery.filters(parse("!pinned:false"), 7, false).get(1));
  }

  @Test
  void publicFalseProducesNoFilterAtAll() {
    assertEquals(1, SearchQuery.filters(parse("public:false"), 7, false).size());
    assertEquals(2, SearchQuery.filters(parse("public:true"), 7, false).size());
  }

  @Test
  void aDateThatDoesNotParseProducesNoFilter() {
    assertEquals(1, SearchQuery.filters(parse("before:someday"), 7, false).size());
    assertEquals(1, SearchQuery.filters(parse("after:someday"), 7, false).size());
  }

  @Test
  void datesCompareWholeSecondsAndNegationFlipsStrictness() {
    long midnight = 1704067200L; // 2024-01-01T00:00:00Z
    assertEquals(
        "creationTimestamp < " + midnight,
        SearchQuery.filters(parse("before:2024-01-01"), 7, false).get(1));
    assertEquals(
        "creationTimestamp >= " + midnight,
        SearchQuery.filters(parse("!before:2024-01-01"), 7, false).get(1));
    assertEquals(
        "creationTimestamp > " + midnight,
        SearchQuery.filters(parse("after:2024-01-01T00:00:00Z"), 7, false).get(1));
    assertEquals(
        "creationTimestamp <= " + midnight,
        SearchQuery.filters(parse("!after:2024-01-01T00:00:00Z"), 7, false).get(1));
  }

  @Test
  void quotesAndBackslashesInAValueAreEscaped() {
    assertEquals("a\\\\b\\\"c", SearchQuery.escape("a\\b\"c"));
    assertTrue(
        SearchQuery.filters(parse("name:\"say \\\"hi\\\"\""), 7, false).get(1).contains("\\\""));
  }

  @Test
  void aCollectionFilterNamesTheCollectionsOwnName() {
    assertEquals(
        "collectionName = \"Alpha\"", SearchQuery.filters(parse("collection:Alpha"), 7, false).get(1));
    assertEquals(
        "NOT collectionName = \"Alpha\"",
        SearchQuery.filters(parse("!collection:Alpha"), 7, false).get(1));
  }

  @Test
  void generalTextNeverBecomesAFilter() {
    assertEquals(1, SearchQuery.filters(parse("red balloon"), 7, false).size());
  }
}
