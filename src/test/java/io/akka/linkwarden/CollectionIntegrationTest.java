package io.akka.linkwarden;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R23–R32 — collections, who reaches them, and what a delete takes with it. */
class CollectionIntegrationTest extends SurfaceTestBase {

  private int create(Account account, String name) {
    JsonNode made = send("POST", "/api/v1/collections", account.token(), Map.of("name", name));
    assertEquals(200, status(made), "creating " + name + ": " + made);
    return made.get("response").get("id").asInt();
  }

  @Test
  void aCollectionCarriesItsCountItsParentAndItsMembers() {
    Account owner = register();
    int parent = create(owner, "Parent");
    JsonNode child =
        send(
            "POST",
            "/api/v1/collections",
            owner.token(),
            Map.of("name", "Child", "parentId", parent));
    assertEquals(200, status(child));

    JsonNode listed = send("GET", "/api/v1/collections", owner.token(), null);
    JsonNode collections = listed.get("response");
    assertTrue(collections.size() >= 2);
    for (JsonNode collection : collections) {
      assertNotNull(collection.get("_count").get("links"), "R29 — every row carries its count");
      assertTrue(collection.has("parent"), "R29 — and its parent, present or null");
      assertTrue(collection.get("members").isArray());
    }
  }

  @Test
  void theOrderIsTheCallersOwnAndANewCollectionJoinsTheEndOfIt() {
    Account owner = register();
    int first = create(owner, "First");
    int second = create(owner, "Second");

    JsonNode me = send("GET", "/api/v1/users/me", owner.token(), null);
    List<Integer> order = new java.util.ArrayList<>();
    me.get("response").get("collectionOrder").forEach(node -> order.add(node.asInt()));
    assertEquals(
        List.of(first, second),
        order.subList(order.size() - 2, order.size()),
        "R30 — a new collection is appended to the order rather than sorted into it");
  }

  @Test
  void aCollectionThatDoesNotExistAndOneOutOfReachAreTheSameAnswer() {
    Account owner = register();
    Account stranger = register();
    int mine = create(owner, "Mine");

    JsonNode missing = send("GET", "/api/v1/collections/999999", owner.token(), null);
    assertEquals(200, status(missing));
    assertTrue(missing.get("response").isNull(), "a collection that does not exist is a null");

    JsonNode notMine = send("GET", "/api/v1/collections/" + mine, stranger.token(), null);
    assertEquals(200, status(notMine));
    assertTrue(notMine.get("response").isNull(), "and so is one somebody else owns");
  }

  @Test
  void aSubCollectionUnderAParentThatDoesNotExistIsRefusedAsPermissionNotAsAbsence() {
    Account owner = register();
    JsonNode refusal =
        send(
            "POST",
            "/api/v1/collections",
            owner.token(),
            Map.of("name", "Orphan", "parentId", 999999));
    assertEquals(403, status(refusal), "R27 — 403, not 404");
    assertEquals(
        "You are not authorized to create a sub-collection here.", refusal.get("response").asText());
  }

  @Test
  void onlyTheOwnerMayRenameACollection() {
    Account owner = register();
    Account stranger = register();
    int mine = create(owner, "Mine");

    JsonNode refusal =
        send("PUT", "/api/v1/collections/" + mine, stranger.token(), Map.of("name", "Theirs"));
    assertEquals(401, status(refusal));
    assertEquals("Collection is not accessible.", refusal.get("response").asText());
  }

  @Test
  void membersAreReplacedWholesaleAndTheOwnersOwnRowIsDropped() {
    Account owner = register();
    Account member = register();
    int collection = create(owner, "Shared");

    JsonNode updated =
        send(
            "PUT",
            "/api/v1/collections/" + collection,
            owner.token(),
            Map.of(
                "name", "Shared",
                "members",
                List.of(
                    Map.of("userId", member.id(), "canCreate", true, "canUpdate", true, "canDelete", false),
                    Map.of("userId", member.id(), "canCreate", false, "canUpdate", false, "canDelete", false),
                    Map.of("userId", owner.id(), "canCreate", true, "canUpdate", true, "canDelete", true))));
    assertEquals(200, status(updated));

    JsonNode members = updated.get("response").get("members");
    assertEquals(1, members.size(), "R25 — the duplicate goes and the owner's own row goes");
    assertEquals(member.id(), members.get(0).get("userId").asInt());
    assertTrue(members.get(0).get("canCreate").asBoolean(), "R25 — the first of the duplicates wins");
    assertTrue(
        members.get(0).get("user").has("username"), "the member's own name travels with the row");
  }

