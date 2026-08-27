import { getToken } from "next-auth/jwt";
import { NextResponse } from "next/server";
import type { NextRequest } from "next/server";

/**
 * Where this interface's data comes from.
 *
 * <p>Every screen calls `/api/v1/...` on its own origin, exactly as it always did. Those requests
 * are sent on to the rebuild rather than answered here, with the token this session holds attached
 * — the screens carry a cookie and the rebuild reads a bearer header, and this is the one place
 * that difference is dealt with.
 *
 * The sign-in routes are the exception: they belong to this application, which owns the cookie,
 * and they ask the rebuild themselves.
 */
const LINKWARDEN_API = process.env.LINKWARDEN_API || "http://localhost:9160";

export async function middleware(request: NextRequest) {
  const target = new URL(request.nextUrl.pathname + request.nextUrl.search, LINKWARDEN_API);

  const session = await getToken({
    req: request,
    secret: process.env.NEXTAUTH_SECRET,
  });

  const headers = new Headers(request.headers);
  if (session && (session as any).apiToken) {
    headers.set("Authorization", `Bearer ${(session as any).apiToken}`);
  }
  // A subscription is a response that never ends, and a compressed one is not delivered until
  // its compressor has enough bytes to flush — which for a message of ninety bytes is never.
  // Asked for uncompressed, so each message reaches the screen when it is sent.
  headers.set("accept-encoding", "identity");

  return NextResponse.rewrite(target, { request: { headers } });
}

export const config = {
  matcher: ["/api/v1/((?!auth).*)", "/api/v2/:path*"],
};
