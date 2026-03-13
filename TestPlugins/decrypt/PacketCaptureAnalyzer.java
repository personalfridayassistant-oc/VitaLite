package net.runelite.client.plugins.decrypt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class PacketCaptureAnalyzer
{
    private static final byte[] ZIP_LOCAL_HEADER = new byte[]{0x50, 0x4B, 0x03, 0x04};
    private static final byte[] ZIP_END = new byte[]{0x50, 0x4B, 0x05, 0x06};
    private static final byte[] CLASS_MAGIC = new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};

    private PacketCaptureAnalyzer()
    {
    }

    static List<byte[]> findJarStreams(byte[] data)
    {
        List<byte[]> jars = new ArrayList<>();
        Set<Integer> starts = indexOfAll(data, ZIP_LOCAL_HEADER);

        for (Integer start : starts)
        {
            int endOfCentral = indexOf(data, ZIP_END, start);
            if (endOfCentral < 0)
            {
                continue;
            }

            int end = endOfCentral + 22;
            if (end <= data.length && (end - start) > 64)
            {
                byte[] slice = new byte[end - start];
                System.arraycopy(data, start, slice, 0, slice.length);
                jars.add(slice);
            }
        }

        return jars;
    }

    static List<byte[]> findLooseClasses(byte[] data)
    {
        List<byte[]> classes = new ArrayList<>();
        Set<Integer> starts = indexOfAll(data, CLASS_MAGIC);

        for (Integer start : starts)
        {
            int next = nextBoundary(data, start + 4);
            if (next <= start)
            {
                continue;
            }

            byte[] candidate = new byte[next - start];
            System.arraycopy(data, start, candidate, 0, candidate.length);
            if (ClassFileParser.looksLikeClass(candidate))
            {
                classes.add(candidate);
            }
        }

        return classes;
    }

    private static int nextBoundary(byte[] data, int from)
    {
        int nextClass = indexOf(data, CLASS_MAGIC, from);
        int nextZip = indexOf(data, ZIP_LOCAL_HEADER, from);

        int boundary = data.length;
        if (nextClass >= 0)
        {
            boundary = Math.min(boundary, nextClass);
        }
        if (nextZip >= 0)
        {
            boundary = Math.min(boundary, nextZip);
        }
        return boundary;
    }

    private static Set<Integer> indexOfAll(byte[] data, byte[] target)
    {
        Set<Integer> out = new LinkedHashSet<>();
        int from = 0;
        while (from < data.length)
        {
            int idx = indexOf(data, target, from);
            if (idx < 0)
            {
                break;
            }
            out.add(idx);
            from = idx + 1;
        }
        return out;
    }

    private static int indexOf(byte[] data, byte[] target, int from)
    {
        outer:
        for (int i = from; i <= data.length - target.length; i++)
        {
            for (int j = 0; j < target.length; j++)
            {
                if (data[i + j] != target[j])
                {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
