package com.zxhhyj.prism.motion.format

/**
 * 最小 MP4 封装：单个视频轨、AVC（H.264）、恒定帧率、每个样本一个 NAL 组。
 * 只写 VAP 需要的字段，不做编辑列表、B 帧重排或音频。
 */
public object Mp4Writer {

    public fun write(width: Int, height: Int, fps: Int, stream: AvcStream): ByteArray {
        val samples = stream.samples
        require(samples.isNotEmpty()) { "没有样本" }
        val durationMs = samples.size * 1000L / fps

        val ftyp = box("ftyp") {
            bytes("isom".encodeToByteArray())
            u32(0x200)
            bytes("isom".encodeToByteArray())
            bytes("iso2".encodeToByteArray())
            bytes("avc1".encodeToByteArray())
            bytes("mp41".encodeToByteArray())
        }

        // moov 里的 chunk offset 依赖 moov 自身长度，先用 0 生成一次量长度，再回填。
        val probe = moov(width, height, fps, durationMs, stream, chunkOffset = 0)
        val chunkOffset = ftyp.size + probe.size + 8
        val moov = moov(width, height, fps, durationMs, stream, chunkOffset)
        check(moov.size == probe.size) { "moov 长度不稳定" }

        val mdatPayload = ByteArray(samples.sumOf { it.size })
        var offset = 0
        for (sample in samples) {
            sample.copyInto(mdatPayload, offset)
            offset += sample.size
        }

        val out = ByteArray(ftyp.size + moov.size + 8 + mdatPayload.size)
        var cursor = 0
        ftyp.copyInto(out, cursor)
        cursor += ftyp.size
        moov.copyInto(out, cursor)
        cursor += moov.size
        writeU32(out, cursor, 8 + mdatPayload.size)
        out[cursor + 4] = 'm'.code.toByte()
        out[cursor + 5] = 'd'.code.toByte()
        out[cursor + 6] = 'a'.code.toByte()
        out[cursor + 7] = 't'.code.toByte()
        cursor += 8
        mdatPayload.copyInto(out, cursor)
        return out
    }

    private fun moov(
        width: Int,
        height: Int,
        fps: Int,
        durationMs: Long,
        stream: AvcStream,
        chunkOffset: Int,
    ): ByteArray = box("moov") {
        bytes(mvhd(durationMs))
        bytes(trak(width, height, fps, durationMs, stream, chunkOffset))
    }

    private fun mvhd(durationMs: Long): ByteArray = box("mvhd") {
        u32(0) // version + flags
        u32(0) // creation
        u32(0) // modification
        u32(1000) // timescale
        u32(durationMs.toInt())
        u32(0x00010000) // rate
        u16(0x0100) // volume
        u16(0)
        u32(0)
        u32(0)
        matrix()
        repeat(6) { u32(0) } // pre_defined
        u32(2) // next_track_id
    }

    private fun trak(
        width: Int,
        height: Int,
        fps: Int,
        durationMs: Long,
        stream: AvcStream,
        chunkOffset: Int,
    ): ByteArray = box("trak") {
        bytes(
            box("tkhd") {
                u32(7) // version 0, flags enabled|in movie|in preview
                u32(0)
                u32(0)
                u32(1) // track id
                u32(0)
                u32(durationMs.toInt())
                u32(0)
                u32(0)
                u16(0) // layer
                u16(0) // alternate group
                u16(0) // volume
                u16(0)
                matrix()
                u32(width shl 16)
                u32(height shl 16)
            },
        )
        bytes(
            box("mdia") {
                bytes(
                    box("mdhd") {
                        u32(0)
                        u32(0)
                        u32(0)
                        u32(fps) // timescale
                        u32(stream.samples.size) // duration in frames
                        u16(0x55C4) // language "und"
                        u16(0)
                    },
                )
                bytes(
                    box("hdlr") {
                        u32(0)
                        u32(0)
                        bytes("vide".encodeToByteArray())
                        repeat(3) { u32(0) }
                        bytes("VideoHandler\u0000".encodeToByteArray())
                    },
                )
                bytes(
                    box("minf") {
                        bytes(
                            box("vmhd") {
                                u32(1) // version 0, flags 1
                                u16(0)
                                u16(0)
                                u16(0)
                                u16(0)
                            },
                        )
                        bytes(
                            box("dinf") {
                                bytes(
                                    box("dref") {
                                        u32(0)
                                        u32(1)
                                        bytes(box("url ") { u32(1) })
                                    },
                                )
                            },
                        )
                        bytes(stbl(width, height, stream, chunkOffset))
                    },
                )
            },
        )
    }