  @Test
  void propagationReachesAGrandchildAndWithoutItDescendantsAreUntouched() {
    Account owner = register();
    Account member = register();
    int parent = create(owner, "Top");
    int child =
        send("POST", "/api/v1/collections", owner.token(), Map.of("name", "Middle", "parentId", parent))
            .get("response")
            .get("id")
            .asInt();
    int grandchild =
        send("POST", "/api/v1/collections", owner.token(), Map.of("name", "Bottom", "parentId", child))
            .get("response")
            .get("id")
            .asInt();

    send(
        "PUT",
        "/api/v1/collections/" + parent,
        owner.token(),
        Map.of(
            "name", "Top",
            "members", List.of(Map.of("userId", member.id(), "canCreate", true, "canUpdate", false, "canDelete", false))));
    JsonNode untouched = send("GET", "/api/v1/collections/" + grandchild, owner.token(), null);
    assertEquals(
        0,
        untouched.get("response").get("members").size(),
        "R26 — without the flag a descendant keeps its own members");

    send(
        "PUT",
        "/api/v1/collections/" + parent,
        owner.token(),
        Map.of(
            "name", "Top",
            "propagateToSubcollections", true,
            "members", List.of(Map.of("userId", member.id(), "canCreate", true, "canUpdate", false, "canDelete", false))));
    JsonNode reached = send("GET", "/api/v1/collections/" + grandchild, owner.token(), null);
    assertEquals(1, reached.get("response").get("members").size(), "R26 — every descendant, not only the children");
    assertEquals(member.id(), reached.get("response").get("members").get(0).get("userId").asInt());
  }

  @Test
  void aMemberLeavingDeletesOnlyTheirOwnMembership() {
    Account owner = register();
    Account member = register();
    int collection = create(owner, "Shared");
    send(
        "PUT",
        "/api/v1/collections/" + collection,
        owner.token(),
        Map.of(
            "name", "Shared",
            "members", List.of(Map.of("userId", member.id(), "canCreate", true, "canUpdate", true, "canDelete", true))));

    JsonNode left = send("DELETE", "/api/v1/collections/" + collection, member.token(), null);
    assertEquals(200, status(left));
    // The answer is the membership row that went, which is what the interface removes.
    assertEquals(member.id(), left.get("response").get("userId").asInt(), "R32");
    assertEquals(collection, left.get("response").get("collectionId").asInt());

    JsonNode stillThere = send("GET", "/api/v1/collections/" + collection, owner.token(), null);
    assertNotNull(stillThere.get("response").get("id"), "R32 — the owner still has it");
    JsonNode gone = send("GET", "/api/v1/collections/" + collection, member.token(), null);
    assertTrue(gone.get("response").isNull(), "R32 — the member no longer reaches it");
  }

  @Test
  void theOwnersDeleteReachesEveryDescendant() {
    Account owner = register();
    int parent = create(owner, "Top");
    int child =
        send("POST", "/api/v1/collections", owner.token(), Map.of("name", "Middle", "parentId", parent))
            .get("response")
            .get("id")
            .asInt();

    JsonNode deleted = send("DELETE", "/api/v1/collections/" + parent, owner.token(), null);
    assertEquals(200, status(deleted));
    assertEquals(parent, deleted.get("response").get("id").asInt(), "R32 — the record that went");
    assertEquals("Top", deleted.get("response").get("name").asText());

    assertTrue(
        send("GET", "/api/v1/collections/" + child, owner.token(), null).get("response").isNull(),
        "R32 — the child went with it");
  }
}
