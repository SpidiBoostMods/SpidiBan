package net.spidiboost.spidiban;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class NicknameFile {
    public static final String NAME = "spidiban-nicknames.txt";
    private static final String TEMPLATE = "# Вставьте никнеймы ниже.\n"
            + "# Разделители: пробел, запятая или точка с запятой.\n"
            + "# Сохраните файл, затем /sb accept (FunPay) или /sb cheats (Читы).\n\n";

    private NicknameFile() {}

    public static Path prepare(Path config, Path game) throws IOException {
        Path target = config.toAbsolutePath().normalize().resolve(NAME);
        Files.createDirectories(target.getParent());
        if (!Files.exists(target)) {
            Path legacy = game.toAbsolutePath().normalize().resolve(NAME);
            try {
                if (Files.isRegularFile(legacy)) Files.copy(legacy, target);
                else Files.writeString(target, TEMPLATE, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } catch (FileAlreadyExistsException ignored) {}
        }
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            throw new IOException("Файл недоступен: " + target);
        }
        return target;
    }

    static List<String> command(String os, Path file) {
        String name = os.toLowerCase(Locale.ROOT);
        String path = file.toAbsolutePath().normalize().toString();
        if (name.startsWith("windows")) return List.of("notepad.exe", path);
        if (name.contains("mac")) return List.of("/usr/bin/open", "-a", "TextEdit", path);
        return List.of("xdg-open", path);
    }

    public static void open(Path file) throws IOException {
        Process process = new ProcessBuilder(command(System.getProperty("os.name", ""), file))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (process.waitFor(1, TimeUnit.SECONDS) && process.exitValue() != 0) {
                throw new IOException("Редактор завершился с кодом " + process.exitValue());
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("Открытие редактора прервано", error);
        }
    }
}
