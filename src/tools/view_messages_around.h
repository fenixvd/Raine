#pragma once
#include "OpenAITools.h"
#include "IOpenAIChat.h"
#include "telegram/ITelegramClient.h"

namespace tools {
OpenAITools::Tool viewMessagesAround(_<ITelegramClient> telegram, _<IOpenAIChat> openAI, const IOpenAIChat::Session& temporaryContext);
}