    private fun stbl(width: Int, height: Int, stream: AvcStream, chunkOffset: Int): ByteArray =
        box("stbl") {
            bytes(
                box("stsd") {
                    u32(0)
                    u32(1)
                    bytes(avc1(width, height, stream))
                },
            )
            bytes(
                box("stts") {
                    u32(0)
                    u32(1)
                    u32(stream.samples.size)
                    u32(1)
                },
            )
            bytes(
                box("stsc") {
                    u32(0)
                    u32(1)
                    u32(1) // first_chunk
                    u32(stream.samples.size) // samples_per_chunk
                    u32(1) // sample_description_index
                },
            )
            bytes(
                box("stsz") {
                    u32(0)
                    u32(0)
                    u32(stream.samples.size)
                    for (sample in stream.samples) u32(sample.size)
                },
            )
            bytes(
                box("stco") {
                    u32(0)
                    u32(1)
                    u32(chunkOffset)
                },
            )
            if (stream.keyFrameIndices.isNotEmpty()) {
                bytes(
                    box("stss") {
                        u32(0)
                        u32(stream.keyFrameIndices.size)
                        for (index in stream.keyFrameIndices) u32(index + 1)
                    },
                )
            }
        }

    private fun avc1(width: Int, height: Int, stream: AvcStream): ByteArray = box("avc1") {
        repeat(6) { u8(0) }
        u16(1) // data_reference_index
        u16(0)
        u16(0)
        repeat(3) { u32(0) }
        u16(width)
        u16(height)
        u32(0x00480000)
        u32(0x00480000)
        u32(0)
        u16(1) // frame_count
        repeat(32) { u8(0) } // compressorname
        u16(0x0018) // depth
        u16(0xFFFF) // pre_defined
        bytes(
            box("avcC") {
                val sps = stream.sps
                val pps = stream.pps
                u8(1)
                u8(sps[1].toInt() and 0xFF)
                u8(sps[2].toInt() and 0xFF)
                u8(sps[3].toInt() and 0xFF)
                u8(0xFF) // lengthSizeMinusOne = 3
                u8(0xE1) // numOfSequenceParameterSets = 1
                u16(sps.size)
                bytes(sps)
                u8(1) // numOfPictureParameterSets
                u16(pps.size)
                bytes(pps)
            },
        )
    }

    private fun BoxScope.matrix() {
        u32(0x00010000)
        u32(0)
        u32(0)
        u32(0)
        u32(0x00010000)
        u32(0)
        u32(0)
        u32(0)
        u32(0x40000000)
    }

    // ---- 简易写入器 ------------------------------------------------------

    private class Writer {
        private val out = ArrayList<Byte>()
        val size: Int get() = out.size

        fun u8(value: Int) {
            out.add((value and 0xFF).toByte())
        }

        fun u16(value: Int) {
            u8(value ushr 8)
            u8(value)
        }

        fun u32(value: Int) {
            u8(value ushr 24)
            u8(value ushr 16)
            u8(value ushr 8)
            u8(value)
        }

        fun bytes(value: ByteArray) {
            for (byte in value) out.add(byte)
        }

        fun toByteArray(): ByteArray = ByteArray(out.size) { out[it] }
    }

    private class BoxScope(private val writer: Writer) {
        fun u8(value: Int) = writer.u8(value)
        fun u16(value: Int) = writer.u16(value)
        fun u32(value: Int) = writer.u32(value)
        fun bytes(value: ByteArray) = writer.bytes(value)
    }

    private fun box(type: String, body: BoxScope.() -> Unit): ByteArray {
        val content = Writer()
        BoxScope(content).body()
        val payload = content.toByteArray()
        val result = ByteArray(8 + payload.size)
        writeU32(result, 0, result.size)
        for (i in 0 until 4) result[4 + i] = type[i].code.toByte()
        payload.copyInto(result, 8)
        return result
    }

    private fun writeU32(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 24).toByte()
        target[offset + 1] = (value ushr 16).toByte()
        target[offset + 2] = (value ushr 8).toByte()
        target[offset + 3] = value.toByte()
    }
}
