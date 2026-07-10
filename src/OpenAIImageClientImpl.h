#pragma once
#include "IStableDiffusionClient.h"

/**
 * @brief IStableDiffusionClient implementation talking to an OpenAI-compatible image
 *        generation endpoint (v1/images/generations), e.g. FLUX via RouterAI.
 *
 * Unlike StableDiffusionClientImpl (Automatic1111 WebUI), this backend has no notion of
 * samplers, schedulers, checkpoints, LoRA, size or negative prompts. A1111-specific request
 * fields are therefore ignored; the negative prompt (if any) is folded into the text prompt.
 * Talks to POST {baseUrl}images with {model, prompt, n} and reads data[].b64_json.
 */
struct OpenAIImageClientImpl : IStableDiffusionClient {
    EndpointAndModel endpoint = config().imageOpenAI;

    AFuture<Txt2ImgResponse> txt2img(const Txt2ImgRequest& request) override;
    AFuture<> unloadCheckpoint() override;
};
