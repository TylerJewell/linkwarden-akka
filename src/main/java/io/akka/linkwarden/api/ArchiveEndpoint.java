package io.akka.linkwarden.api;

import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.RawHeader;
import akka.javasdk.annotations.Acl;
import akka.javasdk.annotations.http.Get;
import akka.javasdk.annotations.http.HttpEndpoint;
import akka.javasdk.annotations.http.Post;
import io.akka.linkwarden.application.Data;
import io.akka.linkwarden.application.FileStore;
import io.akka.linkwarden.application.LinkEntity;
import io.akka.linkwarden.application.LinkWriter;
import io.akka.linkwarden.domain.Config;
import io.akka.linkwarden.domain.FilePaths;
import io.akka.linkwarden.domain.Ids;
import io.akka.linkwarden.domain.Permissions;
import io.akka.linkwarden.domain.Records;
import io.akka.linkwarden.domain.Tokens;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reading and writing the files a link's preserved formats live in. SPEC-001 R84–R90.
 *
 * <p>Reading is deliberately weaker than every other route here: a public collection's archive is
 * readable by a caller who is signed in as nobody, so the thin token check is used rather than the
 * full account check, and a request with no token at all still gets an answer.
 */
@Acl(allow = @Acl.Matcher(principal = Acl.Principal.ALL))
@HttpEndpoint("/api/v1")
public class ArchiveEndpoint extends Surface {

  private static final int READABILITY = 3;
  private static final int MONOLITH = 4;

  private final FileStore files;
  private final LinkWriter writer;

  public ArchiveEndpoint(Data data, Config config, FileStore files, LinkWriter writer) {
    super(data, config);
    this.files = files;
    this.writer = writer;
  }

  // ------------------------------------------------------------------
  // reading
  // ------------------------------------------------------------------

  @Get("/archives/{linkId}")
  public HttpResponse read(int linkId) {
    Integer format = queryNumber("format");
    boolean preview = queryFlag("preview") || query("preview").isPresent();

    // R86 — with a user-content domain configured, the inlined page is only served to a caller
    // presenting a bearer token, because the plain read is what that domain exists to take over.
    if (format != null
        && format == MONOLITH
        && config.userContentDomain() != null
        && Caller.bearer(authorization()).isEmpty()) {
      return Answers.wrapped(
          403,
          "Monolith archive access must use the user content domain when it is configured.");
    }

    Optional<Records.User> viewer = caller.optional(authorization(), Instant.now());
    Resolution resolved = resolve(linkId, format, preview, viewer.map(Records.User::id).orElse(null));
    if (resolved.refused()) return Answers.wrapped(resolved.status(), resolved.message());

    FileStore.Stored stored = files.read(resolved.filePath());
    List<RawHeader> headers = new ArrayList<>();
    headers.add(RawHeader.create("Cache-Control", "private, max-age=31536000, immutable"));
    headers.add(RawHeader.create("X-Content-Type-Options", "nosniff"));
    if (stored.contentType().startsWith("text/html")) {
      headers.add(RawHeader.create("Content-Security-Policy", "sandbox"));
    }
    return Answers.text(stored.status(), stored.contentType(), stored.bytes(), headers);
  }

  /** SPEC-001 R87 — a five-minute address under the user-content domain, and nothing cached. */
  @Get("/preserved/token")
  public HttpResponse preservedToken() {
    String domain = config.userContentDomain();
    if (domain == null) {
      return Answers.wrapped(400, "User content domain is not configured.");
    }
    Integer linkId = queryNumber("linkId");
    Integer format = queryNumber("format");
    if (format == null) format = MONOLITH;

    Optional<Records.User> viewer = caller.optional(authorization(), Instant.now());
    Resolution resolved =
        resolve(linkId == null ? 0 : linkId, format, false, viewer.map(Records.User::id).orElse(null));
    if (resolved.refused()) return Answers.wrapped(resolved.status(), resolved.message());
    if (!files.exists(resolved.filePath())) {
      return Answers.wrapped(404, "Archived format not found.");
    }

    String secret = config.raw("NEXTAUTH_SECRET");
    String token =
        Tokens.mintPreserved(
            secret, resolved.linkId(), resolved.filePath(), format, Instant.now());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("url", domain.replaceAll("/+$", "") + "/api/v1/preserved/view?token=" + token);
    return Answers.json(200, wrap(body))
        .addHeader(RawHeader.create("Cache-Control", "no-store"));
  }

