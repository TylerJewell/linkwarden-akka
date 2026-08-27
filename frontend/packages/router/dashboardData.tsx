import { UpdateDashboardLayoutSchemaType } from "@linkwarden/lib/schemaValidation";
import {
  LinkIncludingShortenedCollectionAndTags,
  MobileAuth,
} from "@linkwarden/types/global";
import { anyPreservationPending } from "@linkwarden/lib/formatStats";
import { PRESERVATION_POLL_INTERVAL } from "./links";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useSession } from "next-auth/react";
import { useEffect, useRef } from "react";

/**
 * Where this screen's data comes from. It is a subscription rather than a repeated request:
 * the address is on this application's own origin, so the session's token is attached to it
 * the same way it is to every other call, which a subscription cannot do for itself.
 */
const DASHBOARD_STREAM_URL =
  process.env.NEXT_PUBLIC_DASHBOARD_STREAM_URL || "/api/v2/dashboard/stream";

const useDashboardData = (auth?: MobileAuth) => {
  let status: "loading" | "authenticated" | "unauthenticated";

  if (!auth) {
    const session = useSession();
    status = session.status;
  } else {
    status = auth?.status;
  }

  const queryClient = useQueryClient();

  // The first message answers the query; every message after it updates the cache in place.
  // Kept apart because a query whose function never resolves stays "loading" for ever, and a
  // query whose function resolves with a placeholder overwrites whatever the first message
  // had already put there — one of the two happens whichever way round they are wired.
  const firstMessage = useRef<any>(null);
  const waitingForFirst = useRef<((payload: any) => void) | null>(null);

  const deliver = (payload: any) => {
    if (waitingForFirst.current) {
      waitingForFirst.current(payload);
      waitingForFirst.current = null;
      return;
    }
    if (firstMessage.current === null) {
      firstMessage.current = payload;
      return;
    }
    queryClient.setQueryData(["dashboardData"], payload);
  };

  // The dashboard's data arrives on a subscription rather than on a repeated request.
  // The connection is opened once and held; the server sends a message whenever what this
  // screen shows has moved, and the reconnect below is what makes a dropped connection
  // recover rather than leaving the page silently stale.
  useEffect(() => {
    if (status !== "authenticated") return;

    let source: EventSource | null = null;
    let retry: ReturnType<typeof setTimeout> | null = null;
    let closed = false;

    const open = () => {
      if (closed) return;
      source = new EventSource(DASHBOARD_STREAM_URL);
      source.onmessage = (event) => {
        deliver(JSON.parse(event.data).data);
      };
      source.onerror = () => {
        source?.close();
        source = null;
        // A dropped stream reopens; without this the page keeps whatever it last had and
        // looks correct while the server moves on.
        retry = setTimeout(open, 1000);
      };
    };

    open();
    return () => {
      closed = true;
      if (retry) clearTimeout(retry);
      source?.close();
    };
  }, [status, queryClient]);

  return useQuery({
    queryKey: ["dashboardData"],
    // There is no request to make: the subscription above is the only route to this data, so
    // this waits for the message that has arrived, or for the next one.
    queryFn: () =>
      new Promise<any>((resolve) => {
        if (firstMessage.current !== null) {
          const payload = firstMessage.current;
          firstMessage.current = {};
          resolve(payload);
          return;
        }
        waitingForFirst.current = resolve;
      }),
    enabled: status === "authenticated",
    staleTime: Infinity,
    retry: false,
  });
};

const useUpdateDashboardLayout = () => {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: async (body: UpdateDashboardLayoutSchemaType) => {
      const response = await fetch("/api/v2/dashboard", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });

      const data = await response.json();

      if (!response.ok) {
        throw new Error(data.response);
      }

      return data;
    },
    onMutate: async (newData) => {
      await queryClient.cancelQueries({ queryKey: ["user"] });

      const previousData = queryClient.getQueryData(["user"]);

      queryClient.setQueryData(["user"], (oldData: any) => {
        // Filter enabled sections and preserve their order
        const enabledSections = newData
          .filter((section) => section.enabled)
          .sort((a, b) => (a.order ?? 0) - (b.order ?? 0));

        return {
          ...oldData,
          dashboardSections: enabledSections,
        };
      });

      return { previousData };
    },
    onError: (err, newData, context) => {
      queryClient.setQueryData(["user"], context?.previousData);
    },
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ["user"] });
      await queryClient.invalidateQueries({ queryKey: ["dashboardData"] });
    },
  });
};

export { useDashboardData, useUpdateDashboardLayout };
