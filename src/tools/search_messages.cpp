//
// search_messages: verbatim message search across chats (or scoped to a single chat/sender/date range),
// analogous to the search feature in the official Telegram client. Does not rely on the diary/embeddings.
//

#include "search_messages.h"

#include "llmui/telegram.h"
#include "util/is_accessible_from_lockdown.h"
#include "util/json_utils.h"

using namespace std::chrono_literals;

namespace {
constexpr auto LOG_TAG = "search_messages";
constexpr int32_t DEFAULT_LIMIT = 20;
constexpr int32_t MAX_LIMIT = 50;

int64_t extractSenderId(const td::td_api::MessageSender& sender) {
    int64_t result = 0;
    td::td_api::downcast_call(
        const_cast<td::td_api::MessageSender&>(sender),
        aui::lambda_overloaded {
          [&](td::td_api::messageSenderUser& user) { result = user.user_id_; },
          [&](td::td_api::messageSenderChat& chat) { result = chat.chat_id_; },
        });
    return result;
}

int32_t daysAgoToUnixDate(AOptional<long long> daysAgo) {
    if (!daysAgo) {
        return 0;   // unbounded, per tdlib convention.
    }
    auto tp = std::chrono::system_clock::now() - std::chrono::days(*daysAgo);
    return static_cast<int32_t>(std::chrono::duration_cast<std::chrono::seconds>(tp.time_since_epoch()).count());
}

AFuture<int64_t> resolveChatOrUserId(ITelegramClient& telegram, AString query) {
    if (query.startsWith("@")) {
        query = query.substr(1);
    }
    auto chat = co_await telegram.sendQueryWithResult(ITelegramClient::toPtr(td::td_api::searchPublicChat(query)));
    co_return chat->id_;
}
}   // namespace

