package io.akka.linkwarden.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.akka.linkwarden.domain.Permissions.Member;
import io.akka.linkwarden.domain.Permissions.Subject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** SPEC-001 R23–R28: who may do what, and what a chain of parents grants. */
class PermissionTest {

  private static final int ALICE = 1;
  private static final int BOB = 2;
  private static final int CAROL = 3;

  private static Subject owned(int id, Member... members) {
    return new Subject(id, ALICE, List.of(members));
  }

  @Test
  void theOwnerHoldsAllFourRights() {
    Subject subject = owned(1);
    assertTrue(Permissions.canRead(subject, ALICE));
    assertTrue(Permissions.canCreate(subject, ALICE));
    assertTrue(Permissions.canUpdate(subject, ALICE));
    assertTrue(Permissions.canDelete(subject, ALICE));
  }

  @Test
  void aMemberHoldsExactlyWhatTheirRowNames() {
    Subject subject = owned(1, new Member(BOB, true, false, false));
    assertTrue(Permissions.canRead(subject, BOB));
    assertTrue(Permissions.canCreate(subject, BOB));
    assertFalse(Permissions.canUpdate(subject, BOB));
    assertFalse(Permissions.canDelete(subject, BOB));
  }

  @Test
  void aStrangerHoldsNothing() {
    Subject subject = owned(1, new Member(BOB, true, true, true));
    assertFalse(Permissions.canRead(subject, CAROL));
    assertFalse(Permissions.canCreate(subject, CAROL));
  }

  @Test
  void aCollectionThatIsNotThereGrantsNothing() {
    assertFalse(Permissions.canRead(null, ALICE));
    assertFalse(Permissions.canCreateSubCollection(null, ALICE));
  }

  @Test
  void readingIsGrantedByAnyMembershipRowHoweverEmpty() {
    Subject subject = owned(1, new Member(BOB, false, false, false));
    assertTrue(Permissions.canRead(subject, BOB));
  }

  @Test
  void aSubCollectionNeedsAllThreeRightsFromAMember() {
    assertTrue(
        Permissions.canCreateSubCollection(owned(1, new Member(BOB, true, true, true)), BOB));
    assertFalse(
        Permissions.canCreateSubCollection(owned(1, new Member(BOB, true, true, false)), BOB));
  }

  @Test
  void duplicateMembersAreReducedToTheFirstAndTheOwnerIsDropped() {
    List<Member> unique =
        Permissions.uniqueMembers(
            List.of(
                new Member(BOB, true, false, false),
                new Member(BOB, false, true, true),
                new Member(ALICE, true, true, true),
                new Member(CAROL, false, false, true)),
            ALICE);
    assertEquals(2, unique.size());
    assertEquals(new Member(BOB, true, false, false), unique.get(0));
    assertEquals(CAROL, unique.get(1).userId());
  }

  @Test
  void walkingParentsFindsTheRootOwnerAndUnitesEveryRight() {
    Map<Integer, Subject> tree =
        Map.of(
            1, new Subject(1, ALICE, List.of(new Member(BOB, true, false, false))),
            2, new Subject(2, ALICE, List.of(new Member(BOB, false, true, false))),
            3, new Subject(3, ALICE, List.of(new Member(CAROL, false, false, true))));
    Map<Integer, Integer> parents = Map.of(3, 2, 2, 1);

    Permissions.RootAndMembers found =
        Permissions.walk(3, tree::get, id -> parents.get(id));

    assertEquals(ALICE, found.rootOwnerId());
    Member bob =
        found.members().stream().filter(m -> m.userId() == BOB).findFirst().orElseThrow();
    assertTrue(bob.canCreate());
    assertTrue(bob.canUpdate());
    assertFalse(bob.canDelete());
    assertTrue(found.members().stream().anyMatch(m -> m.userId() == ALICE && m.canDelete()));
    assertTrue(found.members().stream().anyMatch(m -> m.userId() == CAROL));
  }

  @Test
  void aCollectionThatIsItsOwnParentDoesNotMakeTheWalkEndless() {
    Map<Integer, Subject> tree = Map.of(1, new Subject(1, ALICE, List.of()));
    Permissions.RootAndMembers found = Permissions.walk(1, tree::get, id -> 1);
    assertEquals(ALICE, found.rootOwnerId());
    assertEquals(1, found.members().size());
  }

  @Test
  void walkingAParentThatIsNotThereFindsNoRoot() {
    Permissions.RootAndMembers found = Permissions.walk(99, id -> null, id -> null);
    assertEquals(null, found.rootOwnerId());
    assertTrue(found.members().isEmpty());
  }
}
