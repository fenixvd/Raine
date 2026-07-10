#include "is_accessible_from_lockdown.h"

#include "config.h"

#include <range/v3/algorithm/contains.hpp>

AFuture<bool> util::isAccessibleFromLockdown(ITelegramClient& telegram, int64_t chatId) {
#ifdef AUI_TESTS_MODULE
    co_return config().papikChatId == chatId;
#else
    // Broadcast channels are read-only feeds (news): the character can't be texted by them and can't
    // reply into them, so allow them through regardless of lockdown when enabled. This lets her stay
    // locked to a single person while still reading subscribed news channels.
    if (config().lockdownAllowChannels) {
        try {
            auto chat = co_await telegram.sendQueryWithResult(ITelegramClient::toPtr(td::td_api::getChat(chatId)));
            if (chat && chat->type_ && chat->type_->get_id() == td::td_api::chatTypeSupergroup::ID
                && static_cast<const td::td_api::chatTypeSupergroup&>(*chat->type_).is_channel_) {
                co_return true;
            }
        } catch (const AException&) {
            // Couldn't resolve the chat - fall through to the normal lockdown rules below.
        }
    }

    switch (config().lockdown) {
        case Config::LockdownMode::NONE: {
            co_return true;
        }

        case Config::LockdownMode::CONTACTS_ONLY: {
            static auto contacts = co_await telegram.sendQueryWithResult(ITelegramClient::toPtr(td::td_api::getContacts()));
            if (ranges::contains(contacts->user_ids_, chatId)) {
                co_return true;
            }
            [[fallthrough]];
        }

        case Config::LockdownMode::PAPIK_ONLY: {
            if (chatId == config().papikChatId) {
                co_return true;
            }
            [[fallthrough]];
        }

        default: {
            co_return false;
        }
    }
#endif
}