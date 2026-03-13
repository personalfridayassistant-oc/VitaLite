package net.runelite.client.plugins.decrypt;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ClassFileParser
{
    private static final int ACC_SYNTHETIC = 0x1000;

    private ClassFileParser()
    {
    }

    static boolean looksLikeClass(byte[] classBytes)
    {
        if (classBytes.length < 32)
        {
            return false;
        }

        ByteBuffer bb = ByteBuffer.wrap(classBytes).order(ByteOrder.BIG_ENDIAN);
        return bb.getInt() == 0xCAFEBABE;
    }

    static ClassFileModel parse(byte[] classBytes)
    {
        try
        {
            ByteBuffer bb = ByteBuffer.wrap(classBytes).order(ByteOrder.BIG_ENDIAN);
            if (bb.getInt() != 0xCAFEBABE)
            {
                return null;
            }

            bb.getShort();
            bb.getShort();

            int cpCount = Short.toUnsignedInt(bb.getShort());
            Map<Integer, Object> cp = new HashMap<>();
            for (int i = 1; i < cpCount; i++)
            {
                int tag = Byte.toUnsignedInt(bb.get());
                switch (tag)
                {
                    case 1:
                    {
                        int len = Short.toUnsignedInt(bb.getShort());
                        byte[] utf = new byte[len];
                        bb.get(utf);
                        cp.put(i, new String(utf));
                        break;
                    }
                    case 7:
                    case 8:
                        cp.put(i, Short.toUnsignedInt(bb.getShort()));
                        break;
                    case 9:
                    case 10:
                    case 11:
                    case 12:
                    case 3:
                    case 4:
                    case 18:
                        bb.getInt();
                        break;
                    case 5:
                    case 6:
                        bb.getLong();
                        i++;
                        break;
                    case 15:
                        bb.get();
                        bb.getShort();
                        break;
                    case 16:
                    case 19:
                    case 20:
                        bb.getShort();
                        break;
                    case 17:
                        bb.getInt();
                        break;
                    default:
                        return null;
                }
            }

            bb.getShort();
            int thisClassIdx = Short.toUnsignedInt(bb.getShort());
            bb.getShort();

            String internalName = resolveClassName(cp, thisClassIdx);
            if (internalName == null)
            {
                return null;
            }

            int slash = internalName.lastIndexOf('/');
            String packageName = slash >= 0 ? internalName.substring(0, slash).replace('/', '.') : "";
            String className = slash >= 0 ? internalName.substring(slash + 1) : internalName;
            ClassFileModel model = new ClassFileModel(packageName, className);

            int interfaces = Short.toUnsignedInt(bb.getShort());
            for (int i = 0; i < interfaces; i++)
            {
                bb.getShort();
            }

            int fields = Short.toUnsignedInt(bb.getShort());
            for (int i = 0; i < fields; i++)
            {
                int access = Short.toUnsignedInt(bb.getShort());
                int nameIdx = Short.toUnsignedInt(bb.getShort());
                bb.getShort();
                String name = asString(cp.get(nameIdx));
                if (name != null && !name.startsWith("this$") && (access & ACC_SYNTHETIC) == 0)
                {
                    model.getFieldNames().add(name);
                }
                skipAttributes(bb);
            }

            int methods = Short.toUnsignedInt(bb.getShort());
            for (int i = 0; i < methods; i++)
            {
                int access = Short.toUnsignedInt(bb.getShort());
                int nameIdx = Short.toUnsignedInt(bb.getShort());
                bb.getShort();
                String name = asString(cp.get(nameIdx));
                if (name != null && !name.startsWith("lambda$") && (access & ACC_SYNTHETIC) == 0)
                {
                    model.getMethodNames().add(name);
                }
                skipAttributes(bb);
            }

            return model;
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static void skipAttributes(ByteBuffer bb)
    {
        int count = Short.toUnsignedInt(bb.getShort());
        for (int i = 0; i < count; i++)
        {
            bb.getShort();
            int len = bb.getInt();
            bb.position(bb.position() + len);
        }
    }

    private static String resolveClassName(Map<Integer, Object> cp, int classIndex)
    {
        Object ref = cp.get(classIndex);
        if (!(ref instanceof Integer))
        {
            return null;
        }
        return asString(cp.get((Integer) ref));
    }

    private static String asString(Object obj)
    {
        return obj instanceof String ? (String) obj : null;
    }
}
