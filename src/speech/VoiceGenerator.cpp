#include "VoiceGenerator.h"
#include "config.h"
#include "ElevenLabsClient.h"
#include "OpenAISpeechClient.h"
#include "AUI/Logging/ALogger.h"
#include "AUI/IO/AFileOutputStream.h"
#include <chrono>
#include <cstdlib>

static constexpr auto LOG_TAG = "VoiceGenerator";

/**
 * @brief Transcodes raw signed-16-bit-LE mono PCM into an OGG/Opus voice note using the ffmpeg binary.
 * @details Some OpenAI-compatible TTS providers (notably Gemini via RouterAI) only return raw PCM, while
 *          Telegram voice notes (inputMessageVoiceNote) require OGG/Opus. ffmpeg is invoked via the shell;
 *          both paths are program-controlled (numeric-timestamp files), so there is no injection surface.
 * @return true on success (out written), false otherwise.
 */
static bool transcodePcmToOpus(const APath& in, const APath& out, int sampleRate) {
    auto cmd = "ffmpeg -y -loglevel error -f s16le -ar {} -ac 1 -i \"{}\" -c:a libopus -b:a 32k \"{}\""_format(
        sampleRate, in.absolute(), out.absolute());
    ALOG_TRACE(LOG_TAG) << "ffmpeg: " << cmd;
    int code = std::system(cmd.toStdString().c_str());
    if (code != 0) {
        ALogger::err(LOG_TAG) << "ffmpeg PCM->Opus failed (exit " << code << "): " << cmd;
        return false;
    }
    return true;
}

AFuture<VoiceGenerator::VoiceMessage> VoiceGenerator::generate(AString text, AString languageCode, double speed) {
    ALogger::info(LOG_TAG) << "Generating voice message for text: " << text;

    try {
        APath voiceDir("data/voice_messages");
        voiceDir.makeDirs();

        AByteBuffer audioData;

        switch (config().recordVoiceBackend) {
            case Config::TTSBackend::ELEVENLABS: {
                ElevenLabsClient ttsClient{
                    .baseUrl = "https://api.elevenlabs.io/",
                    .apiKey = config().recordVoiceElevenLabsKey,
                    .voiceId = config().recordVoiceElevenLabsVoice,
                };
                ElevenLabsClient::TextToSpeechRequest request{
                    .text = text,
                    .model_id = "eleven_v3",
                    .language_code = languageCode,
                    .voice_settings = {.speed = speed},
                };
                auto ttsResponse = co_await ttsClient.textToSpeech(request);
                if (ttsResponse.audioData.empty()) {
                    throw AException("ElevenLabs returned empty audio data");
                }
                audioData = std::move(ttsResponse.audioData);
                break;
            }
            case Config::TTSBackend::OPENAI: {
                OpenAISpeechClient ttsClient{
                    .baseUrl = config().recordVoiceOpenAIUrl,
                    .apiKey = config().recordVoiceOpenAIKey,
                    .model = config().recordVoiceOpenAIModel,
                    .voice = config().recordVoiceOpenAIVoice,
                };
                OpenAISpeechClient::TextToSpeechRequest request{
                    .input = text,
                    .model = config().recordVoiceOpenAIModel,
                    .voice = config().recordVoiceOpenAIVoice,
                    .response_format = config().recordVoiceOpenAIFormat,
                    .speed = speed,
                };
                auto ttsResponse = co_await ttsClient.textToSpeech(request);
                if (ttsResponse.audioData.empty()) {
                    throw AException("OpenAI Speech returned empty audio data");
                }
                audioData = std::move(ttsResponse.audioData);
                break;
            }
        }

        auto timestamp = std::chrono::system_clock::now().time_since_epoch().count();

        // Telegram voice notes require OGG/Opus. Providers that return raw PCM (e.g. Gemini via RouterAI)
        // are transcoded here; anything else is saved as-is.
        const bool needsPcmTranscode = config().recordVoiceBackend == Config::TTSBackend::OPENAI
            && config().recordVoiceOpenAIFormat == "pcm";

        auto writeBytes = [&](const APath& path) {
            AFileOutputStream stream(path);
            stream.write(reinterpret_cast<const char*>(audioData.data()), audioData.getSize());
            stream.close();
        };

        APath outputPath;
        if (needsPcmTranscode) {
            APath rawPath = voiceDir / "{}.pcm"_format(timestamp);
            outputPath = voiceDir / "{}.ogg"_format(timestamp);
            writeBytes(rawPath);
            if (!transcodePcmToOpus(rawPath, outputPath, config().recordVoiceOpenAIPcmSampleRate)) {
                throw AException("Failed to transcode PCM voice message to OGG/Opus (is ffmpeg installed?)");
            }
            try { rawPath.removeFile(); } catch (const AException&) {}
        } else {
            outputPath = voiceDir / "{}.mp3"_format(timestamp);
            writeBytes(outputPath);
        }

        ALogger::info(LOG_TAG) << "Voice message saved to: " << outputPath.absolute();

        co_return VoiceMessage{ .path = outputPath.absolute() };
    } catch (const AException& e) {
        ALogger::err(LOG_TAG) << "Failed to generate voice message: " << e;
        throw;
    }
}
