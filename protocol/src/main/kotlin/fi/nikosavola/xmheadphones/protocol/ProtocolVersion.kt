package fi.nikosavola.xmheadphones.protocol

/**
 * Which MDR command table a headset speaks.
 *
 * Both generations open with the same four CONNECT commands. The reply to
 * [SonyHandshakeRequest.ProtocolInfo] tells them apart. V1 answers with a two byte version, V2 with
 * a four byte one followed by two table flags. Most command bytes are shared. The rest, battery for
 * instance, are looked up per version.
 */
public enum class ProtocolVersion {
    V1,
    V2,
}
