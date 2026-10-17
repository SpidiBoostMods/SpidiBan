package net.spidiboost.spidiban;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BanEvidenceTest {
    static String issued(String prefix, String staff, String player) {
        return prefix + "Администратор " + staff + " временно забанил игрока " + player + " на 50 дней по причине 'Читы'";
    }

    @Test void actualBroadcastsAndSilentPrefixes() {
        for (String prefix : new String[]{"", "[Silent] ", "[23:23:27] [Silent] ", "§6[Silent] §r"}) {
            var evidence = BanEvidence.parse(issued(prefix, "MMRVasya", "ArsenyiBond"));
            assertNotNull(evidence);
            assertEquals(BanEvidence.Kind.ISSUED, evidence.kind());
            assertEquals("MMRVasya", evidence.administrator());
            assertTrue(evidence.forPlayer("arsenyibond"));
            assertFalse(evidence.forPlayer("ArsenyiBond1"));
        }
        assertNotNull(BanEvidence.parse("Администратор MMRVasya забанил игрока Test по причине 'FP_RW'"));
        assertNotNull(BanEvidence.parse("Администратор MMRVasya забанил игрока Test навсегда по причине 'FP_RW'"));
    }

    @Test void normalizationAndRejection() {
        assertEquals(BanEvidence.Kind.ALREADY_BANNED, BanEvidence.parse("[23:23:27] Игрок Frolok_q68 уже забанен и у вас нету прав на новый бан!").kind());
        assertNotNull(BanEvidence.parse(issued("[Silent] ", "MMRVasya", "ArsenyiBond").replace(" ", "\u00a0\u200b")));
        assertNotNull(BanEvidence.parse("Ошибка: Игрок не найден."));
        assertNotNull(BanEvidence.parse("PLAYER DOES NOT EXIST"));
        for (String text : new String[]{"Bans » Выдан бан Frolok_q68. FP_RW (навсегда)",
                "[SpidiBan] Бан для Frolok_q68 отправлен.",
                "Player » " + issued("", "MMRVasya", "ArsenyiBond"),
                "Игрок Frolok_q68 уже забанен", "[Silent] Администратор LongStaffNickname99 забанил игрока Test по причине 'Читы'"})
            assertNull(BanEvidence.parse(text), text);
    }

    @Test void hundredThousandIndependentPlayers() {
        for (int i = 0; i < 100000; i++) {
            String player = "Player" + i;
            var evidence = BanEvidence.parse(issued(i % 2 == 0 ? "[Silent] " : "", "Staff", player));
            assertNotNull(evidence);
            assertTrue(evidence.forPlayer(player));
            assertFalse(evidence.forPlayer(player + "0"));
            assertNull(BanEvidence.parse("Bans » Выдан бан " + player + ". FP_RW (навсегда)"));
        }
    }

    @Test void boundedInboxResetsAndPreservesOrder() throws Exception {
        BanInbox inbox = new BanInbox();
        Object connection = new Object();
        inbox.capture(connection, issued("", "Staff", "Inactive"), 0);
        assertNull(inbox.poll());
        inbox.start();
        Thread network = new Thread(() -> {
            for (int i = 0; i < 257; i++) inbox.capture(connection, issued("", "Staff", "Player" + i), i);
        });
        network.start();
        network.join();
        assertTrue(inbox.overflowed());
        for (int i = 0; i < 256; i++) {
            var item = inbox.poll();
            assertSame(connection, item.connection());
            assertEquals(i, item.receivedAt());
            assertEquals("Player" + i, item.evidence().player());
        }
        assertNull(inbox.poll());
        inbox.stop();
        inbox.start();
        assertFalse(inbox.overflowed());
        assertNull(inbox.poll());
    }
}
