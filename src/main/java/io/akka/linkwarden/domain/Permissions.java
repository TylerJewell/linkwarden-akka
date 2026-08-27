package io.akka.linkwarden.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Who may do what to a collection, and what a chain of parents grants. SPEC-001 R23–R28.
 *
 * <p>There is no right that only a member can hold: the owner has all four, and a member has the
 * ones their membership row names. Reachability is the weakest of them and is what a read asks
 * about.
 */
public final class Permissions {

  /** One membership row, as the rules read it. */
  public record Member(int userId, boolean canCreate, boolean canUpdate, boolean canDelete) {

    public static Member full(int userId) {
      return new Member(userId, true, true, true);
    }

    public Member mergedWith(Member other) {
      return new Member(
          userId,
          canCreate || other.canCreate(),
          canUpdate || other.canUpdate(),
          canDelete || other.canDelete());
    }
  }

  /** What a permission question is asked about: a collection's owner and its members. */
  public record Subject(int collectionId, int ownerId, List<Member> members) {}

  private Permissions() {}

  public static boolean isOwner(Subject subject, int userId) {
    return subject != null && subject.ownerId() == userId;
  }

  public static boolean isMember(Subject subject, int userId) {
    return subject != null && subject.members().stream().anyMatch(m -> m.userId() == userId);
  }

  public static boolean canRead(Subject subject, int userId) {
    return isOwner(subject, userId) || isMember(subject, userId);
  }

  public static boolean canCreate(Subject subject, int userId) {
    return isOwner(subject, userId)
        || (subject != null
            && subject.members().stream().anyMatch(m -> m.userId() == userId && m.canCreate()));
  }

  public static boolean canUpdate(Subject subject, int userId) {
    return isOwner(subject, userId)
        || (subject != null
            && subject.members().stream().anyMatch(m -> m.userId() == userId && m.canUpdate()));
  }

  public static boolean canDelete(Subject subject, int userId) {
    return isOwner(subject, userId)
        || (subject != null
            && subject.members().stream().anyMatch(m -> m.userId() == userId && m.canDelete()));
  }

  /** All three rights, which is what creating a sub-collection asks a member for. */
  public static boolean canCreateSubCollection(Subject subject, int userId) {
    return isOwner(subject, userId)
        || (subject != null
            && subject.members().stream()
                .anyMatch(
                    m ->
                        m.userId() == userId && m.canCreate() && m.canUpdate() && m.canDelete()));
  }

  /**
   * The members a collection's update writes: duplicates by user removed, keeping the first, and
   * the owner's own row dropped.
   */
  public static List<Member> uniqueMembers(List<Member> proposed, int ownerId) {
    List<Member> out = new ArrayList<>();
    for (Member member : proposed) {
      if (member.userId() == ownerId) continue;
      if (out.stream().anyMatch(m -> m.userId() == member.userId())) continue;
      out.add(member);
    }
    return out;
  }

  public record RootAndMembers(Integer rootOwnerId, List<Member> members) {}

  /**
   * The root owner of a chain of parents, and every right anyone holds anywhere along it.
   *
   * <p>Two rows naming the same user are merged by taking the union of their rights, so a user who
   * may update one level and delete another may do both on what is created below. The walk stops
   * on a collection it has already seen, because a collection may be its own parent (question-log
   * row 10) and the chain is then a cycle.
   */
  public static RootAndMembers walk(
      int parentId,
      java.util.function.IntFunction<Subject> subjectOf,
      java.util.function.IntFunction<Integer> parentOf) {
    Map<Integer, Member> byUser = new LinkedHashMap<>();
    Integer rootOwnerId = null;
    Integer currentId = parentId;
    java.util.Set<Integer> seen = new java.util.HashSet<>();

    while (currentId != null && seen.add(currentId)) {
      Subject subject = subjectOf.apply(currentId);
      if (subject == null) break;
      rootOwnerId = subject.ownerId();
      add(byUser, Member.full(subject.ownerId()));
      for (Member member : subject.members()) add(byUser, member);
      currentId = parentOf.apply(currentId);
    }
    return new RootAndMembers(rootOwnerId, List.copyOf(byUser.values()));
  }

  private static void add(Map<Integer, Member> byUser, Member member) {
    byUser.merge(member.userId(), member, Member::mergedWith);
  }
}
