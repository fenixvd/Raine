//
// Created by kuni on 14 Jul 2026.
//

#include "join_chat.h"

#include "util/json_utils.h"

static constexpr auto LOG_TAG = "tools::joinChat";

OpenAITools::Tool tools::joinChat(_<ITelegramClient> telegram, _<td::td_api::chat> chat) {
    return {
        .name = "join_chat",
        .description = "Joins the group chat/channel \"{}\", becoming a member. You are currently NOT a member "
                       "of this chat - that's why you can't send messages here yet, only react to them with "
                       "#react_with_emoji. Join if the chat looks interesting/relevant to you."_format(chat->title_),
        .parameters = {},
        .handler = [telegram, chat](OpenAITools::Ctx ctx) -> AFuture<AString> {
            switch (chat->type_->get_id()) {
                case td::td_api::chatTypeBasicGroup::ID:
                case td::td_api::chatTypeSupergroup::ID:
                    break;
                default:
                    co_return "Error: join_chat can only be used in group chats/channels.";
            }

            ALogger::info(LOG_TAG) << "Joining chat " << chat->id_ << " (\"" << chat->title_ << "\")";

            co_await telegram->sendQueryWithResult(ITelegramClient::toPtr(td::td_api::joinChat(chat->id_)));
            co_return "You joined \"{}\". Open the chat again to see the full set of available actions."_format(chat->title_);
        },
    };
}
