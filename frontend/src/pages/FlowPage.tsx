import { useEffect, useMemo, useState } from "react";
import ReactFlow, { Background, Controls, type Edge, type Node } from "reactflow";
import "reactflow/dist/style.css";
import { fetchProcessState, type ProcessState } from "../api";
import { useLanguage } from "../i18n/LanguageContext";

const STATUS_COLOR: Record<ProcessState["status"], string> = {
  UP: "var(--up)",
  DEGRADED: "var(--degraded)",
  DOWN: "var(--down)",
  UNKNOWN: "var(--unknown)",
};

export default function FlowPage() {
  const { t } = useLanguage();
  const [states, setStates] = useState<ProcessState[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

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
      } finally {
        if (!cancelled) setLoading(false);
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
      position: { x: (i % 5) * 200, y: Math.floor(i / 5) * 140 },
      data: {
        label: (
          <div className="flow-node" style={{ background: STATUS_COLOR[s.status] ?? STATUS_COLOR.UNKNOWN }}>
            <div className="node-service">{s.serviceName}</div>
            <div className="node-status">{t.flow.status[s.status] ?? s.status}</div>
          </div>
        ),
      },
      style: { background: "transparent", border: "none", padding: 0, width: "auto" },
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
  }, [states, t]);

  return (
    <div className="card flow-card">
      <div className="flow-canvas">
        {error ? (
          <div className="empty-state">
            {t.flow.errorPrefix}: {error}
          </div>
        ) : loading ? (
          <div className="loading-row">{t.flow.loading}</div>
        ) : states.length === 0 ? (
          <div className="empty-state">{t.flow.empty}</div>
        ) : (
          <ReactFlow nodes={nodes} edges={edges} fitView>
            <Background />
            <Controls showInteractive={false} />
          </ReactFlow>
        )}
      </div>

      <div className="flow-legend">
        {(Object.keys(t.flow.status) as ProcessState["status"][]).map((status) => (
          <span key={status} className="legend-item">
            <span className="legend-dot" style={{ background: STATUS_COLOR[status] }} />
            {t.flow.status[status]}
          </span>
        ))}
      </div>
    </div>
  );
}
