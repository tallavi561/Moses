import { useCallback, useEffect, useLayoutEffect, useRef, useState, type KeyboardEvent } from "react";
import {
  fetchChatIdentity,
  fetchChatMessages,
  fetchMembers,
  sendChatMessage,
  type ChatIdentity,
  type ChatMessage,
  type ProjectMember,
} from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { useChatSocket, type ChatSocketEvent } from "../chat/useChatSocket";
import ChatMarkdown from "../chat/ChatMarkdown";
import AnimatedLogo from "../components/AnimatedLogo";
import { Icon } from "../icons";
import { Link } from "react-router-dom";
import { fillTemplate, formatDuration } from "../durations";

const TEAM = "team";

interface ChannelState {
  messages: ChatMessage[];
  hasMore: boolean;
  loaded: boolean;
}

const EMPTY: ChannelState = { messages: [], hasMore: false, loaded: false };

/** Adds messages, dropping ones already there (a sent message comes back both from the API and the socket). */
function merge(existing: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const byId = new Map(existing.map((m) => [m.id, m]));
  for (const m of incoming) byId.set(m.id, m);
  return [...byId.values()].sort((a, b) => a.createdAt.localeCompare(b.createdAt));
}

/** Someone the composer can @-mention: the agent, or a project member. */
interface MentionCandidate {
  key: string;
  /** What goes after the "@" in the message. */
  handle: string;
  name: string;
  detail: string;
  agent: boolean;
}

/** The "@..." being typed right before the caret, if any: where it starts and what follows the @. */
function mentionAt(text: string, caret: number): { start: number; query: string } | null {
  const before = text.slice(0, caret);
  const at = before.lastIndexOf("@");
  if (at < 0 || (at > 0 && !/\s/.test(before[at - 1]))) return null;
  const query = before.slice(at + 1);
  if (query.includes("\n") || query.length > 40) return null;
  return { start: at, query };
}

/** Matches when any word of the name (or the email) starts with what was typed, ignoring case. */
function matchesMention(c: MentionCandidate, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return true;
  const name = c.name.toLowerCase();
  return name.startsWith(q) || name.split(/\s+/).some((w) => w.startsWith(q)) || c.handle.toLowerCase().startsWith(q)
    || c.detail.toLowerCase().startsWith(q);
}

function dayKey(iso: string) {
  return new Date(iso).toDateString();
}

