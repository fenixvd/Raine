//
// Created by kuni on 14 Jul 2026.
//

#include "leave_chat.h"

#include "util/json_utils.h"

static constexpr auto LOG_TAG = "tools::leaveChat";

OpenAITools::Tool tools::leaveChat(_<ITelegramClient> telegram, _<td::td_api::chat> chat) {
    return {
        .name = "leave_chat",
        .description = "Permanently leaves the current group chat \"{}\". Use this if you consistently feel this group"
                       "chat is offensive to you or "
                       "otherwise not worth your time (e.g., participants are consistently rude, toxic, or the "
                       "chat brings no value to you). This is a serious, irreversible decision - use #ask "
                       "beforehand to double check your reasoning if unsure. This tool is only available in "
                       "group chats; for a private chat/DM with a specific person, use #remove_and_ban_chat "
                       "instead."_format(chat->title_),
        .parameters = {},
        .handler = [telegram, chat](OpenAITools::Ctx ctx) -> AFuture<AString> {
            switch (chat->type_->get_id()) {
                case td::td_api::chatTypeBasicGroup::ID:
                case td::td_api::chatTypeSupergroup::ID:
                    break;
                default:
                    co_return "Error: leave_chat can only be used in group chats.";
            }

            ALogger::info(LOG_TAG) << "Leaving chat " << chat->id_ << " (\"" << chat->title_ << "\")";

            // leaveChat: removes the current user from chat members. Private and secret chats can't be left
            // using this method (already excluded above).
            co_await telegram->sendQueryWithResult(ITelegramClient::toPtr(td::td_api::leaveChat(chat->id_)));
            co_return "You left \"{}\"."_format(chat->title_);
        },
    };
}
