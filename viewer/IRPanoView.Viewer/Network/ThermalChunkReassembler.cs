namespace IRPanoView.Viewer.Network;

public sealed class ThermalChunkReassembler
{
    public const uint Magic = 0x43505249; // "IRPC"
    public const int HeaderBytes = 20;

    private sealed class FrameAssembly
    {
        public required byte[] Buffer { get; init; }
        public required bool[] Received { get; init; }
        public int ReceivedCount { get; set; }
    }

    private readonly Dictionary<uint, FrameAssembly> _pending = new();

    public byte[]? Push(ReadOnlySpan<byte> packet)
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

        var frameSeq = BitConverter.ToUInt32(packet.Slice(4, 4));
        var chunkIndex = BitConverter.ToUInt16(packet.Slice(8, 2));
        var chunkCount = BitConverter.ToUInt16(packet.Slice(10, 2));
        var byteOffset = BitConverter.ToUInt32(packet.Slice(12, 4));
        var totalBytes = BitConverter.ToUInt32(packet.Slice(16, 4));
        if (chunkCount == 0 || chunkIndex >= chunkCount || totalBytes == 0)
        {
            return null;
        }

        var payload = packet.Slice(HeaderBytes);
        if (byteOffset + payload.Length > totalBytes)
        {
            return null;
        }

        if (!_pending.TryGetValue(frameSeq, out var assembly))
        {
            assembly = new FrameAssembly
            {
                Buffer = new byte[totalBytes],
                Received = new bool[chunkCount],
            };
            _pending[frameSeq] = assembly;
        }

        if (assembly.Buffer.Length != totalBytes || assembly.Received.Length != chunkCount)
        {
            _pending.Remove(frameSeq);
            return null;
        }

        if (!assembly.Received[chunkIndex])
        {
            payload.CopyTo(assembly.Buffer.AsSpan((int)byteOffset, payload.Length));
            assembly.Received[chunkIndex] = true;
            assembly.ReceivedCount++;
        }

        if (assembly.ReceivedCount < chunkCount)
        {
            return null;
        }

        _pending.Remove(frameSeq);
        PruneStale(frameSeq);
        return assembly.Buffer;
    }

    private void PruneStale(uint currentSeq)
    {
        if (_pending.Count <= 4)
        {
            return;
        }

        foreach (var key in _pending.Keys.Where(k => k + 8 < currentSeq).ToList())
        {
            _pending.Remove(key);
        }
    }
}