OpenAITools::Tool tools::searchMessages(_<ITelegramClient> telegram, _<IOpenAIChat> openAI, const IOpenAIChat::Session& temporaryContext) {
    return {
        .name = "search_messages",
        .description =
            "Searches for messages by their verbatim text content, just like the search feature in the official "
            "Telegram client. Unlike #ask, this does NOT use the diary/memory system - it searches the raw message "
            "text as stored by Telegram, so it's useful for finding an exact phrase, link, or quote that was said "
            "in a chat.\n"
            "- Omit chat_id to search across all of your chats.\n"
            "- Provide chat_id to scope the search to one specific chat (also allows filtering by sender_id).\n"
            "- sender_id may be a numeric user_id or a @username. When chat_id is omitted, the sender filter is "
            "applied approximately (only within the first page of global search results).\n"
            "- from_days_ago/to_days_ago scope the search to a date range relative to now, e.g. from_days_ago=7 "
            "to_days_ago=1 means \"between a week ago and yesterday\". Omit both to search the entire history.",
        .parameters =
            {
                .properties =
                    {
                        {"query", {.type = "string", .description = "Verbatim text (or part of it) to search for."}},
                        {"chat_id", {.type = "integer", .description = "(optional) Restrict the search to this chat only. Use get_telegram_chats/search_chats to find chat_ids."}},
                        {"sender_id", {.type = "string", .description = "(optional) Restrict results to messages sent by this user. Accepts a numeric user_id or a @username."}},
                        {"from_days_ago", {.type = "integer", .description = "(optional) Only include messages sent at most this many days ago, e.g. 7 = within the last week."}},
                        {"to_days_ago", {.type = "integer", .description = "(optional) Only include messages sent at least this many days ago, e.g. 1 = up until yesterday. Use 0 (or omit) for \"up to now\"."}},
                        {"limit", {.type = "integer", .description = "(optional) Max number of results to return, default {}, capped at {}."_format(DEFAULT_LIMIT, MAX_LIMIT)}},
                    },
                .required = {"query"},
            },
        .handler = [telegram = std::move(telegram), openAI = std::move(openAI), &temporaryContext](OpenAITools::Ctx ctx) -> AFuture<AString> {
            auto query = ctx.args["query"].asStringOpt().valueOrException("query string is required");

            AOptional<int64_t> chatId;
            if (ctx.args.contains("chat_id")) {
                chatId = util::jsonAsLongInt(ctx.args["chat_id"]);
            }

            AOptional<int64_t> senderId;
            if (ctx.args.contains("sender_id")) {
                auto senderQuery = ctx.args["sender_id"].asStringOpt().valueOr("");
                if (!senderQuery.empty()) {
                    if (auto numeric = util::jsonAsLongInt(ctx.args["sender_id"])) {
                        senderId = numeric;
                    } else {
                        try {
                            senderId = co_await resolveChatOrUserId(*telegram, senderQuery);
                        } catch (const AException& e) {
                            ALogger::warn(LOG_TAG) << "Failed to resolve sender_id \"" << senderQuery << "\": " << e;
                            co_return "Error: could not resolve sender_id \"{}\" to a user."_format(senderQuery);
                        }
                    }
                }
            }

            AOptional<long long> fromDaysAgo;
            if (ctx.args.contains("from_days_ago")) {
                fromDaysAgo = util::jsonAsLongInt(ctx.args["from_days_ago"]);
            }
            AOptional<long long> toDaysAgo;
            if (ctx.args.contains("to_days_ago")) {
                toDaysAgo = util::jsonAsLongInt(ctx.args["to_days_ago"]);
            }
            // from_days_ago (further in the past) becomes the lower bound (min_date_);
            // to_days_ago (closer to now) becomes the upper bound (max_date_).
            const int32_t minDate = daysAgoToUnixDate(fromDaysAgo);
            const int32_t maxDate = daysAgoToUnixDate(toDaysAgo);

            int32_t limit = DEFAULT_LIMIT;
            if (ctx.args.contains("limit")) {
                if (auto v = util::jsonAsLongInt(ctx.args["limit"])) {
                    limit = std::clamp(static_cast<int32_t>(*v), 1, MAX_LIMIT);
                }
            }

            AVector<td::td_api::object_ptr<td::td_api::message>> foundMessages;
            int32_t totalCount = 0;

            if (chatId) {
                if (!co_await util::isAccessibleFromLockdown(*telegram, *chatId)) {
                    co_return "Error: this chat is not accessible.";
                }

                td::td_api::object_ptr<td::td_api::MessageSender> senderFilter;
                if (senderId) {
                    senderFilter = ITelegramClient::toPtr(td::td_api::messageSenderUser(*senderId));
                }

                auto response = co_await telegram->sendQueryWithResult(ITelegramClient::toPtr(td::td_api::searchChatMessages(
                    *chatId, nullptr /* topic_id */, query.toStdString(), std::move(senderFilter),
                    0 /* from_message_id */, 0 /* offset */, limit, nullptr /* filter */)));

                totalCount = response->total_count_;
                for (auto& msg : response->messages_) {
                    if (msg->date_ < minDate) continue;
                    if (maxDate != 0 && msg->date_ > maxDate) continue;
                    foundMessages.push_back(std::move(msg));
                }
            } else {
                auto response = co_await telegram->sendQueryWithResult(ITelegramClient::toPtr(td::td_api::searchMessages(
                    ITelegramClient::toPtr(td::td_api::chatListMain()), query.toStdString(), "" /* offset */, limit,
                    nullptr /* filter */, nullptr /* chat_type_filter */, minDate, maxDate)));

                totalCount = response->total_count_;
                for (auto& msg : response->messages_) {
                    if (senderId && extractSenderId(*msg->sender_id_) != *senderId) {
                        continue;
                    }
                    if (!co_await util::isAccessibleFromLockdown(*telegram, msg->chat_id_)) {
                        // don't leak messages from chats that are off-limits under the current lockdown mode.
                        continue;
                    }
                    foundMessages.push_back(std::move(msg));
                }
            }

            if (foundMessages.empty()) {
                co_return "No messages found matching your query.";
            }

            AString result = "<search_results total_count=\"{}\" returned_count=\"{}\">\n"_format(totalCount, foundMessages.size());
            for (auto& msg : foundMessages) {
                auto chat = co_await telegram->getChat(msg->chat_id_);
                result += co_await llmui::formatChatHistoryMessage(*telegram, *msg, *chat, *openAI, temporaryContext, "message chat_id={}"_format(chat->id_));
            }
            result += "</search_results>\n";

            co_return result;
        },
    };
}
