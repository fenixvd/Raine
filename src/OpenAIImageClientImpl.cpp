#include "OpenAIImageClientImpl.h"

#include <algorithm>

#include "AUI/Curl/ACurl.h"
#include "AUI/Json/AJson.h"
#include "AUI/Logging/ALogger.h"

#include "config.h"

static constexpr auto LOG_TAG = "OpenAIImageClient";

/**
 * @brief Snaps arbitrary width/height to the closest size string accepted by OpenAI-compatible
 *        image endpoints (square / landscape / portrait).
 */
static AString pickSize(std::int64_t width, std::int64_t height) {
    const double ratio = double(width) / double(std::max<std::int64_t>(height, 1));
    if (ratio > 1.2) {
        return "1792x1024";
    }
    if (ratio < 0.83) {
        return "1024x1792";
    }
    return "1024x1024";
}

AFuture<IStableDiffusionClient::Txt2ImgResponse> OpenAIImageClientImpl::txt2img(const Txt2ImgRequest& request) {
    ALOG_TRACE(LOG_TAG) << "txt2img";

    // OpenAI-style image endpoints have no negative prompt; fold it into the text prompt.
    AString prompt = request.prompt;
    if (!request.negative_prompt.empty()) {
        prompt += "\n\nAvoid the following: {}."_format(request.negative_prompt);
    }

    AJson body = AJson::Object {
        { "model", endpoint.model },
        { "prompt", prompt },
        { "n", 1 },
        { "size", pickSize(request.width, request.height) },
        { "response_format", "b64_json" },
    };
    auto query = AJson::toString(body);
    ALOG_TRACE(LOG_TAG) << "Query: " << query;

    AVector<AString> headers = { "Content-Type: application/json" };
    if (!endpoint.endpoint.bearerKey.empty()) {
        headers << "Authorization: Bearer {}"_format(endpoint.endpoint.bearerKey);
    }

    auto responseBody = (co_await ACurl::Builder(endpoint.endpoint.baseUrl + "images/generations")
                             .withMethod(ACurl::Method::HTTP_POST)
                             .withHeaders(std::move(headers))
                             .withBody(query.toStdString())
                             .withTimeout(config().requestTimeoutSecs)
                             .runAsync())
                            .body;
    auto response = AJson::fromBuffer(responseBody);
    ALOG_TRACE(LOG_TAG) << "Response: " << AJson::toString(response);

    if (!response.contains("data") || response["data"].asArray().empty()) {
        throw AException("Image endpoint returned no data: {}"_format(AJson::toString(response)));
    }

    Txt2ImgResponse res;
    for (const auto& item : response["data"].asArray()) {
        if (item.contains("b64_json")) {
            res.images << AImage::fromBuffer(AByteBuffer::fromBase64String(item["b64_json"].asString()));
        } else if (item.contains("url")) {
            // Some providers only return a URL; download it.
            auto imageBytes = (co_await ACurl::Builder(item["url"].asString())
                                   .withMethod(ACurl::Method::HTTP_GET)
                                   .withTimeout(config().requestTimeoutSecs)
                                   .runAsync())
                                  .body;
            res.images << AImage::fromBuffer(imageBytes);
        }
    }
    if (res.images.empty()) {
        throw AException("Image endpoint returned neither b64_json nor url");
    }
    res.info = response;
    co_return res;
}

AFuture<> OpenAIImageClientImpl::unloadCheckpoint() {
    // Cloud image backends keep no local checkpoint in VRAM - nothing to unload.
    co_return;
}
