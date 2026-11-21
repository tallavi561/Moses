import { useEffect, useState, type FormEvent } from "react";
import {
  addMember,
  ApiError,
  changeMemberRole,
  createInvite,
  fetchMembers,
  removeMember,
  type AssignableRole,
  type ProjectMember,
} from "../api";
import { useProjects } from "../projects/ProjectsContext";
import { useLanguage } from "../i18n/LanguageContext";
import { Icon } from "../icons";

/**
 * The current project's team: who is in it and what each may do, and (for the owner) the two
 * ways to bring people in, changing a member's role, and removing them.
 */
export default function TeamSettings() {
  const { currentProject, reload } = useProjects();
  const { t } = useLanguage();
  const projectId = currentProject?.id ?? null;
  const isOwner = currentProject?.role === "OWNER";

  const [members, setMembers] = useState<ProjectMember[] | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [email, setEmail] = useState("");
  const [newRole, setNewRole] = useState<AssignableRole>("EDITOR");
  const [memberError, setMemberError] = useState(false);
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
    setMemberError(false);
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
      setMembers(await addMember(projectId, value, newRole));
      setEmail("");
      void reload();
    } catch (e) {
      setAddError(e instanceof ApiError && e.status === 404 ? t.team.noSuchUser : t.team.addFailed);
    } finally {
      setAdding(false);
    }
  }

  async function handleRoleChange(member: ProjectMember, role: AssignableRole) {
    if (!projectId) return;
    setMemberError(false);
    try {
      setMembers(await changeMemberRole(projectId, member.userId, role));
    } catch {
      setMemberError(true);
    }
  }

  async function handleRemove(member: ProjectMember) {
    if (!projectId || !window.confirm(t.team.removeConfirm.replace("{name}", member.name))) return;
    setMemberError(false);
    try {
      setMembers(await removeMember(projectId, member.userId));
      void reload();
    } catch {
      setMemberError(true);
    }
  }

  const roleLabel = { OWNER: t.team.roleOwner, EDITOR: t.team.roleEditor, VIEWER: t.team.roleViewer };
  const selectClass =
    "shrink-0 bg-surface-container-highest text-on-surface font-body-sm text-body-sm px-space-sm py-1.5 rounded-lg focus:outline-none focus:ring-1 focus:ring-secondary";

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
    <div className="card">
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
              {isOwner && m.role !== "OWNER" ? (
                <div className="shrink-0 flex items-center gap-space-sm">
                  <select
                    className={selectClass}
                    aria-label={t.team.role}
                    value={m.role}
                    onChange={(e) => handleRoleChange(m, e.target.value as AssignableRole)}
                  >
                    <option value="EDITOR">{t.team.roleEditor}</option>
                    <option value="VIEWER">{t.team.roleViewer}</option>
                  </select>
                  <button
                    className="text-on-surface-variant hover:text-error transition-colors p-1 rounded-lg"
                    onClick={() => handleRemove(m)}
                    title={t.team.remove}
                    type="button"
                  >
                    <Icon name="person_remove" size={18} />
                  </button>
                </div>
              ) : (
                <span className="shrink-0 font-label-sm text-label-sm uppercase text-on-surface-variant">
                  {roleLabel[m.role]}
                </span>
              )}
            </li>
          ))}
        </ul>
      )}
      {memberError && (
        <p className="font-body-sm text-body-sm text-error mt-space-sm" role="alert">
          {t.team.changeFailed}
        </p>
      )}
      <p className="font-body-sm text-body-sm text-on-surface-variant mt-space-sm">{t.team.rolesHint}</p>

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
              <select
                className={selectClass}
                aria-label={t.team.role}
                value={newRole}
                onChange={(e) => setNewRole(e.target.value as AssignableRole)}
              >
                <option value="EDITOR">{t.team.roleEditor}</option>
                <option value="VIEWER">{t.team.roleViewer}</option>
              </select>
              <button
                className="shrink-0 px-space-md py-space-sm btn btn-primary disabled:opacity-40"
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
                  className="shrink-0 w-8 h-8 flex items-center justify-center rounded-lg text-on-surface-variant hover:text-on-surface hover:bg-surface-container-high transition-colors"
                  onClick={handleCopy}
                  title={copied ? t.team.copied : t.team.copy}
                  aria-label={copied ? t.team.copied : t.team.copy}
                  type="button"
                >
                  <Icon name={copied ? "check" : "content_copy"} size={18} className={copied ? "text-secondary" : ""} />
                </button>
              </div>
            )}
            <button
              className="w-fit flex items-center gap-1.5 px-space-md py-space-sm btn btn-secondary"
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
