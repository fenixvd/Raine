package ru.rainedev.raine.telegram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.tdlight.jni.TdApi;
import org.junit.jupiter.api.Test;

class LockdownTest {

    private static final long OWNER = 1864770113L;
    private static final long STRANGER = 555L;

    private static TdApi.Chat privateChat(long userId) {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = userId;
        chat.type = new TdApi.ChatTypePrivate(userId);
        return chat;
    }

    private static TdApi.Chat channel() {
        TdApi.Chat chat = new TdApi.Chat();
        chat.id = -100L;
        TdApi.ChatTypeSupergroup type = new TdApi.ChatTypeSupergroup();
        type.isChannel = true;
        chat.type = type;
        return chat;
    }

    @Test
    void ownerOnlyKeepsStrangersOut() {
        Lockdown lockdown = new Lockdown(Lockdown.Mode.OWNER_ONLY, OWNER);

        assertTrue(lockdown.allows(privateChat(OWNER), false));
        assertFalse(lockdown.allows(privateChat(STRANGER), false));
        assertFalse(lockdown.allows(privateChat(STRANGER), true), "даже контакт не проходит в строгом режиме");
    }

    @Test
    void contactsOnlyLetsAcquaintancesIn() {
        Lockdown lockdown = new Lockdown(Lockdown.Mode.CONTACTS_ONLY, OWNER);

        assertTrue(lockdown.allows(privateChat(OWNER), false));
        assertTrue(lockdown.allows(privateChat(STRANGER), true));
        assertFalse(lockdown.allows(privateChat(STRANGER), false));
    }

    @Test
    void channelsAreReadableEvenUnderStrictestMode() {
        // канал — лента новостей: ответить туда нельзя, и написать ей оттуда тоже
        assertTrue(new Lockdown(Lockdown.Mode.OWNER_ONLY, OWNER).allows(channel(), false));
    }

    @Test
    void groupChatsAreAllowedAmongContacts() {
        // в группу её позвали, и переписка там общая, а не личная
        TdApi.Chat group = new TdApi.Chat();
        group.id = -200L;
        group.type = new TdApi.ChatTypeBasicGroup();

        assertTrue(new Lockdown(Lockdown.Mode.CONTACTS_ONLY, OWNER).allows(group, false));
        assertFalse(new Lockdown(Lockdown.Mode.OWNER_ONLY, OWNER).allows(group, false),
                "в строгом режиме группы тоже закрыты");
    }

    @Test
    void ownerCannotBeBlocked() {
        // единственный человек, которого она не может закрыть
        assertTrue(new Lockdown(Lockdown.Mode.NONE, OWNER).isOwner(OWNER));
        assertFalse(new Lockdown(Lockdown.Mode.NONE, OWNER).isOwner(STRANGER));
    }

    @Test
    void openModeAllowsEveryone() {
        Lockdown lockdown = new Lockdown(Lockdown.Mode.NONE, OWNER);

        assertTrue(lockdown.allows(privateChat(STRANGER), false));
        assertTrue(lockdown.allowsSender(STRANGER, false));
    }

    @Test
    void incomingMessagesFollowTheSameRule() {
        Lockdown strict = new Lockdown(Lockdown.Mode.OWNER_ONLY, OWNER);

        assertTrue(strict.allowsSender(OWNER, false));
        assertFalse(strict.allowsSender(STRANGER, true));
    }

    @Test
    void unknownSettingFallsBackToStrictest() {
        // опечатка в настройке не должна молча открывать её всему миру
        assertEquals(Lockdown.Mode.OWNER_ONLY, Lockdown.Mode.of("абракадабра"));
        assertEquals(Lockdown.Mode.OWNER_ONLY, Lockdown.Mode.of(null));
        assertEquals(Lockdown.Mode.NONE, Lockdown.Mode.of("none"));
        assertEquals(Lockdown.Mode.CONTACTS_ONLY, Lockdown.Mode.of("contacts_only"));
    }
}
