//
// Created by alex2772 on 5/9/26.
//

#include "search_chats.h"

#include "llmui/telegram.h"

#include <range/v3/range/conversion.hpp>
#include <range/v3/view/transform.hpp>

OpenAITools::Tool tools::searchChats(_<ITelegramClient> telegram) {
    return {
        .name = "search_chats",
        .description = "Searches for chats by @username or name.",
        .parameters =
            {
                .properties =
                    {
                        {"query", {.type = "string", .description = "The username or name of the "
                            "chat. Examples: \n"
                            "- @alex2772sc\n"
                            "- Alex2772\n"
                        }},
                    },
                .required = {"query"},
            },
        .handler = [telegram = std::move(telegram)](OpenAITools::Ctx ctx) -> AFuture<AString> {
            auto query = ctx.args["query"].asStringOpt().valueOrException("query string is required");
            if (query.startsWith("@")) {
                query = query.substr(1);
            }


            AString result;

            try {
                auto queryResult = co_await telegram->sendQueryWithResult(ITelegramClient::toPtr(td::td_api::searchChatsOnServer(query, 50)));
                auto chatFutures =
                    queryResult->chat_ids_ | ranges::view::transform([&](td::td_api::int53 chatId) {
                        return telegram->getChat(chatId);
                    }) |
                    ranges::to_vector;

                AVector<_<td::td_api::chat>> chats;
                chats.reserve(chatFutures.size());
                for (const auto& chat : chatFutures) {
                    chats.push_back(co_await chat);
                }
                if (!chats.empty()) {
                    result += "<existing_chats comment=\"Chats that you participate already\">\n";
                    co_await llmui::formatChatList(*telegram, result, chats);
                    result += "</existing_chats>\n";
                }
            } catch (const AException& e) {}

            try {
                auto usernameQueryResult = co_await telegram->sendQueryWithResult(ITelegramClient::toPtr(td::td_api::searchPublicChat(query)));
                if (usernameQueryResult->id_ != 0) {
                    auto publicChat = co_await telegram->getChat(usernameQueryResult->id_);

                    result += "<global_search comment=\"Chats that don't know about you\">\n";
                    co_await llmui::formatChatSingle(*telegram, result, *publicChat);
                    result += "</global_search>\n";
                }
            } catch (const AException& e) {}

            if (result.empty()) {
                co_return "No chats found satisfying your query.";
            }

            co_return result;
        },
    };
}
