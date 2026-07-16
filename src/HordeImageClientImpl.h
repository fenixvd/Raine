#pragma once
#include "IStableDiffusionClient.h"

/**
 * @brief Image generation via the AI Horde (aihorde.net) — free, community-powered, uncensored.
 * @details
 * Unlike the OpenAI/a1111 backends this API is asynchronous: submit a job, poll until it's done,
 * then download the produced image. NSFW is requested explicitly (nsfw=true, censor_nsfw=false), so
 * this backend can draw adult content the moderated cloud providers (FLUX/RouterAI) reject.
 */
class HordeImageClientImpl : public IStableDiffusionClient {
public:
    AFuture<Txt2ImgResponse> txt2img(const Txt2ImgRequest& request) override;
    AFuture<> unloadCheckpoint() override;
};
