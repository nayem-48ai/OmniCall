package com.example.audio

/**
 * Pure-Kotlin ITU-T G.711 u-law / A-law codec & 16kHz <-> 8kHz linear resampler
 * for wide compatibility across SIP trunks while keeping internal DSP at 16kHz HD wideband.
 */
object AudioCodecUtils {

    private const val BIAS = 0x84
    private const val CLIP = 32635

    fun encodeMuLaw(pcm16: ShortArray): ByteArray {
        val out = ByteArray(pcm16.size)
        for (i in pcm16.indices) {
            out[i] = linearToMuLaw(pcm16[i].toInt())
        }
        return out
    }

    fun decodeMuLaw(encoded: ByteArray, offset: Int = 0, length: Int = encoded.size): ShortArray {
        val out = ShortArray(length)
        for (i in 0 until length) {
            out[i] = muLawToLinear(encoded[offset + i])
        }
        return out
    }

    fun encodeALaw(pcm16: ShortArray): ByteArray {
        val out = ByteArray(pcm16.size)
        for (i in pcm16.indices) {
            out[i] = linearToALaw(pcm16[i].toInt())
        }
        return out
    }

    fun decodeALaw(encoded: ByteArray, offset: Int = 0, length: Int = encoded.size): ShortArray {
        val out = ShortArray(length)
        for (i in 0 until length) {
            out[i] = aLawToLinear(encoded[offset + i])
        }
        return out
    }

    /**
     * Downsamples 16kHz PCM to 8kHz using pairwise averaging for anti-aliasing.
     */
    fun downsample16kTo8k(pcm16k: ShortArray): ShortArray {
        val out = ShortArray(pcm16k.size / 2)
        for (i in out.indices) {
            val s0 = pcm16k[i * 2].toInt()
            val s1 = pcm16k[i * 2 + 1].toInt()
            out[i] = ((s0 + s1) / 2).toShort()
        }
        return out
    }

    /**
     * Upsamples 8kHz PCM to 16kHz using linear interpolation.
     */
    fun upsample8kTo16k(pcm8k: ShortArray): ShortArray {
        if (pcm8k.isEmpty()) return ShortArray(0)
        val out = ShortArray(pcm8k.size * 2)
        for (i in pcm8k.indices) {
            val current = pcm8k[i].toInt()
            val next = if (i + 1 < pcm8k.size) pcm8k[i + 1].toInt() else current
            out[i * 2] = current.toShort()
            out[i * 2 + 1] = ((current + next) / 2).toShort()
        }
        return out
    }

    private fun linearToMuLaw(sampleInput: Int): Byte {
        var sample = sampleInput
        val sign = if (sample < 0) {
            sample = -sample
            0x80
        } else {
            0x00
        }
        if (sample > CLIP) sample = CLIP
        sample += BIAS

        var exponent = 7
        var expMask = 0x4000
        while ((sample and expMask) == 0 && exponent > 0) {
            exponent--
            expMask = expMask shr 1
        }
        val mantissa = (sample shr (exponent + 3)) and 0x0F
        val muLawByte = (sign or (exponent shl 4) or mantissa).inv()
        return (muLawByte and 0xFF).toByte()
    }

    private fun muLawToLinear(muLawByte: Byte): Short {
        val inv = muLawByte.toInt().inv() and 0xFF
        val sign = inv and 0x80
        val exponent = (inv and 0x70) shr 4
        val mantissa = inv and 0x0F
        var sample = ((mantissa shl 3) + BIAS) shl exponent
        sample -= BIAS
        return (if (sign != 0) -sample else sample).toShort()
    }

    private fun linearToALaw(sampleInput: Int): Byte {
        var sample = sampleInput
        val sign = if (sample >= 0) 0x80 else {
            sample = -sample - 1
            0x00
        }
        if (sample > 32767) sample = 32767
        var exponent = 7
        var mask = 0x4000
        while ((sample and mask) == 0 && exponent > 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = if (exponent == 0) (sample shr 4) and 0x0F else (sample shr (exponent + 3)) and 0x0F
        val aLaw = (sign or (exponent shl 4) or mantissa) xor 0x55
        return (aLaw and 0xFF).toByte()
    }

    private fun aLawToLinear(aLawByte: Byte): Short {
        val value = (aLawByte.toInt() and 0xFF) xor 0x55
        val sign = value and 0x80
        val exponent = (value and 0x70) shr 4
        val mantissa = value and 0x0F
        var sample = (mantissa shl 4) + 8
        if (exponent != 0) {
            sample += 0x100
            sample = sample shl (exponent - 1)
        }
        return (if (sign != 0) sample else -sample).toShort()
    }
}
