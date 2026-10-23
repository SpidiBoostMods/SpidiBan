package net.spidiboost.spidiban;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class NicknameFileTest {
    @TempDir Path root;

    @Test void createsConfigAndReadableTemplateInUnicodeInstance() throws Exception {
        Path game = root.resolve("Мой инстанс & (тест)");
        Path config = game.resolve("config");
        Path file = NicknameFile.prepare(config, game);
        assertEquals(config.resolve(NicknameFile.NAME).toAbsolutePath(), file);
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.readString(file).contains("\n# Разделители"));
        assertFalse(Files.readString(file).contains("\\n"));
    }

    @Test void copiesLegacyListWithoutDeletingOrOverwriting() throws Exception {
        Path legacy = root.resolve(NicknameFile.NAME);
        Files.writeString(legacy, "Spidi_Boost\nNick2; Nick3");
        Path config = root.resolve("config");
        Path target = NicknameFile.prepare(config, root);
        assertEquals(Files.readString(legacy), Files.readString(target));
        Files.writeString(target, "Edited_Nick");
        NicknameFile.prepare(config, root);
        assertEquals("Edited_Nick", Files.readString(target));
        assertEquals("Spidi_Boost\nNick2; Nick3", Files.readString(legacy));
    }

    @Test void rejectsDirectoryInsteadOfFile() throws Exception {
        Path config = root.resolve("config");
        Files.createDirectories(config.resolve(NicknameFile.NAME));
        assertThrows(IOException.class, () -> NicknameFile.prepare(config, root));
    }

    @Test void reportsUnusableConfigDirectory() throws Exception {
        Path config = root.resolve("config");
        Files.writeString(config, "keep");
        assertThrows(IOException.class, () -> NicknameFile.prepare(config, root));
        assertEquals("keep", Files.readString(config));
    }

    @Test void launchesWindowsNotepadWithoutShellOrManualQuoting() {
        Path file = root.resolve("Мой инстанс & (test)").resolve(NicknameFile.NAME);
        assertEquals(List.of("notepad.exe", file.toAbsolutePath().toString()), NicknameFile.command("Windows 11", file));
        assertEquals(List.of("notepad.exe", file.toAbsolutePath().toString()), NicknameFile.command("Windows 10", file));
    }

    @Test void preservesMacTextEditAndLinuxOpener() {
        Path file = root.resolve(NicknameFile.NAME);
        assertEquals(List.of("/usr/bin/open", "-a", "TextEdit", file.toAbsolutePath().toString()), NicknameFile.command("Mac OS X", file));
        assertEquals(List.of("xdg-open", file.toAbsolutePath().toString()), NicknameFile.command("Linux", file));
    }
}
