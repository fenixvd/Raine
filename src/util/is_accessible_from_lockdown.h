#pragma once

#include "config.h"

#include <telegram/ITelegramClient.h>

namespace util {
AFuture<bool> isAccessibleFromLockdown(ITelegramClient& telegram, int64_t chatId, Config::LockdownMode lockdown = config().lockdown);
}