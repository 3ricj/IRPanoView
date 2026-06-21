namespace IRPanoView.Viewer.Network;

public sealed class RawTcpFramingReader
{
    public const uint Magic = 0x52505249; // "IRPR"

    private readonly Stream _stream;
    private readonly byte[] _header = new byte[8];

    public RawTcpFramingReader(Stream stream)
    {
        _stream = stream;
    }

    public async Task<byte[]?> ReadPacketAsync(CancellationToken cancellationToken)
    {
        if (!await ReadExactAsync(_header, cancellationToken))
        {
            return null;
        }

        var magic = BitConverter.ToUInt32(_header, 0);
        if (magic != Magic)
        {
            throw new InvalidDataException($"Bad raw TCP magic 0x{magic:X8}");
        }

        var length = BitConverter.ToInt32(_header, 4);
        if (length <= 0 || length > 8 * 1024 * 1024)
        {
            throw new InvalidDataException($"Bad raw TCP length {length}");
        }

        var packet = new byte[length];
        if (!await ReadExactAsync(packet, cancellationToken))
        {
            return null;
        }

        return packet;
    }

    private async Task<bool> ReadExactAsync(byte[] buffer, CancellationToken cancellationToken)
    {
        var offset = 0;
        while (offset < buffer.Length)
        {
            var read = await _stream.ReadAsync(buffer.AsMemory(offset, buffer.Length - offset), cancellationToken);
            if (read == 0)
            {
                return false;
            }

            offset += read;
        }

        return true;
    }
}
