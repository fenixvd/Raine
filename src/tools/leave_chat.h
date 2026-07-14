#pragma once
#include "OpenAITools.h"
#include "telegram/ITelegramClient.h"

namespace tools {
/**
 * @brief Tool that lets Kuni leave a group chat (basic group / supergroup), permanently deleting it if possible.
 *
 * Unlike #remove_and_ban_chat (which is a punitive block for rude/needy people), this tool is meant for group
 * chats that Kuni consistently feels are not worth her attention (offensive, low-value, or simply not something
 * she wants to be part of). It tries to fully delete the chat (removes it for all members, requires owner
 * privileges); if that's not possible, it just removes Kuni from the chat members.
 *
 * @param telegram   The Telegram client.
 * @param chat       The chat currently opened.
 */
OpenAITools::Tool leaveChat(_<ITelegramClient> telegram, _<td::td_api::chat> chat);
}