export default function ChatPage() {
  const { currentProject, currentEnvironment } = useProjects();
  const { t, lang } = useLanguage();
  const projectId = currentProject?.id ?? null;

  const [identity, setIdentity] = useState<ChatIdentity | null>(null);
  const [identityError, setIdentityError] = useState(false);
  const [channel, setChannel] = useState(TEAM);
  const [channels, setChannels] = useState<Record<string, ChannelState>>({});
  const [typing, setTyping] = useState<Record<string, boolean>>({});
  const [unread, setUnread] = useState<Record<string, number>>({});
  const [draft, setDraft] = useState("");
  const [replyTo, setReplyTo] = useState<ChatMessage | null>(null);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState(false);
  const [members, setMembers] = useState<ProjectMember[]>([]);
  const [mention, setMention] = useState<{ start: number; query: string } | null>(null);
  const [mentionIndex, setMentionIndex] = useState(0);

  const scroller = useRef<HTMLDivElement>(null);
  const stickToBottom = useRef(true);
  const channelRef = useRef(channel);
  channelRef.current = channel;
  const composer = useRef<HTMLTextAreaElement>(null);

  const privateChannel = identity?.privateChannel ?? null;
  const current = channels[channel] ?? EMPTY;

  useEffect(() => {
    let cancelled = false;
    setIdentity(null);
    setIdentityError(false);
    setChannels({});
    fetchChatIdentity()
      .then((id) => !cancelled && setIdentity(id))
      .catch(() => !cancelled && setIdentityError(true));
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  useEffect(() => {
    if (!projectId) return;
    let cancelled = false;
    fetchMembers(projectId)
      .then((m) => !cancelled && setMembers(m))
      .catch(() => !cancelled && setMembers([]));
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  const loadLatest = useCallback(async (ch: string) => {
    try {
      const page = await fetchChatMessages(ch);
      setChannels((prev) => {
        const old = prev[ch] ?? EMPTY;
        return { ...prev, [ch]: { messages: merge(old.messages, page.messages), hasMore: old.loaded ? old.hasMore : page.hasMore, loaded: true } };
      });
    } catch {
      setChannels((prev) => ({ ...prev, [ch]: { ...(prev[ch] ?? EMPTY), loaded: true } }));
    }
  }, []);

  useEffect(() => {
    if (!identity) return;
    if (!channels[channel]?.loaded) void loadLatest(channel);
    setUnread((u) => ({ ...u, [channel]: 0 }));
    stickToBottom.current = true;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [identity, channel]);

  const onEvent = useCallback((event: ChatSocketEvent) => {
    if (event.type === "typing") {
      setTyping((prev) => ({ ...prev, [event.channel]: event.typing }));
      return;
    }
    const m = event.message;
    setChannels((prev) => {
      const old = prev[m.channel];
      if (!old?.loaded) return prev; // will be fetched when opened
      return { ...prev, [m.channel]: { ...old, messages: merge(old.messages, [m]) } };
    });
    if (m.authorType === "AGENT") setTyping((prev) => ({ ...prev, [m.channel]: false }));
    if (m.channel !== channelRef.current) setUnread((u) => ({ ...u, [m.channel]: (u[m.channel] ?? 0) + 1 }));
  }, []);

  // After every (re)connect, catch up on whatever arrived while the socket was down.
  const onReady = useCallback(() => {
    void loadLatest(channelRef.current);
  }, [loadLatest]);

  const connection = useChatSocket(identity ? projectId : null, currentEnvironment, onEvent, onReady);

  // Follow new messages only when the reader is already at the bottom.
  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && stickToBottom.current) el.scrollTop = el.scrollHeight;
  }, [current.messages, typing, channel]);

  function onScroll() {
    const el = scroller.current;
    if (el) stickToBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }

  async function loadEarlier() {
    const first = current.messages[0];
    if (!first) return;
    const el = scroller.current;
    const heightBefore = el?.scrollHeight ?? 0;
    try {
      const page = await fetchChatMessages(channel, first.createdAt);
      stickToBottom.current = false;
      setChannels((prev) => {
        const old = prev[channel] ?? EMPTY;
        return { ...prev, [channel]: { ...old, messages: merge(old.messages, page.messages), hasMore: page.hasMore } };
      });
      requestAnimationFrame(() => {
        if (el) el.scrollTop += el.scrollHeight - heightBefore;
      });
    } catch {
      // the button stays; the user can try again
    }
  }

  async function send(text: string) {
    const body = text.trim();
    if (!body || sending) return;
    setSending(true);
    setSendError(false);
    try {
      const sent = await sendChatMessage(channel, body, replyTo?.id);
      stickToBottom.current = true;
      setChannels((prev) => {
        const old = prev[channel] ?? EMPTY;
        return { ...prev, [channel]: { ...old, messages: merge(old.messages, [sent]), loaded: true } };
      });
      setDraft("");
      setReplyTo(null);
    } catch {
      setSendError(true);
    } finally {
      setSending(false);
      composer.current?.focus();
    }
  }

  // The agent always; people only in the team channel (the private one is just you and the agent).
  const mentionCandidates: MentionCandidate[] = [
    { key: "agent", handle: "moses", name: "MoSes", detail: t.chat.mentionAgent, agent: true },
    ...(channel === TEAM
      ? members
          .filter((m) => m.userId !== identity?.userId)
          .map((m) => ({ key: m.userId, handle: m.name, name: m.name, detail: m.email, agent: false }))
      : []),
  ];
  const mentionMatches = mention ? mentionCandidates.filter((c) => matchesMention(c, mention.query)) : [];
  const mentionOpen = mention !== null && mentionMatches.length > 0;

  function trackMention(el: HTMLTextAreaElement) {
    let next = mentionAt(el.value, el.selectionStart ?? el.value.length);
    // A finished mention ("@Dana Cohen ") doesn't reopen the list.
    if (next && /\s$/.test(next.query)) {
      const typed = next.query.trim().toLowerCase();
      if (mentionCandidates.some((c) => c.handle.toLowerCase() === typed)) next = null;
    }
    setMention((prev) => {
      if (prev?.start !== next?.start || prev?.query !== next?.query) setMentionIndex(0);
      return next;
    });
  }

  function pickMention(c: MentionCandidate) {
    const el = composer.current;
    if (!mention || !el) return;
    const caret = el.selectionStart ?? draft.length;
    const inserted = `@${c.handle} `;
    const next = draft.slice(0, mention.start) + inserted + draft.slice(caret);
    const pos = mention.start + inserted.length;
    setDraft(next);
    setMention(null);
    requestAnimationFrame(() => {
      el.focus();
      el.setSelectionRange(pos, pos);
    });
  }

  function onKeyDown(e: KeyboardEvent<HTMLTextAreaElement>) {
    if (mentionOpen) {
      if (e.key === "ArrowDown" || e.key === "ArrowUp") {
        e.preventDefault();
        const step = e.key === "ArrowDown" ? 1 : -1;
        setMentionIndex((i) => (i + step + mentionMatches.length) % mentionMatches.length);
        return;
      }
      if ((e.key === "Enter" || e.key === "Tab") && !e.nativeEvent.isComposing) {
        e.preventDefault();
        pickMention(mentionMatches[Math.min(mentionIndex, mentionMatches.length - 1)]);
        return;
      }
      if (e.key === "Escape") {
        e.preventDefault();
        setMention(null);
        return;
      }
    }
    if (e.key === "Enter" && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      void send(draft);
    }
  }

  function startReply(m: ChatMessage) {
    setReplyTo(m);
    composer.current?.focus();
  }

  const timeFormat = new Intl.DateTimeFormat(lang === "he" ? "he-IL" : "en-GB", { hour: "2-digit", minute: "2-digit" });
  const dateFormat = new Intl.DateTimeFormat(lang === "he" ? "he-IL" : "en-GB", { weekday: "long", day: "numeric", month: "long" });
  const isPrivate = channel !== TEAM;

  if (identityError) {
    return (
      <div className="bg-surface-container-low rounded-2xl p-space-lg text-on-surface-variant font-body-md text-body-md">
        {t.chat.unavailable}
      </div>
    );
  }

  const channelList = [
    { id: TEAM, icon: "groups", title: t.chat.teamChannel, hint: t.chat.teamHint },
    ...(privateChannel ? [{ id: privateChannel, icon: "smart_toy", title: t.chat.privateChannel, hint: t.chat.privateHint }] : []),
  ];

  return (
    <div className="h-[calc(100vh-6rem)] min-h-[420px] flex flex-col md:flex-row gap-space-md">
      {/* Channels */}
      <nav className="md:w-60 shrink-0 bg-surface-container-low rounded-2xl p-space-sm flex md:flex-col gap-1">
        {channelList.map((c) => {
          const active = c.id === channel;
          const count = unread[c.id] ?? 0;
          return (
            <button
              key={c.id}
              className={`flex-1 md:flex-none flex items-center gap-space-sm px-space-sm py-space-sm rounded-xl text-left transition-colors ${
                active ? "bg-surface-container-high text-on-surface" : "text-on-surface-variant hover:bg-surface-container hover:text-on-surface"
              }`}
              onClick={() => setChannel(c.id)}
              type="button"
            >
              <Icon name={c.icon} size={20} className={active ? "text-secondary" : ""} />
              <span className="min-w-0 flex-1">
                <span className="block font-headline text-headline-sm truncate">{c.title}</span>
                <span className="hidden md:block font-body-sm text-body-sm text-on-surface-variant truncate">{c.hint}</span>
              </span>
              {typing[c.id] && !active && <span className="w-2 h-2 rounded-full bg-secondary animate-pulse" />}
              {count > 0 && (
                <span className="min-w-5 h-5 px-1.5 rounded-full bg-secondary text-on-secondary font-label-sm text-label-sm flex items-center justify-center">
                  {count}
                </span>
              )}
            </button>
          );
        })}
      </nav>

      {/* Conversation */}
      <section className="flex-1 min-w-0 min-h-0 bg-surface-container-low rounded-2xl flex flex-col overflow-hidden">
        <header className="flex items-center justify-between gap-space-md px-space-md py-space-sm border-b border-outline-variant/40">
          <div className="min-w-0">
            <h1 className="font-headline text-headline-md text-on-surface truncate flex items-center gap-space-sm">
              {isPrivate ? t.chat.privateChannel : t.chat.teamChannel}
              {currentEnvironment && (
                <span className="flex items-center gap-1 px-space-sm py-0.5 rounded-lg bg-secondary/15 text-secondary font-code text-code-sm" title={t.chat.environmentHint}>
                  <Icon name="layers" size={14} />
                  {currentEnvironment}
                </span>
              )}
            </h1>
            <p className="font-body-sm text-body-sm text-on-surface-variant truncate">
              {connection === "live" ? (isPrivate ? t.chat.privateHint : t.chat.teamHint) : t.chat.reconnecting}
            </p>
          </div>
          {currentProject?.repoUrl ? (
            <a
              className="shrink-0 flex items-center gap-1.5 px-space-md py-1.5 rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors"
              href={currentProject.repoUrl}
              rel="noreferrer noopener"
              target="_blank"
              title={currentProject.repoUrl}
            >
              <Icon name="code" size={16} className="text-secondary" />
              GitHub
            </a>
          ) : (
            <span className="hidden sm:block shrink-0 font-body-sm text-body-sm text-on-surface-variant">{t.chat.noRepo}</span>
          )}
        </header>

        <div ref={scroller} onScroll={onScroll} className="flex-1 min-h-0 overflow-y-auto px-space-md py-space-md flex flex-col gap-2">
          {current.hasMore && (
            <button
              className="self-center px-space-md py-1 rounded-full bg-surface-container-high hover:bg-surface-bright text-on-surface-variant font-body-sm text-body-sm"
              onClick={loadEarlier}
              type="button"
            >
              {t.chat.loadEarlier}
            </button>
          )}

          {!current.loaded && <p className="m-auto font-body-md text-body-md text-on-surface-variant">{t.chat.loading}</p>}

          {current.loaded && current.messages.length === 0 && (
            <EmptyState isPrivate={isPrivate} onPick={(q) => void send(q)} />
          )}

          {current.messages.map((m, i) => {
            const prev = current.messages[i - 1];
            const newDay = !prev || dayKey(prev.createdAt) !== dayKey(m.createdAt);
            const mine = m.authorType === "USER" && m.authorId === identity?.userId;
            const grouped = !newDay && prev && prev.authorId === m.authorId && !m.replyTo;
            return (
              <div key={m.id} className="flex flex-col">
                {newDay && (
                  <div className="self-center my-space-sm px-space-md py-0.5 rounded-full bg-surface-container text-on-surface-variant font-label-sm text-label-sm">
                    {dateFormat.format(new Date(m.createdAt))}
                  </div>
                )}
                {m.authorType === "SYSTEM" ? (
                  <SystemAlert message={m} time={timeFormat.format(new Date(m.createdAt))} onReply={() => startReply(m)} />
                ) : (
                <MessageBubble
                  message={m}
                  mine={mine}
                  grouped={Boolean(grouped)}
                  time={timeFormat.format(new Date(m.createdAt))}
                  youLabel={t.chat.you}
                  replyLabel={t.chat.reply}
                  onReply={() => startReply(m)}
                />
                )}
              </div>
            );
          })}

          {typing[channel] && (
            <div className="flex items-center gap-space-sm text-on-surface-variant font-body-sm text-body-sm">
              <AnimatedLogo size={28} loop />
              <span className="flex gap-1">
                <span className="w-1.5 h-1.5 rounded-full bg-secondary animate-bounce [animation-delay:-0.3s]" />
                <span className="w-1.5 h-1.5 rounded-full bg-secondary animate-bounce [animation-delay:-0.15s]" />
                <span className="w-1.5 h-1.5 rounded-full bg-secondary animate-bounce" />
              </span>
              {t.chat.agentWorking}
            </div>
          )}
        </div>

        {/* Composer */}
        <div className="border-t border-outline-variant/40 p-space-sm flex flex-col gap-space-xs">
          {replyTo && (
            <div className="flex items-start gap-space-sm px-space-sm py-1.5 rounded-xl bg-surface-container border-l-2 border-secondary">
              <div className="min-w-0 flex-1">
                <div className="font-label-sm text-label-sm text-secondary">
                  {t.chat.replyingTo.replace("{name}", replyTo.authorName)}
                </div>
                <div dir="auto" className="font-body-sm text-body-sm text-on-surface-variant truncate">{replyTo.text}</div>
              </div>
              <button className="text-on-surface-variant hover:text-on-surface" onClick={() => setReplyTo(null)} title={t.chat.cancel} type="button">
                <Icon name="close" size={16} />
              </button>
            </div>
          )}
          {sendError && <p className="px-space-sm font-body-sm text-body-sm text-error">{t.chat.sendFailed}</p>}
          <div className="relative flex items-end gap-space-sm">
            {mentionOpen && (
              <ul
                className="absolute bottom-full left-0 mb-space-xs w-72 max-w-full max-h-64 overflow-y-auto bg-surface-container-highest rounded-xl shadow-xl py-1 z-10"
                role="listbox"
                aria-label={t.chat.mentionTitle}
              >
                {mentionMatches.map((c, i) => {
                  const active = i === Math.min(mentionIndex, mentionMatches.length - 1);
                  return (
                    <li key={c.key} role="option" aria-selected={active}>
                      <button
                        type="button"
                        className={`w-full flex items-center gap-space-sm px-space-sm py-1.5 text-left transition-colors ${
                          active ? "bg-surface-bright" : "hover:bg-surface-bright"
                        }`}
                        // mousedown, so the textarea keeps focus and its caret
                        onMouseDown={(e) => {
                          e.preventDefault();
                          pickMention(c);
                        }}
                        onMouseEnter={() => setMentionIndex(i)}
                      >
                        {c.agent ? (
                          <AnimatedLogo size={28} />
                        ) : (
                          <span className="w-7 h-7 shrink-0 rounded-full bg-surface-container text-on-surface flex items-center justify-center font-label-sm text-label-sm">
                            {c.name.trim().slice(0, 1).toUpperCase()}
                          </span>
                        )}
                        <span className="min-w-0">
                          <span className={`block font-body-md text-body-md truncate ${c.agent ? "text-secondary" : "text-on-surface"}`}>{c.name}</span>
                          <span className="block font-body-sm text-body-sm text-on-surface-variant truncate">{c.detail}</span>
                        </span>
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}
            <textarea
              ref={composer}
              dir="auto"
              rows={1}
              value={draft}
              maxLength={4000}
              onChange={(e) => {
                setDraft(e.target.value);
                e.target.style.height = "auto";
                e.target.style.height = `${Math.min(e.target.scrollHeight, 160)}px`;
                trackMention(e.target);
              }}
              onSelect={(e) => trackMention(e.currentTarget)}
              onBlur={() => setMention(null)}
              onKeyDown={onKeyDown}
              placeholder={isPrivate ? t.chat.placeholderPrivate : t.chat.placeholderTeam}
              className="flex-1 min-w-0 resize-none bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant"
            />
            <button
              className="shrink-0 w-10 h-10 rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container flex items-center justify-center transition-all disabled:opacity-40"
              disabled={!draft.trim() || sending}
              onClick={() => void send(draft)}
              title={t.chat.send}
              type="button"
            >
              <Icon name="send" size={18} />
            </button>
          </div>
        </div>
      </section>
    </div>
  );

  function EmptyState({ isPrivate: priv, onPick }: { isPrivate: boolean; onPick: (q: string) => void }) {
    return (
      <div className="m-auto max-w-md flex flex-col items-center text-center gap-space-md py-space-lg">
        <AnimatedLogo size={56} />
        <p className="font-body-md text-body-md text-on-surface-variant">{priv ? t.chat.emptyPrivate : t.chat.emptyTeam}</p>
        <div className="flex flex-wrap justify-center gap-space-xs">
          {(priv ? t.chat.suggestionsPrivate : t.chat.suggestionsTeam).map((q) => (
            <button
              key={q}
              className="px-space-md py-1.5 rounded-full bg-surface-container-high hover:bg-surface-bright text-on-surface font-body-sm text-body-sm transition-colors"
              onClick={() => onPick(q)}
              type="button"
            >
              {q}
            </button>
          ))}
        </div>
      </div>
    );
  }
}

interface BubbleProps {
  message: ChatMessage;
  mine: boolean;
  grouped: boolean;
  time: string;
  youLabel: string;
  replyLabel: string;
  onReply: () => void;
}

/**
 * An alert MoSes posted itself (an incident opened or resolved), shown from its structured event
 * in the reader's language. Replying to it with @moses hands the alert to the agent.
 */
function SystemAlert({ message: m, time, onReply }: { message: ChatMessage; time: string; onReply: () => void }) {
  const { t, lang } = useLanguage();
  const e = m.event;
  const opened = e?.type === "INCIDENT_OPENED";
  let text = m.text;
  if (e) {
    if (!opened) {
      text = fillTemplate(t.chat.alertBackToNormal, {
        service: e.serviceName,
        duration: formatDuration(e.durationSeconds ?? 0, lang),
      });
    } else if (e.incidentKind === "FLOW_BROKEN") {
      text = fillTemplate(t.chat.alertFlow, {
        entry: e.flowEntry ?? "",
        target: e.serviceName,
        broken: e.brokenTraces ?? 0,
        checked: e.checkedTraces ?? 0,
        window: e.windowMinutes ?? 5,
        seconds: e.flowMaxSeconds ?? 0,
      });
      if (e.stoppedAt) text += ". " + fillTemplate(t.chat.alertFlowStopped, { service: e.stoppedAt });
    } else if (e.incidentKind === "SERVICE_DOWN") {
      text = fillTemplate(t.chat.alertDown, { service: e.serviceName });
    } else {
      text = fillTemplate(t.chat.alertSpike, {
        service: e.serviceName,
        errors: e.errors ?? 0,
        window: e.windowMinutes ?? 5,
        baseline: e.baseline ?? 0,
      });
    }
  }
  return (
    <div className="group flex items-start gap-space-sm mt-1.5">
      <div
        className={`flex-1 max-w-[min(42rem,100%)] rounded-xl px-space-md py-space-sm border ${
          opened ? "border-error/50 bg-error-container/15" : "border-secondary/40 bg-secondary/10"
        }`}
      >
        <div className={`flex items-center gap-1.5 font-label-sm text-label-sm ${opened ? "text-error" : "text-secondary"}`}>
          <Icon name={opened ? "e911_emergency" : "check_circle"} size={16} />
          {opened ? t.chat.alertOpened : t.chat.alertResolved}
          <span className="text-on-surface-variant opacity-80">· {m.authorName}</span>
        </div>
        <div dir="auto" className="font-body-md text-body-md text-on-surface mt-0.5">{text}</div>
        {opened && e?.topError && (
          <div className="mt-1 font-body-sm text-body-sm text-on-surface-variant">
            {t.chat.alertTopError}:{" "}
            <span dir="ltr" className="font-code text-code-sm text-on-surface break-all">{e.topError}</span>
          </div>
        )}
        {opened && (e?.seenBefore ?? 0) > 0 && (
          <div className="mt-1 font-body-sm text-body-sm border-s-2 border-secondary/60 ps-space-sm">
            <span className="text-secondary">{e?.seenBefore === 1 ? t.chat.alertSeenOnce : fillTemplate(t.chat.alertSeenBefore, { n: e?.seenBefore ?? 0 })}</span>
            {e?.lastFix && (
              <>
                {" · "}
                <span className="text-on-surface-variant">{t.chat.alertLastFix}: </span>
                <span dir="auto" className="text-on-surface">{e.lastFix}</span>
              </>
            )}
          </div>
        )}
        <div className="mt-1 flex flex-wrap items-center gap-space-sm font-label-sm text-[11px]">
          <Link to="../incidents" relative="path" className="text-secondary hover:underline">
            {t.chat.alertOpenIncidents}
          </Link>
          {opened && <span className="text-on-surface-variant opacity-80">{t.chat.alertAskHint}</span>}
          <span className="ms-auto opacity-60">{time}</span>
        </div>
      </div>
      <button
        className="opacity-0 group-hover:opacity-100 focus:opacity-100 transition-opacity text-on-surface-variant hover:text-on-surface p-1 rounded-lg"
        onClick={onReply}
        title={t.chat.reply}
        type="button"
      >
        <Icon name="reply" size={16} />
      </button>
    </div>
  );
}

function MessageBubble({ message: m, mine, grouped, time, youLabel, replyLabel, onReply }: BubbleProps) {
  const agent = m.authorType === "AGENT";
  const bubble = mine
    ? "bg-primary-container text-on-primary-container rounded-br-md"
    : agent
      ? `bg-surface-container text-on-surface rounded-bl-md border ${m.failed ? "border-error/50" : "border-secondary/30"}`
      : "bg-surface-container-high text-on-surface rounded-bl-md";

  return (
    <div className={`group flex items-end gap-space-sm ${mine ? "flex-row-reverse" : ""} ${grouped ? "" : "mt-1.5"}`}>
      {!mine && (
        <div className="w-7 shrink-0">
          {!grouped &&
            (agent ? (
              <AnimatedLogo size={28} />
            ) : (
              <div className="w-7 h-7 rounded-full bg-surface-container-highest text-on-surface flex items-center justify-center font-label-sm text-label-sm">
                {m.authorName.trim().slice(0, 1).toUpperCase()}
              </div>
            ))}
        </div>
      )}
      <div className={`max-w-[min(42rem,85%)] rounded-2xl px-space-md py-space-sm font-body-md text-body-md ${bubble}`}>
        {!grouped && !mine && (
          <div className={`font-label-sm text-label-sm mb-0.5 ${agent ? "text-secondary" : "text-tertiary"}`}>{m.authorName}</div>
        )}
        {m.replyTo && (
          <div className="mb-1.5 px-space-sm py-1 rounded-lg bg-black/15 border-l-2 border-secondary">
            <div className="font-label-sm text-label-sm opacity-80">{m.replyTo.authorType === "USER" && mine ? youLabel : m.replyTo.authorName}</div>
            <div dir="auto" className="font-body-sm text-body-sm opacity-80 line-clamp-2">{m.replyTo.text}</div>
          </div>
        )}
        <div className={m.failed ? "text-error" : ""}>
          <ChatMarkdown text={m.text} />
        </div>
        <div className={`mt-0.5 font-label-sm text-[11px] opacity-60 ${mine ? "text-right" : ""}`}>{time}</div>
      </div>
      <button
        className="opacity-0 group-hover:opacity-100 focus:opacity-100 transition-opacity text-on-surface-variant hover:text-on-surface p-1 rounded-lg"
        onClick={onReply}
        title={replyLabel}
        type="button"
      >
        <Icon name="reply" size={16} />
      </button>
    </div>
  );
}
