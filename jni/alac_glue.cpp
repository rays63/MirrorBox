// Thin C wrapper around Apple's ALAC decoder (Apache-2.0), for AirPlay audio (44.1 kHz, 16-bit, stereo, 352 frames/packet).
#include <stdint.h>
#include <string.h>
#include "ALACDecoder.h"
#include "ALACBitUtilities.h"

extern "C" {

void *alac_glue_create(void) {
    // 'alac' magic cookie as sent in AirPlay ANNOUNCE/SETUP: ALACSpecificConfig, big-endian.
    static const uint8_t cookie[24] = {
        0x00, 0x00, 0x01, 0x60,   // frameLength 352
        0x00,                     // compatibleVersion
        0x10,                     // bitDepth 16
        0x28, 0x0a, 0x0e,         // pb, mb, kb
        0x02,                     // numChannels
        0x00, 0xff,               // maxRun
        0x00, 0x00, 0x00, 0x00,   // maxFrameBytes
        0x00, 0x00, 0x00, 0x00,   // avgBitRate
        0x00, 0x00, 0xac, 0x44    // sampleRate 44100
    };
    ALACDecoder *d = new ALACDecoder();
    if (d->Init((void *) cookie, sizeof(cookie)) != 0) {
        delete d;
        return NULL;
    }
    return d;
}

/* Decodes one packet into out (interleaved s16, room for 4096 frames). Returns frames decoded, or -1. */
int alac_glue_decode(void *dec, const uint8_t *in, int in_len, int16_t *out, int max_frames) {
    ALACDecoder *d = (ALACDecoder *) dec;
    if (!d) return -1;
    BitBuffer bits;
    BitBufferInit(&bits, (uint8_t *) in, (uint32_t) in_len);
    uint32_t frames = 0;
    if (d->Decode(&bits, (uint8_t *) out, (uint32_t) max_frames, 2, &frames) != 0) return -1;
    return (int) frames;
}

void alac_glue_destroy(void *dec) {
    delete (ALACDecoder *) dec;
}

}
