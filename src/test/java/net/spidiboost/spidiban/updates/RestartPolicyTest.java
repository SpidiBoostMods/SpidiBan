package net.spidiboost.spidiban.updates;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class RestartPolicyTest {
    @TempDir Path game;
    @Test void defaultOnPersistentOffOnAndCorruptPreferencesFailClosed()throws Exception{
        assertTrue(RestartPolicy.enabled(game));RestartPolicy.set(game,false);assertFalse(RestartPolicy.enabled(game));assertTrue(Files.readString(RestartPolicy.file(game)).contains("autoRestart=false"));RestartPolicy.set(game,true);assertTrue(RestartPolicy.enabled(game));
        Files.writeString(RestartPolicy.file(game),"autoRestart=broken");assertFalse(RestartPolicy.enabled(game));
    }
    @Test void retainsOtherFieldsAndUnicodeDirectory()throws Exception{
        Path dir=game.resolve("インスタンス Юникод with spaces");Files.createDirectories(dir);RestartPolicy.set(dir,false);Files.writeString(RestartPolicy.file(dir),"autoRestart=false\nother=keep\n");RestartPolicy.set(dir,true);assertTrue(Files.readString(RestartPolicy.file(dir)).contains("other=keep"));assertTrue(RestartPolicy.enabled(dir));
    }
    @Test void reservationPreventsLegacyClaimAndElectsOnlyOneNewOwnerAcrossRelocatedCopies(){
        var values=new HashMap<String,Object>();var gate=new AtomicReference<Runnable>();Runnable relay=()->{var run=gate.get();if(run!=null)run.run();};
        values.put(SharedUpdater.OWNER,relay);values.put(SharedUpdater.RESERVATION,List.of(relay,gate));
        var share=(net.fabricmc.loader.api.ObjectShare)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{net.fabricmc.loader.api.ObjectShare.class},(o,m,a)->switch(m.getName()){case "get"->values.get(a[0]);case "putIfAbsent"->values.putIfAbsent((String)a[0],a[1]);default->throw new UnsupportedOperationException();});
        assertNotNull(share.putIfAbsent(SharedUpdater.OWNER,(Runnable)()->fail("legacy coordinator ran")));
        int[] calls={0};assertTrue(SharedUpdater.claim(share,()->calls[0]++));assertFalse(SharedUpdater.claim(share,()->fail("second owner")));relay.run();assertEquals(1,calls[0]);
    }
}
