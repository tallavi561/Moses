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
import NodeMetrics from "../components/NodeMetrics";
import { Icon } from "../icons";
import { probeStatusLabel } from "../probeStatus";

const STATUS_DOT: Record<ProcessState["status"], string> = {
  UP: "bg-secondary",
  DEGRADED: "bg-tertiary",
  DOWN: "bg-primary-container",
  UNKNOWN: "bg-outline",
};

const STATUS_BADGE: Record<ProcessState["status"], string> = {
  UP: "bg-surface-container text-secondary",
  DEGRADED: "bg-tertiary-container/20 text-tertiary",
  DOWN: "bg-primary-container text-on-primary-container",
  UNKNOWN: "bg-surface-container text-on-surface-variant",
};

type ViewMode = "graph" | "list";

function defaultPosition(index: number): XYPosition {
  return { x: (index % 5) * 260, y: Math.floor(index / 5) * 170 };
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

function FlowNodeCard({ state, t }: { state: ProcessState; t: ReturnType<typeof useLanguage>["t"] }) {
  const probe = probeStatusLabel(state, t);
  const critical = state.status === "DOWN";
  return (
    <div
      className={`w-64 rounded-2xl bg-surface-container-low p-space-sm shadow-xl cursor-pointer transition-transform hover:scale-[1.02] ${
        critical ? "ring-2 ring-primary-container scale-[1.03]" : ""
      }`}
    >
      <div className="flex items-center justify-between gap-space-xs pb-1">
        <div className="flex items-center gap-space-xs min-w-0">
          <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${STATUS_DOT[state.status] ?? STATUS_DOT.UNKNOWN} ${critical ? "animate-pulse" : ""}`} />
          <span className="font-headline text-headline-sm text-on-surface truncate">{state.serviceName}</span>
        </div>
        <span className={`shrink-0 font-code text-code-sm px-1.5 py-0.5 rounded font-semibold ${STATUS_BADGE[state.status] ?? STATUS_BADGE.UNKNOWN}`}>
          {t.flow.status[state.status] ?? state.status}
        </span>
      </div>
      {state.description && (
        <p className="font-body-sm text-body-sm text-on-surface-variant mt-1 line-clamp-2">{state.description}</p>
      )}
      {probe && (
        <div className="flex items-center gap-1 font-code text-code-sm text-on-surface-variant mt-space-xs bg-surface-container-lowest rounded-lg px-space-xs py-1">
          <Icon name="podcasts" size={13} className="text-secondary shrink-0" />
          <span className="truncate">{probe}</span>
        </div>
      )}
      <NodeMetrics state={state} compact />

    </div>
  );
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
          data: { label: <FlowNodeCard state={s} t={t} /> },
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
    const arrow = { type: MarkerType.ArrowClosed, color: "var(--secondary)" };
    const edges: Edge[] = connections.map((c) => ({
      id: c.id,
      source: c.sourceService,
      target: c.targetService,
      animated: true,
      style: { stroke: "var(--secondary)", strokeWidth: 2 },
      markerEnd: c.direction !== "TARGET_TO_SOURCE" ? arrow : undefined,
      markerStart: c.direction !== "SOURCE_TO_TARGET" ? arrow : undefined,
      label: c.communicationType || undefined,
      labelBgPadding: [6, 3] as [number, number],
      labelBgBorderRadius: 999,
      labelStyle: { fill: "var(--secondary)", fontFamily: "JetBrains Mono, monospace", fontSize: 11 },
      labelBgStyle: { fill: "var(--surface-container-high)" },
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

  const upCount = states.filter((s) => s.status === "UP").length;
  const degradedCount = states.filter((s) => s.status === "DEGRADED").length;
  const downCount = states.filter((s) => s.status === "DOWN").length;
  const unknownCount = states.filter((s) => s.status === "UNKNOWN").length;

  return (
    <div className="flex flex-col gap-space-md -mx-space-md -my-0 md:mx-0">
      <div className="bg-surface-container-lowest rounded-2xl shadow-sm px-space-md py-space-sm flex flex-col gap-space-sm">
        <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-space-sm">
          <div className="flex items-center gap-space-md">
            <div className="w-10 h-10 rounded-xl bg-surface-container flex items-center justify-center text-secondary shadow-sm shrink-0">
              <Icon name="hub" size={22} />
            </div>
            <div>
              <div className="flex items-center gap-space-xs">
                <h1 className="font-headline text-headline-sm text-on-surface">{t.nav.flow}</h1>
                <span className="font-code text-code-sm text-on-surface-variant font-semibold tracking-wider">
                  / SERVICE TOPOLOGY
                </span>
              </div>
              {!loading && !error && (
                <div className="flex items-center gap-1 mt-0.5 font-code text-code-sm text-secondary">
                  <span className="w-1.5 h-1.5 rounded-full bg-secondary animate-pulse" />
                  LIVE - polling every 5s
                </div>
              )}
            </div>
          </div>

          {!loading && !error && states.length > 0 && (
            <div className="flex flex-wrap items-center gap-space-xs">
              <span className="flex items-center gap-1.5 px-space-sm py-1 rounded-xl bg-surface-container shadow-sm">
                <span className="w-2 h-2 rounded-full bg-secondary" />
                <span className="font-label-sm text-label-sm text-secondary font-bold">{upCount} {t.flow.status.UP}</span>
              </span>
              {degradedCount > 0 && (
                <span className="flex items-center gap-1.5 px-space-sm py-1 rounded-xl bg-surface-container shadow-sm">
                  <span className="w-2 h-2 rounded-full bg-tertiary animate-pulse" />
                  <span className="font-label-sm text-label-sm text-tertiary font-bold">{degradedCount} {t.flow.status.DEGRADED}</span>
                </span>
              )}
              {downCount > 0 && (
                <span className="flex items-center gap-1.5 px-space-sm py-1 rounded-xl bg-primary-container text-on-primary-container shadow-sm">
                  <span className="w-2 h-2 rounded-full bg-on-primary-container" />
                  <span className="font-label-sm text-label-sm font-bold">{downCount} {t.flow.status.DOWN}</span>
                </span>
              )}
              {unknownCount > 0 && (
                <span className="flex items-center gap-1.5 px-space-sm py-1 rounded-xl bg-surface-container shadow-sm">
                  <span className="w-2 h-2 rounded-full bg-outline" />
                  <span className="font-label-sm text-label-sm text-on-surface-variant">{unknownCount} {t.flow.status.UNKNOWN}</span>
                </span>
              )}
            </div>
          )}

          <div className="flex items-center gap-space-sm justify-between lg:justify-end">
            <div className="flex items-center bg-surface-container-high rounded-xl p-0.5 shadow-inner">
              <button
                type="button"
                className={`flex items-center gap-1 px-space-sm py-1 rounded-lg font-label-md text-label-md transition-all ${
                  view === "graph" ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
                }`}
                onClick={() => changeView("graph")}
                aria-pressed={view === "graph"}
              >
                <Icon name="bubble_chart" size={16} />
                {t.flow.viewGraph}
              </button>
              <button
                type="button"
                className={`flex items-center gap-1 px-space-sm py-1 rounded-lg font-label-md text-label-md transition-all ${
                  view === "list" ? "bg-surface text-secondary" : "text-on-surface-variant hover:text-on-surface"
                }`}
                onClick={() => changeView("list")}
                aria-pressed={view === "list"}
              >
                <Icon name="view_list" size={16} />
                {t.flow.viewList}
              </button>
            </div>
          </div>
        </div>
      </div>

      <AddServiceForm existingServices={states.map((s) => s.serviceName)} onAdd={handleAddService} />

      <div className="bg-surface-container-lowest rounded-2xl shadow-sm p-space-md flex flex-col gap-space-sm">
        {view === "graph" && (
          <p className="flex items-center gap-1 font-body-sm text-body-sm text-on-surface-variant">
            <Icon name="info" size={15} />
            {t.flow.editNodeHint}
          </p>
        )}

        {error ? (
          <div className="flex flex-col items-center gap-space-xs py-space-xl text-on-surface-variant">
            <Icon name="cloud_off" size={28} />
            <span className="font-body-md text-body-md">
              {t.flow.errorPrefix}: {error}
            </span>
          </div>
        ) : loading ? (
          <div className="flex items-center gap-space-sm font-body-md text-body-md text-on-surface-variant py-space-md">
            <Icon name="progress_activity" size={18} className="animate-spin text-secondary" />
            {t.flow.loading}
          </div>
        ) : states.length === 0 ? (
          <div className="flex flex-col items-center gap-space-xs py-space-xl text-on-surface-variant">
            <Icon name="account_tree" size={28} />
            <span className="font-body-md text-body-md">{t.flow.empty}</span>
          </div>
        ) : view === "graph" ? (
          <div className="relative w-full h-[640px] rounded-xl overflow-hidden bg-surface-container-lowest flow-canvas">
            <div
              className="absolute inset-0 pointer-events-none opacity-20 z-0"
              style={{
                backgroundImage: "radial-gradient(rgba(76,215,246,0.25) 1px, transparent 1px)",
                backgroundSize: "28px 28px",
              }}
            />
            <ReactFlow
              nodes={nodes}
              edges={edges}
              onNodesChange={onNodesChange}
              onNodeDragStop={handleNodeDragStop}
              onNodeClick={handleNodeClick}
              onEdgeClick={handleEdgeClick}
              fitView
              className="relative z-10"
            >
              <Background color="var(--outline-variant)" gap={28} />
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
          <div className="flex flex-wrap items-center gap-space-md pt-space-xs">
            {(Object.keys(t.flow.status) as ProcessState["status"][]).map((status) => (
              <span key={status} className="flex items-center gap-1.5 font-body-sm text-body-sm text-on-surface-variant">
                <span className={`w-2 h-2 rounded-full ${STATUS_DOT[status]}`} />
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
    </div>
  );
}
