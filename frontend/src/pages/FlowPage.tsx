import { useCallback, useEffect, useMemo, useState } from "react";
import ReactFlow, {
  Background,
  Controls,
  MarkerType,
  useNodesState,
  type Edge,
  type Node,
  type NodeMouseHandler,
  type XYPosition,
} from "reactflow";
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
import { useProjects } from "../projects/ProjectsContext";
import AddServiceForm from "../components/AddServiceForm";
import EditNodeForm from "../components/EditNodeForm";
import EditConnectionForm from "../components/EditConnectionForm";
import ArchitectureListView from "../components/ArchitectureListView";

const STATUS_COLOR: Record<ProcessState["status"], string> = {
  UP: "var(--up)",
  DEGRADED: "var(--degraded)",
  DOWN: "var(--down)",
  UNKNOWN: "var(--unknown)",
};

type ViewMode = "graph" | "list";

function defaultPosition(index: number): XYPosition {
  return { x: (index % 5) * 200, y: Math.floor(index / 5) * 140 };
}

function loadPositions(key: string): Record<string, XYPosition> {
  try {
    const stored = localStorage.getItem(key);
    if (stored) return JSON.parse(stored);
  } catch {
    // ignore corrupt/unavailable storage
  }
  return {};
}

function savePositions(key: string, positions: Record<string, XYPosition>) {
  try {
    localStorage.setItem(key, JSON.stringify(positions));
  } catch {
    // ignore
  }
}

function relatedServiceNames(connections: Connection[], serviceName: string): string[] {
  return connections
    .filter((c) => c.sourceService === serviceName || c.targetService === serviceName)
    .map((c) => (c.sourceService === serviceName ? c.targetService : c.sourceService));
}

