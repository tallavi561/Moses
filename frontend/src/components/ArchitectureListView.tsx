import { useState } from "react";
import type { Connection, ProcessState } from "../api";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";
import { probeStatusLabel } from "../probeStatus";

interface ArchitectureListViewProps {
  states: ProcessState[];
  connections: Connection[];
  onEditNode: (node: ProcessState) => void;
  onEditConnection: (connection: Connection) => void;
}

const STATUS_DOT: Record<ProcessState["status"], string> = {
  UP: "bg-secondary",
  DEGRADED: "bg-tertiary",
  DOWN: "bg-primary-container",
  UNKNOWN: "bg-outline",
};

export default function ArchitectureListView({ states, connections, onEditNode, onEditConnection }: ArchitectureListViewProps) {
  const { t } = useLanguage();
  const [expandedNode, setExpandedNode] = useState<string | null>(null);

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
    <div className="flex flex-col gap-space-lg">
      <section>
        <h2 className="font-label-sm text-label-sm text-on-surface-variant uppercase tracking-widest mb-space-sm">
          {t.flow.listServicesTitle}
        </h2>
        {states.length === 0 ? (
          <p className="font-body-md text-body-md text-on-surface-variant">{t.flow.listNoServices}</p>
        ) : (
          <div className="flex flex-col gap-space-xs">
            {states.map((node) => {
              const isExpanded = expandedNode === node.serviceName;
              return (
                <div key={node.serviceName} className="bg-surface-container rounded-xl overflow-hidden">
                  <button
                    type="button"
                    className="w-full flex items-center gap-space-sm px-space-md py-space-sm hover:bg-surface-container-high transition-colors"
                    onClick={() => setExpandedNode(isExpanded ? null : node.serviceName)}
                    aria-expanded={isExpanded}
                  >
                    <Icon
                      name="chevron_right"
                      size={18}
                      className={`text-on-surface-variant transition-transform shrink-0 ${isExpanded ? "rotate-90" : ""}`}
                    />
                    <span className={`w-2.5 h-2.5 rounded-full shrink-0 ${STATUS_DOT[node.status] ?? STATUS_DOT.UNKNOWN}`} />
                    <span className="font-headline text-headline-sm text-on-surface flex-1 text-left truncate">
                      {node.serviceName}
                    </span>
                    <span className="font-code text-code-sm text-on-surface-variant">
                      {t.flow.status[node.status] ?? node.status}
                    </span>
                  </button>

                  {isExpanded && (
                    <div className="px-space-md pb-space-md pt-space-xs flex flex-col gap-space-sm border-t border-outline-variant/30">
                      <p className="font-body-md text-body-md text-on-surface-variant">
                        {node.description || t.flow.listNoDescription}
                      </p>
                      {probeStatusLabel(node, t) && (
                        <p className="flex items-center gap-1 font-code text-code-sm text-on-surface-variant bg-surface-container-high rounded-lg px-space-sm py-1.5 w-fit">
                          <Icon name="podcasts" size={14} className="text-secondary" />
                          {probeStatusLabel(node, t)}
                        </p>
                      )}
                      <button
                        className="w-fit flex items-center gap-1.5 px-space-md py-1.5 rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors"
                        onClick={() => onEditNode(node)}
                        type="button"
                      >
                        <Icon name="edit" size={16} className="text-secondary" />
                        {t.flow.listEditNode}
                      </button>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </section>

      <section>
        <h2 className="font-label-sm text-label-sm text-on-surface-variant uppercase tracking-widest mb-space-sm">
          {t.flow.listConnectionsTitle}
        </h2>
        {connections.length === 0 ? (
          <p className="font-body-md text-body-md text-on-surface-variant">{t.flow.listNoConnections}</p>
        ) : (
          <div className="flex flex-col gap-space-xs">
            {connections.map((c) => (
              <button
                key={c.id}
                type="button"
                className="w-full text-left flex items-center justify-between gap-space-sm px-space-md py-space-sm rounded-xl bg-surface-container hover:bg-surface-container-high transition-colors"
                onClick={() => onEditConnection(c)}
              >
                <div className="flex items-center gap-space-sm min-w-0">
                  <Icon name="swap_horiz" size={16} className="text-secondary shrink-0" />
                  <span className="font-code text-code-md text-on-surface truncate">{connectionLabel(c)}</span>
                </div>
                <div className="flex items-center gap-space-sm shrink-0">
                  {c.communicationType && (
                    <span className="font-code text-code-sm text-secondary bg-surface-container-high px-2 py-0.5 rounded-full">
                      {c.communicationType}
                    </span>
                  )}
                  {c.description && (
                    <span className="hidden md:inline font-body-sm text-body-sm text-on-surface-variant truncate max-w-xs">
                      {c.description}
                    </span>
                  )}
                </div>
              </button>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}
