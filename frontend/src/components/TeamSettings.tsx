import { useEffect, useState, type FormEvent } from "react";
import { addMember, ApiError, createInvite, fetchMembers, type ProjectMember } from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

/** The current project's team: who is in it, and (for the owner) the two ways to bring people in. */
export default function TeamSettings() {
  const { currentProject, reload } = useProjects();
  const { t } = useLanguage();
  const projectId = currentProject?.id ?? null;
  const isOwner = currentProject?.role === "OWNER";

  const [members, setMembers] = useState<ProjectMember[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [email, setEmail] = useState("");
  const [addError, setAddError] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);
  const [inviteLink, setInviteLink] = useState<string | null>(null);
  const [inviteError, setInviteError] = useState(false);
  const [copied, setCopied] = useState(false);

  useEffect(() => {
    setMembers(null);
    setLoadFailed(false);
    setInviteLink(null);
    setAddError(null);
    if (!projectId) return;
    let cancelled = false;
    fetchMembers(projectId)
      .then((loaded) => {
        if (!cancelled) setMembers(loaded);
      })
      .catch(() => {
        if (!cancelled) setLoadFailed(true);
      });
    return () => {
      cancelled = true;
    };
  }, [projectId]);

  if (!projectId) return null;

  async function handleAdd(event: FormEvent) {
    event.preventDefault();
    const value = email.trim();
    if (!value || adding || !projectId) return;
    setAdding(true);
    setAddError(null);
    try {
      setMembers(await addMember(projectId, value));
      setEmail("");
      void reload();
    } catch (e) {
      setAddError(e instanceof ApiError && e.status === 404 ? t.team.noSuchUser : t.team.addFailed);
    } finally {
      setAdding(false);
    }
  }

  async function handleCreateInvite() {
    if (!projectId) return;
    setInviteError(false);
    setCopied(false);
    try {
      const invite = await createInvite(projectId);
      setInviteLink(`${window.location.origin}/join/${invite.token}`);
    } catch {
      setInviteError(true);
    }
  }

  async function handleCopy() {
    if (!inviteLink) return;
    try {
      await navigator.clipboard.writeText(inviteLink);
      setCopied(true);
    } catch {
      // clipboard blocked: the link is on screen and selectable
    }
  }

  return (
    <div className="bg-surface-container-low rounded-2xl shadow-sm p-space-lg">
      <h2 className="font-headline text-headline-md text-on-surface">{t.team.title}</h2>
      <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-xs mb-space-md">{t.team.hint}</p>

      {loadFailed && <p className="font-body-sm text-body-sm text-error">{t.team.loadFailed}</p>}
      {!members && !loadFailed && (
        <p className="font-body-sm text-body-sm text-on-surface-variant">{t.team.loading}</p>
      )}

      {members && (
        <ul className="flex flex-col gap-space-xs">
          {members.map((m) => (
            <li
              key={m.userId}
              className="flex items-center justify-between gap-space-md px-space-md py-space-sm rounded-xl bg-surface-container"
            >
              <div className="min-w-0">
                <div className="font-body-md text-body-md text-on-surface truncate">{m.name}</div>
                <div className="font-body-sm text-body-sm text-on-surface-variant truncate">{m.email}</div>
              </div>
              <span className="shrink-0 font-label-sm text-label-sm uppercase text-on-surface-variant">
                {m.role === "OWNER" ? t.team.roleOwner : t.team.roleMember}
              </span>
            </li>
          ))}
        </ul>
      )}

      {isOwner ? (
        <div className="mt-space-md flex flex-col gap-space-md">
          <form className="flex flex-col gap-space-xs" onSubmit={handleAdd}>
            <label className="font-body-sm text-body-sm text-on-surface-variant" htmlFor="team-add-email">
              {t.team.addByEmail}
            </label>
            <div className="flex items-center gap-space-sm">
              <input
                id="team-add-email"
                className="flex-1 min-w-0 bg-surface-container-highest text-on-surface font-body-md text-body-md px-space-md py-space-sm rounded-xl focus:outline-none focus:ring-1 focus:ring-secondary placeholder:text-on-surface-variant"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder={t.team.emailPlaceholder}
              />
              <button
                className="shrink-0 px-space-md py-space-sm rounded-xl bg-primary-container hover:bg-inverse-primary text-on-primary-container font-headline text-headline-sm transition-all disabled:opacity-40"
                disabled={!email.trim() || adding}
                type="submit"
              >
                {t.team.add}
              </button>
            </div>
            {addError && (
              <p className="font-body-sm text-body-sm text-error" role="alert">
                {addError}
              </p>
            )}
          </form>

          <div className="flex flex-col gap-space-xs">
            <span className="font-body-sm text-body-sm text-on-surface-variant">{t.team.inviteHint}</span>
            {inviteLink && (
              <div className="flex items-center gap-space-sm">
                <input
                  className="flex-1 min-w-0 bg-surface-container-highest text-on-surface font-code text-code-sm px-space-md py-space-sm rounded-xl focus:outline-none"
                  aria-label={t.team.inviteLink}
                  onFocus={(e) => e.target.select()}
                  readOnly
                  value={inviteLink}
                />
                <button
                  className="shrink-0 flex items-center gap-1 px-space-md py-space-sm rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors"
                  onClick={handleCopy}
                  type="button"
                >
                  <Icon name={copied ? "check" : "content_copy"} size={16} />
                  {copied ? t.team.copied : t.team.copy}
                </button>
              </div>
            )}
            <button
              className="w-fit flex items-center gap-1.5 px-space-md py-space-sm rounded-xl bg-surface-container-high hover:bg-surface-bright text-on-surface font-headline text-headline-sm transition-colors"
              onClick={handleCreateInvite}
              type="button"
            >
              <Icon name="link" size={18} className="text-secondary" />
              {inviteLink ? t.team.newInviteLink : t.team.createInviteLink}
            </button>
            {inviteError && (
              <p className="font-body-sm text-body-sm text-error" role="alert">
                {t.team.inviteFailed}
              </p>
            )}
          </div>
        </div>
      ) : (
        <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-md">{t.team.ownerOnly}</p>
      )}
    </div>
  );
}