  private static Map<String, Object> wrap(Object response) {
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("response", response);
    return out;
  }

  /** SPEC-001 R88 — served only on the user-content host, and only against a live token. */
  @Get("/preserved/view")
  public HttpResponse preservedView() {
    String domain = config.userContentDomain();
    if (domain == null) {
      return Answers.wrapped(400, "User content domain is not configured.");
    }
    if (!onUserContentHost(domain)) {
      return Answers.wrapped(403, "Invalid user content host.");
    }
    Optional<String> presented = query("token");
    if (presented.isEmpty()) return Answers.wrapped(401, "Missing archived format token.");

    Optional<Tokens.PreservedClaims> claims =
        Tokens.readPreserved(config.raw("NEXTAUTH_SECRET"), presented.get());
    if (claims.isEmpty() || claims.get().expires().isBefore(Instant.now())) {
      return Answers.wrapped(401, "Invalid archived format token.");
    }

    FileStore.Stored stored = files.read(claims.get().filePath());
    if (stored.status() != 200) {
      return Answers.plain(stored.status(), new String(stored.bytes()));
    }
    boolean download = query("download").map("1"::equals).orElse(false);

    List<RawHeader> headers = new ArrayList<>();
    headers.add(RawHeader.create("Cache-Control", "private, no-store"));
    headers.add(RawHeader.create("X-Robots-Tag", "noindex, nofollow"));
    headers.add(RawHeader.create("X-Content-Type-Options", "nosniff"));
    if (!download && stored.contentType().startsWith("text/html")) {
      headers.add(RawHeader.create("Content-Security-Policy", "sandbox"));
    }
    if (download) {
      headers.add(
          RawHeader.create(
              "Content-Disposition",
              "attachment; filename=\""
                  + FilePaths.downloadFilename(claims.get().format(), claims.get().filePath())
                  + "\""));
    }
    return Answers.text(200, stored.contentType(), stored.bytes(), headers);
  }

  /**
   * Whether this request arrived on the host the user-content domain names.
   *
   * <p>A proxied request carries its original host on {@code X-Forwarded-Host}, which is what a
   * deployment that terminates elsewhere sends, so that is read before {@code Host}.
   */
  private boolean onUserContentHost(String domain) {
    String expected = hostOf(domain);
    String forwarded =
        requestContext()
            .requestHeader("X-Forwarded-Host")
            .map(header -> header.value())
            .orElse(null);
    String host =
        forwarded != null
            ? forwarded.split(",")[0].trim()
            : requestContext().requestHeader("Host").map(header -> header.value()).orElse(null);
    return expected != null && expected.equalsIgnoreCase(normaliseHost(host));
  }

  private static String hostOf(String domain) {
    try {
      URI uri = URI.create(domain.contains("://") ? domain : "https://" + domain);
      return normaliseHost(uri.getPort() < 0 ? uri.getHost() : uri.getHost() + ":" + uri.getPort());
    } catch (RuntimeException e) {
      return null;
    }
  }

  /** A default port is not part of a host: {@code example.com:443} and {@code example.com} match. */
  private static String normaliseHost(String host) {
    if (host == null) return null;
    String value = host.trim().toLowerCase();
    if (value.endsWith(":443") || value.endsWith(":80")) {
      value = value.substring(0, value.lastIndexOf(':'));
    }
    return value;
  }

  @Get("/avatar/{id}")
  public HttpResponse avatar(int id) {
    if (id == 0) return Answers.plain(401, "Invalid parameters.");
    if (data.user(id).isEmpty()) return Answers.plain(400, "File inaccessible.");
    FileStore.Stored stored = files.read(FilePaths.avatar(id));
    return Answers.text(stored.status(), stored.contentType(), stored.bytes(), List.of());
  }

