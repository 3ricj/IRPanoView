namespace IRPanoView.Viewer.Network;

public sealed class ThermalFrameParser
{
    public const uint Magic = 0x56505249; // "IRPV"
    public const int HeaderBytes = 22;
    public const byte FlagPerCameraHealth = 0x01;
    public const byte FlagHasMetaV2 = 0x02;
    public const byte FlagCameraInfo = 0x04;
    public const byte FlagPayloadDeflate = 0x08;
    public const byte Version1 = 1;
    public const byte Version2 = 2;
    public const int MetaV2Bytes = 72;
    public const int CameraInfoBytes = 20;
    public const int CameraSerialBytes = 16;

    public const int DefaultPanoWidth = 1024;
    public const int DefaultPanoHeight = 192;

    [Obsolete("Use frame Width/Height from IRPV header or DefaultPanoWidth")]
    public const int PanoWidth = DefaultPanoWidth;

    [Obsolete("Use frame Height from IRPV header or DefaultPanoHeight")]
    public const int PanoHeight = DefaultPanoHeight;

    public sealed class MetaV2
    {
        public required long ComposeUs { get; init; }
        public required long EmitUs { get; init; }
        public required uint[] SlotFrameSeq { get; init; }
        public required long[] SlotUsbFrameUs { get; init; }
        public required uint EmitSeq { get; init; }
        public required uint Flags { get; init; }
    }

    public sealed class ParsedFrame
    {
        public required int Sequence { get; init; }
        public required long TimestampUs { get; init; }
        public required int Width { get; init; }
        public required int Height { get; init; }
        public required ushort[] RawPixels { get; init; }
        public byte[]? CameraHealth { get; init; }
        public MetaV2? Meta { get; init; }
        public required byte Version { get; init; }
        public int? CameraSlot { get; init; }
        public int? CameraCount { get; init; }
        public string? CameraSerial { get; init; }
    }

    public static ParsedFrame? Parse(ReadOnlySpan<byte> packet)
    {
        if (packet.Length < HeaderBytes)
        {
            return null;
        }

        var magic = BitConverter.ToUInt32(packet);
        if (magic != Magic)
        {
            return null;
        }

        var version = packet[4];
        if (version is not (Version1 or Version2))
        {
            return null;
        }

        var flags = packet[5];
        var width = BitConverter.ToUInt16(packet.Slice(6, 2));
        var height = BitConverter.ToUInt16(packet.Slice(8, 2));
        var sequence = BitConverter.ToInt32(packet.Slice(10, 4));
        var timestampUs = BitConverter.ToInt64(packet.Slice(14, 8));

        var offset = HeaderBytes;
        MetaV2? meta = null;
        if ((flags & FlagHasMetaV2) != 0)
        {
            if (packet.Length < offset + MetaV2Bytes)
            {
                return null;
            }

            meta = new MetaV2
            {
                ComposeUs = BitConverter.ToInt64(packet.Slice(offset, 8)),
                EmitUs = BitConverter.ToInt64(packet.Slice(offset + 8, 8)),
                SlotFrameSeq =
                [
                    BitConverter.ToUInt32(packet.Slice(offset + 16, 4)),
                    BitConverter.ToUInt32(packet.Slice(offset + 20, 4)),
                    BitConverter.ToUInt32(packet.Slice(offset + 24, 4)),
                    BitConverter.ToUInt32(packet.Slice(offset + 28, 4)),
                ],
                SlotUsbFrameUs =
                [
                    BitConverter.ToInt64(packet.Slice(offset + 32, 8)),
                    BitConverter.ToInt64(packet.Slice(offset + 40, 8)),
                    BitConverter.ToInt64(packet.Slice(offset + 48, 8)),
                    BitConverter.ToInt64(packet.Slice(offset + 56, 8)),
                ],
                EmitSeq = BitConverter.ToUInt32(packet.Slice(offset + 64, 4)),
                Flags = BitConverter.ToUInt32(packet.Slice(offset + 68, 4)),
            };
            offset += MetaV2Bytes;
        }

        int? cameraSlot = null;
        int? cameraCount = null;
        string? cameraSerial = null;
        if ((flags & FlagCameraInfo) != 0)
        {
            if (packet.Length < offset + CameraInfoBytes)
            {
                return null;
            }

            cameraSlot = packet[offset];
            cameraCount = packet[offset + 1];
            var serialSpan = packet.Slice(offset + 4, CameraSerialBytes);
            var nul = serialSpan.IndexOf((byte)0);
            var serialLen = nul >= 0 ? nul : CameraSerialBytes;
            cameraSerial = serialLen > 0
                ? System.Text.Encoding.ASCII.GetString(serialSpan.Slice(0, serialLen))
                : null;
            offset += CameraInfoBytes;
        }

        var payloadBytes = width * height * 2;
        var trailerBytes = (flags & FlagPerCameraHealth) != 0 ? 4 : 0;
        var raw = new ushort[width * height];

        if ((flags & FlagPayloadDeflate) != 0)
        {
            var compLen = packet.Length - offset - trailerBytes;
            if (compLen <= 0)
            {
                return null;
            }

            var inflated = Inflate(packet.Slice(offset, compLen), payloadBytes);
            if (inflated == null)
            {
                return null;
            }

            Buffer.BlockCopy(inflated, 0, raw, 0, payloadBytes);
            offset += compLen;
        }
        else
        {
            if (packet.Length < offset + payloadBytes + trailerBytes)
            {
                return null;
            }

            for (var i = 0; i < raw.Length; i++)
            {
                raw[i] = BitConverter.ToUInt16(packet.Slice(offset, 2));
                offset += 2;
            }
        }

        byte[]? health = null;
        if (trailerBytes > 0)
        {
            if (packet.Length < offset + trailerBytes)
            {
                return null;
            }

            health = packet.Slice(offset, trailerBytes).ToArray();
        }

        return new ParsedFrame
        {
            Sequence = sequence,
            TimestampUs = timestampUs,
            Width = width,
            Height = height,
            RawPixels = raw,
            CameraHealth = health,
            Meta = meta,
            Version = version,
            CameraSlot = cameraSlot,
            CameraCount = cameraCount,
            CameraSerial = cameraSerial,
        };
    }

    private static byte[]? Inflate(ReadOnlySpan<byte> source, int expectedLength)
    {
        try
        {
            using var input = new System.IO.MemoryStream(source.ToArray());
            using var zlib = new System.IO.Compression.ZLibStream(
                input, System.IO.Compression.CompressionMode.Decompress);
            var output = new byte[expectedLength];
            var read = 0;
            while (read < expectedLength)
            {
                var n = zlib.Read(output, read, expectedLength - read);
                if (n == 0)
                {
                    break;
                }

                read += n;
            }

            return read == expectedLength ? output : null;
        }
        catch
        {
            return null;
        }
    }
}
