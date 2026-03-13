package net.runelite.client.plugins.decrypt;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

final class JavaStubEmitter
{
    private JavaStubEmitter()
    {
    }

    static String emit(ClassFileModel model)
    {
        StringBuilder sb = new StringBuilder();
        if (model.getPackageName() != null && !model.getPackageName().isBlank())
        {
            sb.append("package ").append(model.getPackageName()).append(";\n\n");
        }

        sb.append("/**\n")
          .append(" * Reconstructed by decrypt plugin from captured bytecode.\n")
          .append(" * Generated: ").append(Instant.now()).append("\n")
          .append(" */\n");

        sb.append("public class ").append(model.getClassName()).append(" {\n");

        Set<String> fields = new LinkedHashSet<>(model.getFieldNames());
        for (String field : fields)
        {
            if (isValidIdentifier(field))
            {
                sb.append("    private Object ").append(field).append(";\n");
            }
        }

        if (!fields.isEmpty())
        {
            sb.append("\n");
        }

        Set<String> methods = new LinkedHashSet<>(model.getMethodNames());
        for (String method : methods)
        {
            if (!isValidIdentifier(method))
            {
                continue;
            }

            if ("<init>".equals(method))
            {
                sb.append("    public ").append(model.getClassName()).append("() {\n")
                  .append("        // constructor body unavailable\n")
                  .append("    }\n\n");
                continue;
            }

            if ("<clinit>".equals(method))
            {
                sb.append("    static {\n")
                  .append("        // static initializer body unavailable\n")
                  .append("    }\n\n");
                continue;
            }

            sb.append("    public void ").append(method).append("() {\n")
              .append("        // method body unavailable\n")
              .append("    }\n\n");
        }

        sb.append("}\n");
        return sb.toString();
    }

    private static boolean isValidIdentifier(String value)
    {
        if (value == null || value.isBlank())
        {
            return false;
        }
        if (!Character.isJavaIdentifierStart(value.charAt(0)))
        {
            return false;
        }
        for (int i = 1; i < value.length(); i++)
        {
            if (!Character.isJavaIdentifierPart(value.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }
}
