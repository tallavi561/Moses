package com.moses.monitor.chat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelsTest {

    @Test
    void usersReachTheTeamAndOnlyTheirOwnPrivateChannel() {
        assertTrue(Channels.mayUse("team", "u1"));
        assertTrue(Channels.mayUse("agent:u1", "u1"));
        assertFalse(Channels.mayUse("agent:u2", "u1"));
        assertFalse(Channels.mayUse("random", "u1"));
    }

    @Test
    void privateChannelsBelongToOneUser() {
        assertEquals("u1", Channels.ownerOf("agent:u1"));
        assertNull(Channels.ownerOf("team"));
    }

    @Test
    void agentAnswersEverythingInPrivate() {
        assertTrue(Channels.addressesAgent("agent:u1", "anything", null));
    }

    @Test
    void inTheTeamChannelTheAgentNeedsATagOrAReply() {
        assertFalse(Channels.addressesAgent("team", "lunch at 1?", null));
        assertTrue(Channels.addressesAgent("team", "@moses why is checkout slow", null));
        assertTrue(Channels.addressesAgent("team", "hey @MoSes, check this", null));
        assertTrue(Channels.addressesAgent("team", "מה קרה? @moses", null));
        assertFalse(Channels.addressesAgent("team", "mail me at a@moses.dev", null));
        assertFalse(Channels.addressesAgent("team", "@mosesbot", null));

        var toAgent = new ChatMessage.ReplyPreview("m1", "MoSes", ChatMessage.AuthorType.AGENT, "...");
        var toHuman = new ChatMessage.ReplyPreview("m2", "Dana", ChatMessage.AuthorType.USER, "...");
        assertTrue(Channels.addressesAgent("team", "and what about staging?", toAgent));
        assertFalse(Channels.addressesAgent("team", "agreed", toHuman));
    }
}
