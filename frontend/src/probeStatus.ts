import type { ProcessState } from "./api";
import type { Strings } from "./i18n/strings";

export function probeStatusLabel(node: ProcessState, t: Strings): string | null {
  if (!node.probeUrl) return null;
  if (!node.lastProbeAt) return t.flow.probeNever;
  const time = new Date(node.lastProbeAt).toLocaleTimeString();
  if (node.lastProbeError) {
    return t.flow.probeLastCheckedError.replace("{time}", time).replace("{error}", node.lastProbeError);
  }
  return t.flow.probeLastChecked
    .replace("{time}", time)
    .replace("{code}", String(node.lastProbeStatusCode ?? "?"))
    .replace("{latency}", String(node.lastProbeLatencyMs ?? "?"));
}
