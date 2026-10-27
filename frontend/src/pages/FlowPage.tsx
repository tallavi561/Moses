import { useCallback, useEffect, useMemo, useState } from "react";
import ReactFlow, { Background, Controls, MarkerType, type Edge, type Node } from "reactflow";
import "reactflow/dist/style.css";
import {
  deleteConnection,
  deleteNode,
  fetchConnections,
  fetchProcessState,
  postHeartbeat,
  updateConnection,
  updateNode,
  type Connection,
  type ConnectionDirection,
  type ProcessState,
} from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import AddServiceForm from "../components/AddServiceForm";
import EditNodeForm from "../components/EditNodeForm";
import EditConnectionForm from "../components/EditConnectionForm";

const STATUS_COLOR: Record<ProcessState["status"], string> = {
  UP: "var(--up)",
  DEGRADED: "var(--degraded)",
  DOWN: "var(--down)",
  UNKNOWN: "var(--unknown)",
};

export default function FlowPage() {
  const { t } = useLanguage();
  const [states, setStates] = useState<ProcessState[]>([]);
  const [connections, setConnections] = useState<Connection[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [editingNode, setEditingNode] = useState<ProcessState | null>(null);
  const [editingConnection, setEditingConnection] = useState<Connection | null>(null);

  const refresh = useCallback(async () => {
    try {
      const [nodeData, connectionData] = await Promise.all([fetchProcessState(), fetchConnections()]);
      setStates(nodeData);
      setConnections(connectionData);
      setError(null);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    refresh();
    const interval = setInterval(refresh, 5000);
    return () => clearInterval(interval);
  }, [refresh]);

  async function handleAddService(serviceName: string, status: ProcessState["status"], connectionTargets: string[]) {
    await postHeartbeat({ serviceName, status, connections: connectionTargets.length > 0 ? connectionTargets : undefined });
    await refresh();
  }

  async function handleSaveNode(serviceName: string, status: ProcessState["status"], connectionTargets: string[]) {
    if (!editingNode) return;
    await updateNode(editingNode.serviceName, {
      serviceName,
      status,
      connections: connectionTargets.length > 0 ? connectionTargets : undefined,
    });
    await refresh();
  }

  async function handleDeleteNode() {
    if (!editingNode) return;
    await deleteNode(editingNode.serviceName);
    await refresh();
  }

  async function handleSaveConnection(direction: ConnectionDirection, communicationType: string, description: string) {
    if (!editingConnection) return;
    await updateConnection(editingConnection.id, {
      sourceService: editingConnection.sourceService,
      targetService: editingConnection.targetService,
      direction,
      communicationType: communicationType || undefined,
      description: description || undefined,
    });
    await refresh();
  }

  async function handleDeleteConnection() {
    if (!editingConnection) return;
    await deleteConnection(editingConnection.id);
    await refresh();
  }

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

    const arrow = { type: MarkerType.ArrowClosed };
    const edges: Edge[] = connections.map((c) => ({
      id: c.id,
      source: c.sourceService,
      target: c.targetService,
      animated: true,
      markerEnd: c.direction !== "TARGET_TO_SOURCE" ? arrow : undefined,
      markerStart: c.direction !== "SOURCE_TO_TARGET" ? arrow : undefined,
    }));

    return { nodes, edges };
  }, [states, connections, t]);

  function handleNodeClick(_event: unknown, node: Node) {
    const state = states.find((s) => s.serviceName === node.id);
    if (state) setEditingNode(state);
  }

  function handleEdgeClick(_event: unknown, edge: Edge) {
    const connection = connections.find((c) => c.id === edge.id);
    if (connection) setEditingConnection(connection);
  }

  return (
    <>
      <AddServiceForm existingServices={states.map((s) => s.serviceName)} onAdd={handleAddService} />

      <div className="card flow-card">
        <p className="hint">{t.flow.editNodeHint}</p>
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
            <ReactFlow nodes={nodes} edges={edges} onNodeClick={handleNodeClick} onEdgeClick={handleEdgeClick} fitView>
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

      {editingNode && (
        <EditNodeForm
          node={editingNode}
          otherServiceNames={states.map((s) => s.serviceName).filter((n) => n !== editingNode.serviceName)}
          onSave={handleSaveNode}
          onDelete={handleDeleteNode}
          onClose={() => setEditingNode(null)}
        />
      )}

      {editingConnection && (
        <EditConnectionForm
          connection={editingConnection}
          onSave={handleSaveConnection}
          onDelete={handleDeleteConnection}
          onClose={() => setEditingConnection(null)}
        />
      )}
    </>
  );
}
