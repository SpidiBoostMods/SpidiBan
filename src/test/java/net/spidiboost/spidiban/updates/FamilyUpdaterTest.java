package net.spidiboost.spidiban.updates;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

class FamilyUpdaterTest {
    @TempDir Path root;
    @Test void everyInstalledSubsetContainsExactlyThoseMods(){
        for(int mask=0;mask<16;mask++){Set<String> ids=new HashSet<>();ids.add("reallystafftool");for(int i=0;i<4;i++)if((mask&(1<<i))!=0)ids.add(ModCatalog.ALL.get(i).id());
            var result=ModCatalog.installed(ids);assertEquals(Integer.bitCount(mask),result.size());assertTrue(result.stream().allMatch(m->ids.contains(m.id())));assertFalse(result.stream().anyMatch(m->m.id().equals("reallystafftool")));}
    }
    @Test void independentBundledCopiesElectExactlyOneOwnerEvenConcurrently()throws Exception {
        var map=new ConcurrentHashMap<String,Object>();
        var share=(net.fabricmc.loader.api.ObjectShare)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class[]{net.fabricmc.loader.api.ObjectShare.class},(o,m,a)->switch(m.getName()){case "putIfAbsent"->map.putIfAbsent((String)a[0],a[1]);case "get"->map.get(a[0]);default->throw new UnsupportedOperationException();});
        var winners=new AtomicInteger();var calls=new AtomicInteger();var pool=Executors.newFixedThreadPool(4);
        try{var tasks=new ArrayList<Future<?>>();for(int i=0;i<4;i++)tasks.add(pool.submit(()->{if(SharedUpdater.claim(share,calls::incrementAndGet))winners.incrementAndGet();}));for(var task:tasks)task.get();}finally{pool.shutdown();}
        assertEquals(1,winners.get());((Runnable)share.get(SharedUpdater.OWNER)).run();assertEquals(1,calls.get());
    }
    @Test void eachManifestMatchesItsOwnRepositoryAndFilename()throws Exception {
        for(var mod:ModCatalog.ALL){var r=Release.parse(mod,"version=2.3.4\nminecraft=1.21.4\nartifact="+mod.artifact("2.3.4")+"\nsha256="+"a".repeat(64));assertTrue(r.newerThan("2.3.3"));assertFalse(r.newerThan("2.3.4"));assertFalse(r.newerThan("9.0.0"));assertTrue(r.download().toString().startsWith(mod.base()));
            assertThrows(IOException.class,()->Release.parse(mod,"version=2.3.4\nminecraft=1.21.11\nartifact="+mod.artifact("2.3.4")+"\nsha256="+"a".repeat(64)));}
    }
    private BatchPlan plan(int count)throws Exception {
        Path game=root.resolve("инстанс Minecraft"),mods=game.resolve("mods"),dir=game.resolve(".spidiboost-updates");Files.createDirectories(mods);Files.createDirectories(dir);
        Files.writeString(game.resolve("config.txt"),"keep");Files.writeString(mods.resolve("AdminTools.jar"),"protected");var changes=new ArrayList<BatchPlan.Change>();
        for(int i=0;i<count;i++){Path old=mods.resolve("old"+i+".jar"),to=mods.resolve("Mod"+i+"-1.21.4-1.1.0.jar"),staged=dir.resolve("stage"+i+".jar");Files.writeString(old,"old "+i);Files.writeString(staged,"new "+i);changes.add(new BatchPlan.Change(old,to,staged,BatchInstall.hash(old),BatchInstall.hash(staged)));}
        return new BatchPlan(ProcessHandle.current().pid(),game,changes,List.of(),root,List.of("java","--accessToken","test-private-value","ник пробел"),false);
    }
    @Test void protocolKeepsUnicodePathsAndTokenizedArgumentsInMemory()throws Exception {
        var p=plan(4);var out=new ByteArrayOutputStream();p.write(out);assertEquals(p,BatchPlan.read(new ByteArrayInputStream(out.toByteArray())));
        assertThrows(IOException.class,()->BatchPlan.read(new ByteArrayInputStream(new byte[20])));
    }
    @Test void wholeBatchRenamesJarsBacksUpEveryOldVersionAndPreservesUnrelatedFiles()throws Exception {
        var p=plan(4);BatchInstall.install(p);for(var c:p.changes()){assertFalse(Files.exists(c.target()));assertEquals(c.newHash(),BatchInstall.hash(c.destination()));assertEquals(c.oldHash(),BatchInstall.hash(p.game().resolve(".spidiboost-updates/backups/"+c.oldHash()+".jar.bak")));assertFalse(Files.exists(c.staged()));}
        assertEquals("keep",Files.readString(p.game().resolve("config.txt")));assertEquals("protected",Files.readString(p.game().resolve("mods/AdminTools.jar")));
    }
    @Test void corruptedSecondDownloadLeavesTheEntireBatchUntouched()throws Exception {
        var p=plan(4);Files.writeString(p.changes().get(1).staged(),"tampered");assertThrows(IOException.class,()->BatchInstall.install(p));assertOriginals(p);
    }
    @Test void userInstalledNewVersionIsNeverOverwritten()throws Exception {
        var p=plan(2);Files.writeString(p.changes().get(1).destination(),"user upgrade");assertThrows(IOException.class,()->BatchInstall.install(p));assertOriginals(p);assertEquals("user upgrade",Files.readString(p.changes().get(1).destination()));
    }
    @Test void commitFailureRollsBackPreviouslyReplacedMembers()throws Exception {
        var p=plan(4);var n=new AtomicInteger();assertThrows(IllegalStateException.class,()->BatchInstall.install(p,c->{if(n.incrementAndGet()==3)throw new IllegalStateException("Injected I/O interruption");}));assertOriginals(p);for(var c:p.changes())assertFalse(Files.exists(c.destination()));
    }
    @Test void movedOrModifiedOldJarAbortsBeforeAnyCommit()throws Exception {
        var p=plan(2);Files.writeString(p.changes().get(1).target(),"manually replaced");assertThrows(IOException.class,()->BatchInstall.install(p));assertEquals(p.changes().getFirst().oldHash(),BatchInstall.hash(p.changes().getFirst().target()));assertFalse(Files.exists(p.changes().getFirst().destination()));
    }
    @Test void foreignPathsAndOverlappingDestinationsAreRejected()throws Exception {
        var p=plan(2);var a=p.changes().getFirst();var bad1=new BatchPlan.Change(a.target(),root.resolve("Foreign-1.21.4-1.1.0.jar"),a.staged(),a.oldHash(),a.newHash());assertThrows(IOException.class,()->BatchInstall.install(new BatchPlan(p.pid(),p.game(),List.of(bad1),List.of(),p.cwd(),List.of(),false)));
        var b=p.changes().get(1);var bad2=new BatchPlan.Change(b.target(),a.destination(),b.staged(),b.oldHash(),b.newHash());assertThrows(IOException.class,()->BatchInstall.validate(new BatchPlan(p.pid(),p.game(),List.of(a,bad2),List.of(),p.cwd(),List.of(),false)));
    }
    @Test void helperWaitsForOwningJvmAndRestartsOnceAfterAllFilesChange()throws Exception {
        var p=plan(4);Path java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");Path code=root.resolve("Fixture.java");
        Files.writeString(code,"import java.nio.file.*; public class Fixture {public static void main(String[] a)throws Exception{if(a.length==0){System.out.println(\"LIVE\");System.out.flush();System.in.read();}else Files.writeString(Path.of(a[0]),\"restart\\n\",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}}");
        Path javac=java.resolveSibling(System.getProperty("os.name").startsWith("Windows")?"javac.exe":"javac");assertEquals(0,new ProcessBuilder(javac.toString(),"-d",root.toString(),code.toString()).inheritIO().start().waitFor());
        // Helper JAR path is ASCII-relative; the working directory and plan carry Unicode safely.
        var cp=getClass().getProtectionDomain().getCodeSource().getLocation();Path fixtureDir=root.resolve("fixture");Files.createDirectories(fixtureDir);Files.copy(root.resolve("Fixture.class"),fixtureDir.resolve("Fixture.class"));
        Process old=new ProcessBuilder(java.toString(),"-cp",".","Fixture").directory(fixtureDir.toFile()).start();assertEquals("LIVE",new BufferedReader(new InputStreamReader(old.getInputStream())).readLine());
        Path agent=p.game().resolve(".spidiboost-updates/agent.jar");Files.copy(Path.of("build/shared-agent/spidiban-shared-update-agent.jar"),agent);Path marker=fixtureDir.resolve("restarted.txt");
        var actual=new BatchPlan(old.pid(),p.game(),p.changes(),List.of(),fixtureDir,List.of(java.toString(),"-cp",".","Fixture","restarted.txt"),false);
        Process helper=new ProcessBuilder(java.toString(),"-jar","agent.jar").directory(agent.getParent().toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try{try(var pipe=helper.getOutputStream()){actual.write(pipe);}assertEquals("READY",new BufferedReader(new InputStreamReader(helper.getInputStream())).readLine());assertOriginals(p);assertFalse(Files.exists(marker));old.getOutputStream().write(1);old.getOutputStream().flush();assertTrue(old.waitFor(10,TimeUnit.SECONDS));assertTrue(helper.waitFor(20,TimeUnit.SECONDS));assertEquals(0,helper.exitValue());
            for(int i=0;i<100&&!Files.exists(marker);i++)Thread.sleep(50);assertEquals("restart\n",Files.readString(marker));for(var c:p.changes())assertEquals(c.newHash(),BatchInstall.hash(c.destination()));
        }finally{old.destroyForcibly();helper.destroyForcibly();}
    }
    @Test void obsoleteHistAddonIsBackedUpAndRemovedOnlyWithSuccessfulBatch()throws Exception {
        var p=plan(2);Path addon=p.game().resolve("mods/SpidiBan-Shist-Addon.jar");
        try(var zip=new ZipOutputStream(Files.newOutputStream(addon))){zip.putNextEntry(new ZipEntry("fabric.mod.json"));zip.write("{\"id\":\"spidiban_shist\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
        String hash=BatchInstall.hash(addon);var actual=new BatchPlan(p.pid(),p.game(),p.changes(),List.of(new BatchPlan.Removal(addon,hash)),p.cwd(),List.of(),false);BatchInstall.install(actual);assertFalse(Files.exists(addon));assertEquals(hash,BatchInstall.hash(p.game().resolve(".spidiboost-updates/backups/"+hash+".jar.bak")));
    }
    @Test void unrelatedModCannotBeRemovedByLegacyMigration()throws Exception {
        var p=plan(1);Path other=p.game().resolve("mods/AdminTools.jar");var actual=new BatchPlan(p.pid(),p.game(),p.changes(),List.of(new BatchPlan.Removal(other,BatchInstall.hash(other))),p.cwd(),List.of(),false);assertThrows(IOException.class,()->BatchInstall.install(actual));assertOriginals(p);assertEquals("protected",Files.readString(other));
    }
    private void assertOriginals(BatchPlan p)throws Exception{for(var c:p.changes())assertEquals(c.oldHash(),BatchInstall.hash(c.target()));}
}
