package com.moses.monitor.chat;

import java.util.Locale;
import java.util.regex.Pattern;

/** The two kinds of conversation in a project, and when the agent should answer in them. */
final class Channels {

    static final String TEAM = "team";
    static final String AGENT_PREFIX = "agent:";

    /** "@moses" as its own word, any case. */
    private static final Pattern MENTION = Pattern.compile("(^|[^\\w@])@moses\\b", Pattern.CASE_INSENSITIVE);

    private Channels() {
    }

    static String privateWithAgent(String userId) {
        return AGENT_PREFIX + userId;
    }

    /** A user may use the team channel and their own private channel - never someone else's. */
    static boolean mayUse(String channel, String userId) {
        return TEAM.equals(channel) || privateWithAgent(userId).equals(channel);
    }

    static boolean isPrivate(String channel) {
        return channel != null && channel.startsWith(AGENT_PREFIX);
    }

    /** The user whose private channel this is, or null for the team channel. */
    static String ownerOf(String channel) {
        return isPrivate(channel) ? channel.substring(AGENT_PREFIX.length()) : null;
    }

    /**
     * In a private channel every message is for the agent. In the team channel only a message
     * that tags @moses, or that replies to something the agent wrote, is - otherwise the agent
     * would butt into every conversation the team has.
     */
    static boolean addressesAgent(String channel, String text, ChatMessage.ReplyPreview replyTo) {
        if (isPrivate(channel)) {
            return true;
        }
        if (replyTo != null && replyTo.authorType() == ChatMessage.AuthorType.AGENT) {
            return true;
        }
        return text != null && MENTION.matcher(text.toLowerCase(Locale.ROOT)).find();
    }
}
