#include "HordeImageClientImpl.h"

#include <algorithm>
#include <chrono>
#include <cmath>

#include "AUI/Curl/ACurl.h"
#include "AUI/Json/AJson.h"
#include "AUI/Logging/ALogger.h"
#include "AUI/Thread/AThread.h"

#include "config.h"

using namespace std::chrono_literals;
static constexpr auto LOG_TAG = "HordeImageClient";
static constexpr auto CLIENT_AGENT = "raine-kuni:1.0:github.com/fenixvd/Raine";

// AI Horde requires image dimensions to be multiples of 64. SDXL-class models (which the anime/NSFW
// checkpoints are) also behave badly far outside ~1024px; keep within the free (no-kudos-upfront)
// 1024x1024 ceiling — above that the horde demands kudos upfront (KudosUpfront) under load.
static int64_t sanitizeDim(int64_t v) {
    v = (v / 64) * 64;
    return std::clamp<int64_t>(v, 512, 1024);
}

// Small helper: GET a URL with a few retries and a short low-speed abort, so a single stalled transfer
// (curl's "Operation too slow" after 180s by default) doesn't throw away an already-finished,
// kudos-costing horde generation. Returns the raw body; throws only after all attempts fail.
static AFuture<AByteBuffer> hordeGet(AString url, int attempts, std::chrono::seconds timeout) {
    for (int attempt = 1; attempt <= attempts; ++attempt) {
        bool failed = false;
        try {
            co_return (co_await ACurl::Builder(url)
                           .withMethod(ACurl::Method::HTTP_GET)
                           .withHeaders({ "Client-Agent: {}"_format(CLIENT_AGENT) })
                           .withTimeout(timeout)
                           .withLowSpeedTime(15s)  // abort a stalled transfer fast, then retry
                           .runAsync())
                .body;
        } catch (const AException& e) {
            // Note: co_await is ill-formed inside a catch block, so we only flag the failure here
            // and do the back-off sleep below, outside the handler.
            ALogger::warn(LOG_TAG) << "GET " << url << " attempt " << attempt << "/" << attempts
                                   << " failed: " << e;
            if (attempt >= attempts) {
                throw;
            }
            failed = true;
        }
        if (failed) {
            co_await AThread::asyncSleep(2s);
        }
    }
    throw AException("horde GET exhausted");  // unreachable
}

AFuture<IStableDiffusionClient::Txt2ImgResponse> HordeImageClientImpl::txt2img(const Txt2ImgRequest& request) {
    ALOG_TRACE(LOG_TAG) << "txt2img";
    const auto base = config().hordeBaseUrl;  // e.g. https://aihorde.net/api/v2/

    // AI Horde encodes the negative prompt inline, after a "###" separator.
    AString prompt = request.prompt;
    if (!request.negative_prompt.empty()) {
        prompt += " ### ";
        prompt += request.negative_prompt;
    }

    AJson::Array models;
    for (const auto& m : config().hordeModels.split(',')) {
        auto name = m.trim();
        if (!name.empty()) {
            models << name;
        }
    }

    AJson params = AJson::Object {
        { "sampler_name", "k_euler_a" },
        // AI Horde rejects cfg_scale with more than 2 decimal places (ImageGenerator randomizes it).
        { "cfg_scale", std::round(std::clamp(request.cfg_scale <= 0 ? 6.0 : request.cfg_scale, 4.0, 8.0) * 100.0) / 100.0 },
        { "width", (int) sanitizeDim(request.width) },
        { "height", (int) sanitizeDim(request.height) },
        { "steps", (int) std::clamp<int64_t>(request.steps, 10, 30) },
        { "n", 1 },
        { "karras", true },
        { "clip_skip", 2 },
    };
    AJson body = AJson::Object {
        { "prompt", prompt },
        { "params", std::move(params) },
        { "nsfw", true },
        { "censor_nsfw", false },
        { "shared", true },  // sharing generations back to the horde grants kudos → better priority
        { "trusted_workers", false },
        { "r2", true },
        { "models", std::move(models) },
    };

    // 1. Submit the job.
    AVector<AString> submitHeaders = {
        "Content-Type: application/json",
        "apikey: {}"_format(config().hordeApiKey),
        "Client-Agent: {}"_format(CLIENT_AGENT),
    };
    auto submitBody = (co_await ACurl::Builder(base + "generate/async")
                           .withMethod(ACurl::Method::HTTP_POST)
                           .withHeaders(std::move(submitHeaders))
                           .withBody(AJson::toString(body).toStdString())
                           .withTimeout(60s)
                           .withLowSpeedTime(30s)
                           .runAsync())
                          .body;
    auto submit = AJson::fromBuffer(submitBody);
    if (!submit.contains("id")) {
        throw AException("AI Horde submit failed: {}"_format(AJson::toString(submit)));
    }
    const AString id = submit["id"].asString();
    ALogger::info(LOG_TAG) << "Submitted horde job " << id;

    // 2. Poll until finished. A single failed poll (slow/stalled connection) is retried, not fatal.
    constexpr int kMaxPolls = 150;  // 150 * 4s ≈ 10 min ceiling
    bool done = false;
    for (int i = 0; i < kMaxPolls; ++i) {
        AJson check;
        bool polled = false;
        try {
            check = AJson::fromBuffer(co_await hordeGet(base + "generate/check/" + id, 1, 30s));
            polled = true;
        } catch (const AException& e) {
            ALogger::warn(LOG_TAG) << "poll check failed, will retry: " << e;
        }
        if (polled) {
            if (check["faulted"].asBoolOpt().valueOr(false)) {
                throw AException("AI Horde job faulted: {}"_format(AJson::toString(check)));
            }
            if (check["done"].asBoolOpt().valueOr(false)) {
                done = true;
                break;
            }
        }
        co_await AThread::asyncSleep(4s);
    }
    if (!done) {
        throw AException("AI Horde job timed out while still queued");
    }

    // 3. Fetch the finished job's image reference (retried).
    auto status = AJson::fromBuffer(co_await hordeGet(base + "generate/status/" + id, 3, 30s));
    auto generations = status["generations"].asArrayOpt().valueOr(AJson::Array{});
    if (generations.empty()) {
        throw AException("AI Horde returned no generations: {}"_format(AJson::toString(status)));
    }
    const AString imgUrl = generations.first()["img"].asString();

    // 4. Download the produced image (r2=true → an R2 URL). Retried so one stalled transfer doesn't
    //    throw away an already-finished, kudos-costing generation.
    auto imageBytes = co_await hordeGet(imgUrl, 3, 60s);

    Txt2ImgResponse res;
    res.images << AImage::fromBuffer(imageBytes);
    res.info = status;
    co_return res;
}

AFuture<> HordeImageClientImpl::unloadCheckpoint() {
    // The horde has no local checkpoint to unload.
    co_return;
}
