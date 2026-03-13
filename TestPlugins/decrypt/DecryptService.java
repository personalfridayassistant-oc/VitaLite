package net.runelite.client.plugins.decrypt;

import lombok.extern.slf4j.Slf4j;

import javax.inject.Singleton;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;

@Slf4j
@Singleton
public class DecryptService
{
    public void analyze(Path inputFile, Path outputDirectory, boolean overwrite)
    {
        try
        {
            if (!Files.exists(inputFile))
            {
                log.error("Input file does not exist: {}", inputFile);
                return;
            }

            Files.createDirectories(outputDirectory);
            byte[] inputBytes = Files.readAllBytes(inputFile);

            String lower = inputFile.getFileName().toString().toLowerCase();
            if (lower.endsWith(".jar"))
            {
                processJar(inputBytes, outputDirectory, overwrite);
                return;
            }

            if (lower.endsWith(".pcap") || lower.endsWith(".pcapng"))
            {
                processCapture(inputBytes, outputDirectory, overwrite);
                return;
            }

            log.warn("Unsupported input type for file: {}", inputFile);
        }
        catch (Exception ex)
        {
            log.error("Failed to analyze input {}", inputFile, ex);
        }
    }

    private void processCapture(byte[] captureBytes, Path outputDirectory, boolean overwrite) throws IOException
    {
        List<byte[]> candidateJars = PacketCaptureAnalyzer.findJarStreams(captureBytes);
        List<byte[]> candidateClasses = PacketCaptureAnalyzer.findLooseClasses(captureBytes);

        int written = 0;
        for (byte[] jar : candidateJars)
        {
            written += processJar(jar, outputDirectory, overwrite);
        }

        for (byte[] classBytes : candidateClasses)
        {
            ClassFileModel model = ClassFileParser.parse(classBytes);
            if (model == null)
            {
                continue;
            }

            Path javaFile = outputDirectory.resolve(model.toJavaRelativePath());
            writeSourceFile(javaFile, JavaStubEmitter.emit(model), overwrite);
            written++;
        }

        log.info("decrypt finished capture analysis. Wrote {} .java files to {}", written, outputDirectory);
    }

    private int processJar(byte[] jarBytes, Path outputDirectory, boolean overwrite) throws IOException
    {
        List<ClassFileModel> models = new ArrayList<>();

        try (InputStream is = new ByteArrayInputStream(jarBytes);
             JarInputStream jarInputStream = new JarInputStream(is))
        {
            JarEntry entry;
            while ((entry = jarInputStream.getNextJarEntry()) != null)
            {
                if (entry.isDirectory() || !entry.getName().endsWith(".class"))
                {
                    continue;
                }

                byte[] classBytes = readFully(jarInputStream);
                ClassFileModel model = ClassFileParser.parse(classBytes);
                if (model != null)
                {
                    models.add(model);
                }
            }
        }

        models.sort(Comparator.comparing(ClassFileModel::getClassName));
        for (ClassFileModel model : models)
        {
            Path javaFile = outputDirectory.resolve(model.toJavaRelativePath());
            writeSourceFile(javaFile, JavaStubEmitter.emit(model), overwrite);
        }

        log.info("decrypt processed jar stream: {} Java source stubs emitted", models.size());
        return models.size();
    }

    private static void writeSourceFile(Path targetFile, String source, boolean overwrite) throws IOException
    {
        Files.createDirectories(targetFile.getParent());
        if (Files.exists(targetFile) && !overwrite)
        {
            return;
        }
        Files.writeString(targetFile, source);
    }

    private static byte[] readFully(InputStream in) throws IOException
    {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int read;
        while ((read = in.read(buf)) != -1)
        {
            bos.write(buf, 0, read);
        }
        return bos.toByteArray();
    }
}
