"""Team and private chat: access, replies, and where the agent's answers land."""
import time

from support import RUN, call_json as call, check, finish, register, register_with_id, url, use_fake_model, wait_for

A, a_id = register_with_id("Dana")
B, b_id = register_with_id("Omer")
C, _ = register("Stranger")

s, p = call("POST", "/api/projects", {"name": "Chat"}, token=A)
P = p["id"]
check("repo: local path refused", call("PUT", f"/api/projects/{P}/repo", {"repoUrl": "C:\\Users"}, token=A)[0], 400)
check("repo: non-github refused", call("PUT", f"/api/projects/{P}/repo", {"repoUrl": "https://gitlab.com/a/b"}, token=A)[0], 400)
s, p = call("PUT", f"/api/projects/{P}/repo", {"repoUrl": "https://github.com/example/shop.git"}, token=A)
check("repo: github saved (normalized)", (s, p.get("repoUrl")), (200, "https://github.com/example/shop"))

s, inv = call("POST", f"/api/projects/{P}/invites", token=A)
call("POST", f"/api/invites/{inv['token']}/accept", token=B)
check("member cannot set repo", call("PUT", f"/api/projects/{P}/repo", {"repoUrl": ""}, token=B)[0], 403)

s, me = call("GET", "/api/chat/me", token=A, project=P)
check("chat identity", (s, me["privateChannel"]), (200, "agent:" + a_id))
check("no token -> 401", call("GET", "/api/chat/messages?channel=team", project=P)[0], 401)
check("stranger -> 404", call("GET", "/api/chat/messages?channel=team", token=C, project=P)[0], 404)
check("B cannot read A's private chat", call("GET", f"/api/chat/messages?channel=agent:{a_id}", token=B, project=P)[0], 404)
check("unknown channel -> 404", call("GET", "/api/chat/messages?channel=general", token=A, project=P)[0], 404)
check("direct chat-service, forged project -> 404",
      call("GET", "/api/chat/messages?channel=team", token=C, project=P, base=url("chat"))[0], 404)

s, m1 = call("POST", "/api/chat/messages", {"channel": "team", "text": "בוקר טוב צוות"}, token=A, project=P)
check("team message sent", s, 200)
s, page = call("GET", "/api/chat/messages?channel=team", token=B, project=P)
check("B sees it", [m["text"] for m in page["messages"]], ["בוקר טוב צוות"])
s, m2 = call("POST", "/api/chat/messages", {"channel": "team", "text": "agreed", "replyToId": m1["id"]}, token=B, project=P)
check("reply carries preview", (s, m2["replyTo"]["authorName"]), (200, "Dana"))
check("empty text refused", call("POST", "/api/chat/messages", {"channel": "team", "text": "  "}, token=A, project=P)[0], 400)
time.sleep(4)
s, page = call("GET", "/api/chat/messages?channel=team", token=A, project=P)
check("agent stays quiet without @moses", [m for m in page["messages"] if m["authorType"] == "AGENT"], [])

# The fake provider has no tool use, which the agent needs today: what matters here is that the
# answer (that explanation) reaches the right channel and only that one. The repository is unset
# first, so nothing gets cloned.
call("PUT", f"/api/projects/{P}/repo", {"repoUrl": ""}, token=A)
check("fake model configured", use_fake_model(P, A, f"fake-chat-{RUN}")[0], 200)


def agent_messages(channel, token=A):
    s, page = call("GET", f"/api/chat/messages?channel={channel}", token=token, project=P)
    return [m for m in page["messages"] if m["authorType"] == "AGENT"]


def explains_tool_limit(messages):
    return bool(messages) and messages[-1]["failed"] and "can't use tools yet" in messages[-1]["text"]


call("POST", "/api/chat/messages", {"channel": f"agent:{a_id}", "text": "Where is the login token signed?"}, token=A, project=P)
private = wait_for(lambda: agent_messages(f"agent:{a_id}"), 60)
check("agent replies in the private chat", explains_tool_limit(private), True)
check("private answer not in team channel", agent_messages("team", B), [])

call("POST", "/api/chat/messages", {"channel": "team", "text": "@moses מה השתנה לאחרונה?"}, token=B, project=P)
in_team = wait_for(lambda: agent_messages("team"), 60)
check("agent replies in the team chat when mentioned", explains_tool_limit(in_team), True)
check("the team reply is visible to the other member", agent_messages("team", B) == in_team, True)

finish(expected=20)
