package net.runelite.client.plugins.decrypt;

import java.util.ArrayList;
import java.util.List;

final class ClassFileModel
{
    private final String packageName;
    private final String className;
    private final List<String> methodNames = new ArrayList<>();
    private final List<String> fieldNames = new ArrayList<>();

    ClassFileModel(String packageName, String className)
    {
        this.packageName = packageName;
        this.className = className;
    }

    String getPackageName()
    {
        return packageName;
    }

    String getClassName()
    {
        return className;
    }

    List<String> getMethodNames()
    {
        return methodNames;
    }

    List<String> getFieldNames()
    {
        return fieldNames;
    }

    String toJavaRelativePath()
    {
        String pkg = packageName == null || packageName.isBlank() ? "" : packageName.replace('.', '/') + "/";
        return pkg + className + ".java";
    }
}
