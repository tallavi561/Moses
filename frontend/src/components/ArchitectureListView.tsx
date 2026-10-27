import { useState } from "react";
import type { Connection, ProcessState } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import { IconChevron } from "../icons";

interface ArchitectureListViewProps {
  states: ProcessState[];
  connections: Connection[];
  onEditNode: (node: ProcessState) => void;
  onEditConnection: (connection: Connection) => void;
}

const STATUS_COLOR: Record<ProcessState["status"], string> = {
  UP: "var(--up)",
  DEGRADED: "var(--degraded)",
  DOWN: "var(--down)",
  UNKNOWN: "var(--unknown)",
};

export default function ArchitectureListView({ states, connections, onEditNode, onEditConnection }: ArchitectureListViewProps) {
  const { t } = useLanguage();
  const [expanded, setExpanded] = useState<string | null>(null);

  function connectionLabel(c: Connection) {
    const template =
      c.direction === "SOURCE_TO_TARGET"
        ? t.flow.directionSourceToTarget
        : c.direction === "TARGET_TO_SOURCE"
        ? t.flow.directionTargetToSource
        : t.flow.directionBidirectional;
    return template.replace("{source}", c.sourceService).replace("{target}", c.targetService);
  }

  return (
    <div className="arch-list">
      {states.map((node) => {
        const relatedConnections = connections.filter(
          (c) => c.sourceService === node.serviceName || c.targetService === node.serviceName
        );
        const isExpanded = expanded === node.serviceName;

        return (
          <div key={node.serviceName} className="arch-row">
            <button
              type="button"
              className="arch-row-header"
              onClick={() => setExpanded(isExpanded ? null : node.serviceName)}
              aria-expanded={isExpanded}
            >
              <IconChevron direction="end" className={`arch-row-chevron ${isExpanded ? "open" : ""}`} />
              <span className="legend-dot" style={{ background: STATUS_COLOR[node.status] ?? STATUS_COLOR.UNKNOWN }} />
              <span className="arch-row-name">{node.serviceName}</span>
              <span className="arch-row-status">{t.flow.status[node.status] ?? node.status}</span>
            </button>

            {isExpanded && (
              <div className="arch-row-body">
                <p className="arch-row-description">{node.description || t.flow.listNoDescription}</p>

                <div className="calls-label">{t.flow.listConnectionsTitle}</div>
                {relatedConnections.length === 0 ? (
                  <p className="hint">{t.flow.listNoConnections}</p>
                ) : (
                  <ul className="arch-connections">
                    {relatedConnections.map((c) => (
                      <li key={c.id}>
                        <button
                          type="button"
                          className="arch-connection-item clickable"
                          onClick={() => onEditConnection(c)}
                        >
                          <span className="arch-connection-direction">{connectionLabel(c)}</span>
                          {c.communicationType && <span className="arch-connection-type">{c.communicationType}</span>}
                          {c.description && <span className="arch-connection-description">{c.description}</span>}
                        </button>
                      </li>
                    ))}
                  </ul>
                )}

                <button className="btn-secondary" onClick={() => onEditNode(node)}>
                  {t.flow.listEditNode}
                </button>
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}
