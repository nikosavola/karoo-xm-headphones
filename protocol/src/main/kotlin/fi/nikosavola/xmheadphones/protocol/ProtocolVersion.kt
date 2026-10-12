package fi.nikosavola.xmheadphones.protocol

/**
 * Which MDR command table a headset speaks.
 *
 * Both generations open with the same four CONNECT commands; the reply to
 * [SonyHandshakeRequest.ProtocolInfo] is what tells them apart, because V1 answers with a two byte
 * version and V2 with a four byte one followed by two table flags. Most command bytes are shared,
 * but the ones that are not (battery, for instance) are looked up per version.
 */
public enum class ProtocolVersion {
    V1,
    V2,
}
