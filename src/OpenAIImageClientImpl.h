#pragma once
#include "IStableDiffusionClient.h"

/**
 * @brief IStableDiffusionClient implementation talking to an OpenAI-compatible image
 *        generation endpoint (v1/images/generations), e.g. FLUX via RouterAI.
 *
 * Unlike StableDiffusionClientImpl (Automatic1111 WebUI), this backend has no notion of
 * samplers, schedulers, checkpoints, LoRA or negative prompts. A1111-specific request
 * fields are therefore ignored; the negative prompt (if any) is folded into the text
 * prompt, and width/height are snapped to the closest size the endpoint accepts.
 */
struct OpenAIImageClientImpl : IStableDiffusionClient {
    EndpointAndModel endpoint = config().imageOpenAI;

    AFuture<Txt2ImgResponse> txt2img(const Txt2ImgRequest& request) override;
    AFuture<> unloadCheckpoint() override;
};
