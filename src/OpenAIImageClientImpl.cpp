#include "OpenAIImageClientImpl.h"

#include "AUI/Curl/ACurl.h"
#include "AUI/Json/AJson.h"
#include "AUI/Logging/ALogger.h"

#include "config.h"

static constexpr auto LOG_TAG = "OpenAIImageClient";

AFuture<IStableDiffusionClient::Txt2ImgResponse> OpenAIImageClientImpl::txt2img(const Txt2ImgRequest& request) {
    ALOG_TRACE(LOG_TAG) << "txt2img";

    // OpenRouter-style image endpoints (e.g. RouterAI) have no negative prompt; fold it into the
    // text prompt. Sampler/steps/cfg/size/LoRA fields are not part of this API and are ignored.
    AString prompt = request.prompt;
    if (!request.negative_prompt.empty()) {
        prompt += "\n\nAvoid the following: {}."_format(request.negative_prompt);
    }

    AJson body = AJson::Object {
        { "model", endpoint.model },
        { "prompt", prompt },
        { "n", 1 },
        { "output_format", "png" },
    };
    auto query = AJson::toString(body);
    ALOG_TRACE(LOG_TAG) << "Query: " << query;

    AVector<AString> headers = { "Content-Type: application/json" };
    if (!endpoint.endpoint.bearerKey.empty()) {
        headers << "Authorization: Bearer {}"_format(endpoint.endpoint.bearerKey);
    }

    auto responseBody = (co_await ACurl::Builder(endpoint.endpoint.baseUrl + "images")
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
