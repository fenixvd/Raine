#pragma once
#include "OpenAITools.h"
#include "telegram/ITelegramClient.h"

namespace tools {
/**
 * @brief Tool that lets Kuni join a group chat/channel she was not a member of.
 *
 * Mirrors the official Telegram client behavior: opening a group chat/channel you haven't joined only shows a
 * "Join" button (plus the ability to react to messages) instead of a text field. Once joined, the full set of
 * chat tools becomes available the next time the chat is opened.
 *
 * @param telegram  The Telegram client.
 * @param chat      The chat currently opened.
 */
OpenAITools::Tool joinChat(_<ITelegramClient> telegram, _<td::td_api::chat> chat);
}