  /**
   * The icon a site publishes, fetched from one of two public services.
   *
   * <p>Neither service is asked to be reachable: a caller whose network cannot reach them gets the
   * same empty answer as one whose site has no icon, which is what keeps the interface from
   * waiting on a third party to draw a list.
   */
  @Get("/getFavicon")
  public HttpResponse favicon() {
    Optional<String> raw = query("url");
    if (raw.isEmpty()) return Answers.plain(400, "");
    URI uri;
    try {
      uri = URI.create(java.net.URLDecoder.decode(raw.get(), java.nio.charset.StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      return Answers.plain(204, "");
    }
    String scheme = uri.getScheme();
    if (!"http".equals(scheme) && !"https".equals(scheme)) return Answers.plain(204, "");
    return Answers.plain(204, "")
        .addHeader(
            RawHeader.create(
                "Cache-Control",
                "public, max-age=3600, s-maxage=86400, stale-while-revalidate=604800"));
  }

  // ------------------------------------------------------------------
  // uploading
  // ------------------------------------------------------------------

  /** SPEC-001 R89 — a file supplied for a link that already exists. */
  @Post("/archives/{linkId}")
  public HttpResponse upload(int linkId, Upload body) {
    if (config.demoMode()) return Answers.demoRefusal();
    Integer format = queryNumber("format");
    boolean preview = queryFlag("preview") || query("preview").isPresent();
    if (linkId == 0 || format == null || FilePaths.suffix(format) == null) {
      return Answers.wrapped(401, "Invalid parameters.");
    }
    if (format == READABILITY) return Answers.wrapped(400, "This format cannot be uploaded.");

    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    Optional<Permissions.Subject> subject = data.subjectForLink(linkId);
    if (subject.isEmpty() || !Permissions.canCreate(subject.get(), result.user().id())) {
      return Answers.wrapped(400, "Collection is not accessible.");
    }
    Optional<Records.Link> found = data.link(linkId);
    if (found.isEmpty()) return Answers.wrapped(400, "Link not found.");
    Records.Link link = found.get();

    // R89 — the two image formats are mutually exclusive: a link that already carries one is not
    // allowed to acquire the other, which would leave two files and one field naming one of them.
    String image = link.image();
    if (!preview
        && image != null
        && ((image.endsWith("jpeg") && format == 0) || (image.endsWith("png") && format == 1))) {
      return Answers.wrapped(400, "PNG or JPEG file already exists.");
    }

    Upload file = body == null ? new Upload(null, null, null) : body;
    Optional<HttpResponse> refusal = refuseFile(file, format, preview);
    if (refusal.isPresent()) return refusal.get();

    return Answers.wrapped(
        200, storeAndRecord(link, subject.get().collectionId(), format, preview, file));
  }

  /** SPEC-001 R89's other half — a file supplied for a link that does not exist yet. */
  @Post("/archives")
  public HttpResponse uploadNew(Upload body) {
    if (config.demoMode()) return Answers.demoRefusal();
    Integer format = queryNumber("format");
    boolean preview = queryFlag("preview") || query("preview").isPresent();
    if (format == null || FilePaths.suffix(format) == null) {
      return Answers.wrapped(401, "Missing format");
    }
    if (format == READABILITY) return Answers.wrapped(400, "This format cannot be uploaded.");

    Caller.Result result = signedIn();
    if (result.refused()) return result.refusal();
    Records.User user = result.user();
    Instant now = Instant.now();
    if (writer.hasPassedLimit(user, 1, now)) {
      return Answers.wrapped(
          400,
          "Each collection owner can only have a maximum of "
              + config.maxLinksPerUser()
              + " Links.");
    }

    Upload file = body == null ? new Upload(null, null, null) : body;
    Optional<HttpResponse> refusal = refuseFile(file, format, preview);
    if (refusal.isPresent()) return refusal.get();

    String url = file.url();
    Records.Collection into = writer.unorganized(user, now);
    Records.Link link =
        writer.create(
            user,
            into,
            new LinkWriter.Proposal(null, url, "url", null, null, null, List.of(), null),
            url != null,
            now);
    return Answers.wrapped(200, storeAndRecord(link, into.id(), format, preview, file));
  }

  /** The media types each format admits, and the size no upload may exceed. */
  private Optional<HttpResponse> refuseFile(Upload file, int format, boolean preview) {
    List<String> allowed =
        preview
            ? List.of("image/png", "image/jpg", "image/jpeg")
            : switch (format) {
              case 0 -> List.of("image/png");
              case 1 -> List.of("image/jpg", "image/jpeg");
              case 2 -> List.of("application/pdf");
              case 4 -> List.of("text/html");
              default -> List.<String>of();
            };
    int maxMb = config.maxFileBufferMb();
    if (file == null || file.contentType() == null || !allowed.contains(file.contentType())) {
      return Optional.of(
          Answers.wrapped(
              400,
              "Sorry, we couldn't process your file. Please ensure it's in ["
                  + String.join(", ", allowed)
                  + "] format and doesn't exceed "
                  + maxMb
                  + "MB."));
    }
    if (file.bytes().length > 1024L * 1024L * maxMb) {
      return Optional.of(
          Answers.wrapped(
              400,
              "Sorry, we couldn't process your file. Please ensure it doesn't exceed "
                  + maxMb
                  + "MB."));
    }
    return Optional.empty();
  }

  /** Writes the bytes where the format belongs and records what the link now carries. */
  private Map<String, Object> storeAndRecord(
      Records.Link link, int collectionId, int format, boolean preview, Upload file) {
    String contentType = file.contentType();
    boolean isPdf = contentType.contains("pdf");
    boolean isImage = contentType.startsWith("image");
    boolean isHtml = contentType.equals("text/html");
    String filePath = FilePaths.archive(collectionId, link.id(), format);
    Instant now = Instant.now();

    if (isImage) {
      files.createFolder("archives/preview/" + collectionId);
      // The preview is the uploaded bytes as they arrived. Producing a smaller copy is the
      // image decoder's work, which SPEC-001 §1 leaves outside this port; where the file goes
      // and that it exists is the decision, and that is kept.
      files.write(FilePaths.preview(collectionId, link.id()), file.bytes());
    }
    if (!preview) files.write(filePath, file.bytes());

    Records.Link updated =
        data.client()
            .forKeyValueEntity(Ids.link(link.id()))
            .method(LinkEntity::uploaded)
            .invoke(
                new LinkEntity.Uploaded(
                    preview ? "preview" : (isImage ? "image" : isPdf ? "pdf" : isHtml ? "monolith" : ""),
                    filePath,
                    isPdf,
                    now));
    return Shapes.link(updated, null, data.tagsOf(updated), null, false);
  }

  /**
   * A file a caller uploaded: what it says it is, and what it is.
   *
   * <p>The original takes this as one part of a {@code multipart/form-data} body. The runtime this
   * is built on routes a request body to an endpoint as JSON, so the file arrives base64-encoded
   * inside one, with the media type beside it — which R89 refuses an upload on, so it is part of
   * the message rather than a hint. Every rule that reads either is unchanged.
   */
  public record Upload(String mimeType, byte[] file, String url) {

    byte[] bytes() {
      return file == null ? new byte[0] : file;
    }

    String contentType() {
      if (mimeType == null) return null;
      int semicolon = mimeType.indexOf(';');
      return semicolon > 0 ? mimeType.substring(0, semicolon).trim() : mimeType.trim();
    }
  }

  // ------------------------------------------------------------------
  // where a format's file is, and who may read it
  // ------------------------------------------------------------------

  /** SPEC-001 R86 — the resolved path, or the refusal to send instead. */
  private record Resolution(String filePath, int linkId, int status, String message) {

    boolean refused() {
      return filePath == null;
    }
  }

  private Resolution resolve(int linkId, Integer format, boolean preview, Integer viewerId) {
    if (linkId == 0 || format == null || FilePaths.suffix(format) == null) {
      return new Resolution(null, linkId, 401, "Invalid parameters.");
    }
    Optional<Records.Collection> holder =
        data.link(linkId).flatMap(link -> data.collection(link.collectionId()));
    boolean allowed =
        holder
            .map(
                collection ->
                    collection.isPublic()
                        || (viewerId != null
                            && Permissions.canRead(collection.asSubject(), viewerId)))
            .orElse(false);
    if (!allowed) {
      return new Resolution(null, linkId, 401, "You don't have access to this collection.");
    }
    int collectionId = holder.get().id();
    String path =
        preview
            ? FilePaths.preview(collectionId, linkId)
            : FilePaths.archive(collectionId, linkId, format);
    return new Resolution(path, linkId, 200, null);
  }
}
