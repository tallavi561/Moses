import { useEffect, useMemo, useState } from "react";
import ReactFlow, { type Edge, type Node } from "reactflow";
import "reactflow/dist/style.css";
import { fetchProcessState, type ProcessState } from "../api";

const STATUS_COLOR: Record<ProcessState["status"], string> = {
  UP: "#2ecc71",
  DEGRADED: "#f39c12",
  DOWN: "#e74c3c",
  UNKNOWN: "#95a5a6",
};

export default function FlowView() {
  const [states, setStates] = useState<ProcessState[]>([]);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;

    async function poll() {
      try {
        const data = await fetchProcessState();
        if (!cancelled) {
          setStates(data);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) setError((e as Error).message);
      }
    }

    poll();
    const interval = setInterval(poll, 5000);
    return () => {
      cancelled = true;
      clearInterval(interval);
    };
  }, []);

  const { nodes, edges } = useMemo(() => {
    const nodes: Node[] = states.map((s, i) => ({
      id: s.serviceName,
      position: { x: (i % 5) * 180, y: Math.floor(i / 5) * 120 },
      data: { label: `${s.serviceName}\n${s.status}` },
      style: {
        background: STATUS_COLOR[s.status] ?? STATUS_COLOR.UNKNOWN,
        color: "white",
        whiteSpace: "pre-line",
        borderRadius: 8,
        padding: 8,
      },
    }));

    const edges: Edge[] = states.flatMap((s) =>
      (s.calls ?? []).map((target) => ({
        id: `${s.serviceName}->${target}`,
        source: s.serviceName,
        target,
        animated: true,
      }))
    );

    return { nodes, edges };
  }, [states]);

  return (
    <div style={{ height: "70vh", border: "1px solid #ccc" }}>
      {error && <p style={{ color: "red" }}>{error}</p>}
      {states.length === 0 && !error && (
        <p style={{ padding: 16 }}>
          No process-state heartbeats received yet. POST to
          /api/process/heartbeat on process-monitor-service to populate this view.
        </p>
      )}
      <ReactFlow nodes={nodes} edges={edges} fitView />
    </div>
  );
}
