import { useEffect, useRef, useState } from "react";
import type { ChatMessage } from "../api";
import { getSession } from "../auth/session";

/**
 * The chat's live connection. chat-service pushes new messages and the agent's "typing" state
 * over a WebSocket; sending stays a normal API call. The socket signs in with its first message
 * (so the token never goes into a URL), and reconnects with a growing delay when it drops -
 * `onReady` fires after every (re)connect, so the page can fetch whatever it missed meanwhile.
 */

const WS_URL: string = import.meta.env.VITE_CHAT_WS_URL ?? "ws://localhost:8087/ws/chat";

export type ChatSocketEvent =
  | { type: "message"; message: ChatMessage }
  | { type: "typing"; channel: string; typing: boolean };

export type ChatConnection = "connecting" | "live" | "offline";

export function useChatSocket(
  projectId: string | null,
  environment: string | null,
  onEvent: (event: ChatSocketEvent) => void,
  onReady: () => void
): ChatConnection {
  const [state, setState] = useState<ChatConnection>("connecting");
  // Latest callbacks without reconnecting when they change.
  const handlers = useRef({ onEvent, onReady });
  handlers.current = { onEvent, onReady };

  useEffect(() => {
    if (!projectId || !environment) return;
    let socket: WebSocket | null = null;
    let closedByUs = false;
    let retry = 0;
    let retryTimer: number | undefined;
    let pingTimer: number | undefined;

    function connect() {
      const session = getSession();
      if (!session) return;
      setState("connecting");
      socket = new WebSocket(WS_URL);
      socket.onopen = () => {
        socket?.send(JSON.stringify({ type: "auth", token: session.token, projectId, environment }));
      };
      socket.onmessage = (raw) => {
        let data: { type?: string } & Record<string, unknown>;
        try {
          data = JSON.parse(String(raw.data));
        } catch {
          return;
        }
        if (data.type === "ready") {
          retry = 0;
          setState("live");
          handlers.current.onReady();
        } else if (data.type === "message" || data.type === "typing") {
          handlers.current.onEvent(data as unknown as ChatSocketEvent);
        }
      };
      socket.onclose = () => {
        window.clearInterval(pingTimer);
        if (closedByUs) return;
        setState("offline");
        retry += 1;
        retryTimer = window.setTimeout(connect, Math.min(15_000, 1_000 * 2 ** Math.min(retry, 4)));
      };
      // Keeps proxies and idle timeouts from silently dropping a quiet socket.
      pingTimer = window.setInterval(() => {
        if (socket?.readyState === WebSocket.OPEN) socket.send('{"type":"ping"}');
      }, 25_000);
    }

    connect();
    return () => {
      closedByUs = true;
      window.clearTimeout(retryTimer);
      window.clearInterval(pingTimer);
      socket?.close();
    };
  }, [projectId, environment]);

  return state;
}
