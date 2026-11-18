import type { ProcessState } from "./api";

/**
 * How each service status looks, in one place. The colors behind these classes are the
 * --status-* tokens in src/index.css: change what "up" or "down" looks like there.
 */
export type Status = ProcessState["status"];

/** The small round dot next to a service name. */
export const STATUS_DOT: Record<Status, string> = {
  UP: "bg-status-up",
  DEGRADED: "bg-status-degraded",
  DOWN: "bg-status-down",
  UNKNOWN: "bg-status-unknown",
};

/** The status label chip. */
export const STATUS_BADGE: Record<Status, string> = {
  UP: "bg-status-up-bg text-status-up-text",
  DEGRADED: "bg-status-degraded-bg/20 text-status-degraded-text",
  DOWN: "bg-status-down-bg text-status-down-text",
  UNKNOWN: "bg-status-unknown-bg text-status-unknown-text",
};
