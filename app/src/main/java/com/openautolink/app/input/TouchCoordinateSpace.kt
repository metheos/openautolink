package com.openautolink.app.input

/** Maps live video geometry into the fixed touchscreen space advertised in SDR. */
object TouchCoordinateSpace {
    fun innerForProtocol(
        protocolWidth: Int,
        protocolHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        videoInnerWidth: Int,
        videoInnerHeight: Int,
    ): Pair<Int, Int> {
        if (protocolWidth <= 0 || protocolHeight <= 0) return 0 to 0
        if (videoWidth <= 0 || videoHeight <= 0 ||
            videoInnerWidth <= 0 || videoInnerHeight <= 0
        ) {
            return protocolWidth to protocolHeight
        }

        val innerWidth = ((protocolWidth.toLong() * videoInnerWidth) / videoWidth)
            .coerceIn(1L, protocolWidth.toLong())
            .toInt()
        val innerHeight = ((protocolHeight.toLong() * videoInnerHeight) / videoHeight)
            .coerceIn(1L, protocolHeight.toLong())
            .toInt()
        return innerWidth to innerHeight
    }
}