export default function FlowPage() {
  const { t } = useLanguage();
  const { currentProjectId } = useProjects();
  const positionsKey = `moses.flow.positions.${currentProjectId ?? "default"}`;
  const viewKey = `moses.flow.view.${currentProjectId ?? "default"}`;

  const [view, setView] = useState<ViewMode>(() => {
    try {
      const stored = localStorage.getItem(viewKey);
      return stored === "list" ? "list" : "graph";
    } catch {
      return "graph";
    }
  });
  const [states, setStates] = useState<ProcessState[]>([]);
  const [connections, setConnections] = useState<Connection[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [editingNode, setEditingNode] = useState<ProcessState | null>(null);
  const [editingConnection, setEditingConnection] = useState<Connection | null>(null);
  const [nodes, setNodes, onNodesChange] = useNodesState<{ label: React.ReactNode }>([]);

  function changeView(next: ViewMode) {
    setView(next);
    try {
      localStorage.setItem(viewKey, next);
    } catch {
      // ignore
    }
  }

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

  // Rebuild node objects whenever the backend state or language changes, but keep
  // whatever position each node currently has on screen (drag position wins over
  // a stored/default one) so polling doesn't snap dragged nodes back.
  useEffect(() => {
    const storedPositions = loadPositions(positionsKey);
    setNodes((prevNodes) => {
      const prevById = new Map(prevNodes.map((n) => [n.id, n]));
      return states.map((s, i) => {
        const existing = prevById.get(s.serviceName);
        const position = existing?.position ?? storedPositions[s.serviceName] ?? defaultPosition(i);
        return {
          id: s.serviceName,
          position,
          data: {
            label: (
              <div className="flow-node" style={{ background: STATUS_COLOR[s.status] ?? STATUS_COLOR.UNKNOWN }}>
                <div className="node-service">{s.serviceName}</div>
                <div className="node-status">{t.flow.status[s.status] ?? s.status}</div>
              </div>
            ),
          },
          style: { background: "transparent", border: "none", padding: 0, width: "auto" },
        };
      });
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [states, t, positionsKey]);

  const handleNodeDragStop: NodeMouseHandler = useCallback(
    (_event, node) => {
      const positions = loadPositions(positionsKey);
      positions[node.id] = node.position;
      savePositions(positionsKey, positions);
    },
    [positionsKey]
  );

  async function handleAddService(
    serviceName: string,
    status: ProcessState["status"],
    description: string,
    probeUrl: string,
    connectionTargets: string[]
  ) {
    await postHeartbeat({
      serviceName,
      status,
      description: description || undefined,
      probeUrl: probeUrl || undefined,
      connections: connectionTargets,
    });
    await refresh();
  }

  async function handleSaveNode(
    serviceName: string,
    status: ProcessState["status"],
    description: string,
    probeUrl: string,
    connectionTargets: string[]
  ) {
    if (!editingNode) return;
    await updateNode(editingNode.serviceName, {
      serviceName,
      status,
      description: description || undefined,
      probeUrl: probeUrl || undefined,
      connections: connectionTargets,
    });
    if (serviceName !== editingNode.serviceName) {
      const positions = loadPositions(positionsKey);
      if (positions[editingNode.serviceName]) {
        positions[serviceName] = positions[editingNode.serviceName];
        delete positions[editingNode.serviceName];
        savePositions(positionsKey, positions);
      }
    }
    await refresh();
  }

  async function handleDeleteNode() {
    if (!editingNode) return;
    await deleteNode(editingNode.serviceName);
    const positions = loadPositions(positionsKey);
    delete positions[editingNode.serviceName];
    savePositions(positionsKey, positions);
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

  const edges = useMemo(() => {
    const arrow = { type: MarkerType.ArrowClosed };
    const edges: Edge[] = connections.map((c) => ({
      id: c.id,
      source: c.sourceService,
      target: c.targetService,
      animated: true,
      markerEnd: c.direction !== "TARGET_TO_SOURCE" ? arrow : undefined,
      markerStart: c.direction !== "SOURCE_TO_TARGET" ? arrow : undefined,
    }));
    return edges;
  }, [connections]);

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

      <div className="mode-switch" role="radiogroup">
        <button
          type="button"
          className={`mode-option ${view === "graph" ? "active" : ""}`}
          onClick={() => changeView("graph")}
          aria-pressed={view === "graph"}
        >
          {t.flow.viewGraph}
        </button>
        <button
          type="button"
          className={`mode-option ${view === "list" ? "active" : ""}`}
          onClick={() => changeView("list")}
          aria-pressed={view === "list"}
        >
          {t.flow.viewList}
        </button>
      </div>

      <div className="card flow-card">
        {view === "graph" && <p className="hint">{t.flow.editNodeHint}</p>}

        {error ? (
          <div className="empty-state">
            {t.flow.errorPrefix}: {error}
          </div>
        ) : loading ? (
          <div className="loading-row">{t.flow.loading}</div>
        ) : states.length === 0 ? (
          <div className="empty-state">{t.flow.empty}</div>
        ) : view === "graph" ? (
          <div className="flow-canvas">
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onNodeDragStop={handleNodeDragStop}
              onNodeClick={handleNodeClick}
              onEdgeClick={handleEdgeClick}
              fitView
            >
              <Background />
              <Controls showInteractive={false} />
            </ReactFlow>
          </div>
        ) : (
          <ArchitectureListView
            states={states}
            connections={connections}
            onEditNode={setEditingNode}
            onEditConnection={setEditingConnection}
          />
        )}

        {view === "graph" && !error && !loading && states.length > 0 && (
          <div className="flow-legend">
            {(Object.keys(t.flow.status) as ProcessState["status"][]).map((status) => (
              <span key={status} className="legend-item">
                <span className="legend-dot" style={{ background: STATUS_COLOR[status] }} />
                {t.flow.status[status]}
              </span>
            ))}
          </div>
        )}
      </div>

      {editingNode && (
        <EditNodeForm
          node={editingNode}
          otherServiceNames={states.map((s) => s.serviceName).filter((n) => n !== editingNode.serviceName)}
          initialConnections={relatedServiceNames(connections, editingNode.serviceName)}
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
