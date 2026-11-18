import { Fragment, type ReactNode } from "react";

/**
 * The small slice of Markdown chat messages use: fenced code blocks, `inline code`, **bold**,
 * [links](https://...), bare URLs, bullet/numbered lists and # headings. Built as React elements
 * - never as HTML - so nothing in a message (or in what the agent quotes from logs) can inject
 * markup. Links open only http(s) addresses, in a new tab.
 */

const INLINE = /(`[^`\n]+`)|(\*\*[^*\n]+\*\*)|(\[[^\]\n]+\]\((https?:\/\/[^)\s]+)\))|(https?:\/\/[^\s<>()]+[^\s<>().,;:!?'"])|(@moses\b)/gi;

function inline(text: string, keyBase: string): ReactNode[] {
  const out: ReactNode[] = [];
  let last = 0;
  let i = 0;
  for (const m of text.matchAll(INLINE)) {
    const start = m.index ?? 0;
    if (start > last) out.push(text.slice(last, start));
    const key = `${keyBase}-${i++}`;
    if (m[1]) {
      out.push(
        <code key={key} className="font-code text-[0.85em] px-1 py-0.5 rounded bg-surface-container-highest text-secondary">
          {m[1].slice(1, -1)}
        </code>
      );
    } else if (m[2]) {
      out.push(<strong key={key}>{m[2].slice(2, -2)}</strong>);
    } else if (m[3]) {
      const label = m[3].slice(1, m[3].indexOf("]("));
      out.push(<ExternalLink key={key} href={m[4]}>{label}</ExternalLink>);
    } else if (m[5]) {
      out.push(<ExternalLink key={key} href={m[5]}>{shortUrl(m[5])}</ExternalLink>);
    } else if (m[6]) {
      out.push(
        <span key={key} className="text-secondary font-medium">
          {m[6]}
        </span>
      );
    }
    last = start + m[0].length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

/** GitHub links read better as "path#L12" than as the whole URL. */
function shortUrl(url: string): string {
  const gh = url.match(/^https:\/\/github\.com\/[^/]+\/[^/]+\/(?:blob|tree)\/[^/]+\/(.+)$/);
  if (gh) return decodeURIComponent(gh[1]);
  const commit = url.match(/^https:\/\/github\.com\/[^/]+\/[^/]+\/commit\/([0-9a-f]{7,40})/i);
  if (commit) return `commit ${commit[1].slice(0, 7)}`;
  return url.replace(/^https?:\/\//, "");
}

function ExternalLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <a className="text-secondary underline underline-offset-2 break-all hover:opacity-80" href={href} rel="noreferrer noopener" target="_blank">
      {children}
    </a>
  );
}

type Block =
  | { kind: "code"; lang: string; text: string }
  | { kind: "list"; ordered: boolean; items: string[] }
  | { kind: "heading"; text: string }
  | { kind: "para"; text: string };

function parse(source: string): Block[] {
  const lines = source.replace(/\r\n/g, "\n").split("\n");
  const blocks: Block[] = [];
  let para: string[] = [];
  const flush = () => {
    if (para.length) blocks.push({ kind: "para", text: para.join("\n") });
    para = [];
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const fence = line.match(/^\s*```(\S*)\s*$/);
    if (fence) {
      flush();
      const body: string[] = [];
      i++;
      while (i < lines.length && !/^\s*```\s*$/.test(lines[i])) body.push(lines[i++]);
      blocks.push({ kind: "code", lang: fence[1], text: body.join("\n") });
      continue;
    }
    const bullet = line.match(/^\s*(?:[-*]|(\d+)[.)])\s+(.*)$/);
    if (bullet) {
      flush();
      const ordered = bullet[1] !== undefined;
      const prev = blocks[blocks.length - 1];
      if (prev && prev.kind === "list" && prev.ordered === ordered) prev.items.push(bullet[2]);
      else blocks.push({ kind: "list", ordered, items: [bullet[2]] });
      continue;
    }
    const heading = line.match(/^\s*#{1,6}\s+(.*)$/);
    if (heading) {
      flush();
      blocks.push({ kind: "heading", text: heading[1] });
      continue;
    }
    if (line.trim() === "") {
      flush();
      continue;
    }
    para.push(line);
  }
  flush();
  return blocks;
}

export default function ChatMarkdown({ text }: { text: string }) {
  return (
    <div className="flex flex-col gap-1.5 [overflow-wrap:anywhere]">
      {parse(text).map((block, b) => {
        const key = `b${b}`;
        switch (block.kind) {
          case "code":
            return (
              <pre key={key} dir="ltr" className="font-code text-code-sm bg-surface-container-lowest rounded-lg p-space-sm overflow-x-auto whitespace-pre text-left">
                <code>{block.text}</code>
              </pre>
            );
          case "list": {
            const Tag = block.ordered ? "ol" : "ul";
            return (
              <Tag key={key} dir="auto" className={`${block.ordered ? "list-decimal" : "list-disc"} ps-5 flex flex-col gap-0.5`}>
                {block.items.map((item, j) => (
                  <li key={j}>{inline(item, `${key}-${j}`)}</li>
                ))}
              </Tag>
            );
          }
          case "heading":
            return (
              <p key={key} dir="auto" className="font-semibold">
                {inline(block.text, key)}
              </p>
            );
          default:
            return (
              <p key={key} dir="auto" className="whitespace-pre-wrap">
                {block.text.split("\n").map((ln, j, all) => (
                  <Fragment key={j}>
                    {inline(ln, `${key}-${j}`)}
                    {j < all.length - 1 && <br />}
                  </Fragment>
                ))}
              </p>
            );
        }
      })}
    </div>
  );
}
