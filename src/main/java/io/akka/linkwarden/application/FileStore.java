package io.akka.linkwarden.application;

import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.FilePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;

/**
 * Where preserved formats, previews and avatars are kept. SPEC-001 R84–R86, R90.
 *
 * <p>Paths are relative and always the same ones the original writes, so a data directory written
 * by either system is readable by the other. A read of something that is not there answers the
 * same way the original's does — a plain-text sentence with a 404 — rather than raising, because
 * the absence is an ordinary answer here: a link is asked for its screenshot before one exists.
 */
public final class FileStore {

  /** What a read answers: the bytes, what they are, and the status the route should carry. */
  public record Stored(byte[] bytes, String contentType, int status) {}

  private final Path root;

  public FileStore(Config config) {
    this.root = Paths.get(config.storageFolder()).toAbsolutePath();
  }

  private Path resolve(String filePath) {
    Path resolved = root.resolve(filePath).normalize();
    if (!resolved.startsWith(root)) {
      throw new IllegalArgumentException("path escapes the storage folder: " + filePath);
    }
    return resolved;
  }

  public boolean exists(String filePath) {
    return Files.isRegularFile(resolve(filePath));
  }

  public Stored read(String filePath) {
    Path path = resolve(filePath);
    if (!Files.isRegularFile(path)) {
      return new Stored("File not found.".getBytes(java.nio.charset.StandardCharsets.UTF_8),
          "text/plain", 404);
    }
    try {
      return new Stored(Files.readAllBytes(path), FilePaths.contentType(filePath), 200);
    } catch (IOException e) {
      return new Stored(
          "An internal occurred, please contact the support team."
              .getBytes(java.nio.charset.StandardCharsets.UTF_8),
          "text/plain",
          500);
    }
  }

  public void write(String filePath, byte[] bytes) {
    Path path = resolve(filePath);
    try {
      Files.createDirectories(path.getParent());
      Files.write(path, bytes);
    } catch (IOException e) {
      throw new IllegalStateException("could not write " + filePath, e);
    }
  }

  public void createFolder(String filePath) {
    try {
      Files.createDirectories(resolve(filePath));
    } catch (IOException e) {
      throw new IllegalStateException("could not create " + filePath, e);
    }
  }

  public void remove(String filePath) {
    try {
      Files.deleteIfExists(resolve(filePath));
    } catch (IOException e) {
      throw new IllegalStateException("could not remove " + filePath, e);
    }
  }

  public void removeFolder(String filePath) {
    Path path = resolve(filePath);
    if (!Files.isDirectory(path)) return;
    try (var walk = Files.walk(path)) {
      walk.sorted(java.util.Comparator.reverseOrder())
          .forEach(
              p -> {
                try {
                  Files.deleteIfExists(p);
                } catch (IOException e) {
                  throw new IllegalStateException("could not remove " + p, e);
                }
              });
    } catch (IOException e) {
      throw new IllegalStateException("could not remove " + filePath, e);
    }
  }

  public void move(String from, String to) {
    Path source = resolve(from);
    if (!Files.isRegularFile(source)) return;
    Path target = resolve(to);
    try {
      Files.createDirectories(target.getParent());
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new IllegalStateException("could not move " + from, e);
    }
  }

  /** SPEC-001 R85 — all seven paths a link's files can occupy. */
  public void removeLinkFiles(int collectionId, int linkId) {
    for (String path : FilePaths.allOf(collectionId, linkId)) remove(path);
  }

  public void moveLinkFiles(int linkId, int from, int to) {
    List<String> before = FilePaths.allOf(from, linkId);
    List<String> after = FilePaths.allOf(to, linkId);
    for (int i = 0; i < before.size(); i++) move(before.get(i), after.get(i));
  }
}
