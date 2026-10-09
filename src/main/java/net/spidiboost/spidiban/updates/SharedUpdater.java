package net.spidiboost.spidiban.updates;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.slf4j.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Each mod bundles its own relocated copy. ObjectShare elects one process-wide coordinator. */
public final class SharedUpdater {
    public static final String OWNER="spidiboost:update-coordinator-v1";
    private static final Logger LOG=LoggerFactory.getLogger("SpidiBoost-updater");
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"SpidiBoost-updates");t.setDaemon(true);return t;});
    private final ExecutorService downloads=Executors.newFixedThreadPool(4,r->{var t=new Thread(r,"SpidiBoost-download");t.setDaemon(true);return t;});
    private final AtomicBoolean checking=new AtomicBoolean();private volatile boolean pending;
    private final GitHubDownload http=new GitHubDownload();
    private final Map<String,UpdateRow> rows=new ConcurrentHashMap<>();
    private record Installed(ModCatalog mod,String version,Path jar){}
    public static void preserve(String id,Runnable save){FabricLoader.getInstance().getObjectShare().put("spidiboost:update-save-"+id,save);}
    public static boolean claim(net.fabricmc.loader.api.ObjectShare share,Runnable owner){return share.putIfAbsent(OWNER,owner)==null;}
    public static void initialize() {
        var loader=FabricLoader.getInstance();
        // Runnable is a JDK interface: relocated packages and mod loading order cannot break election.
        var updater=new SharedUpdater();
        if(!claim(loader.getObjectShare(),()->updater.check(false))){updater.worker.shutdown();updater.downloads.shutdown();return;}
        if(loader.isDevelopmentEnvironment()){updater.worker.shutdown();updater.downloads.shutdown();return;}
        ClientLifecycleEvents.CLIENT_STARTED.register(c->updater.check(true));
        ClientPlayConnectionEvents.JOIN.register((h,s,c)->updater.check(false));
    }
    private List<Installed> installed(Path game)throws IOException {
        var loader=FabricLoader.getInstance();var list=new ArrayList<Installed>();
        for(var spec:ModCatalog.ALL) {
            var found=loader.getModContainer(spec.id());if(found.isEmpty())continue;var mod=found.get();String version=mod.getMetadata().getVersion().getFriendlyString();
            rows.put(spec.id(),new UpdateRow(spec.id(),spec.name(),version,"Проверяем обновление…"));
            var paths=mod.getOrigin().getPaths();Path jar=null;
            if(paths.size()==1){Path p=paths.getFirst();if(!Files.isSymbolicLink(p)&&Files.isRegularFile(p)&&p.getFileName().toString().endsWith(".jar")){Path real=p.toRealPath();if(real.getParent().equals(game.resolve("mods").toRealPath()))jar=real;}}
            if(jar==null)rows.put(spec.id(),new UpdateRow(spec.id(),spec.name(),version,"Внешний или вложенный JAR: обновление вручную"));
            else list.add(new Installed(spec,version,jar));
        }return list;
    }
    public List<UpdateRow> snapshot(){return ModCatalog.ALL.stream().map(m->rows.get(m.id())).filter(Objects::nonNull).toList();}
    private void state(Installed m,String v,String status){rows.put(m.mod().id(),new UpdateRow(m.mod().id(),m.mod().name(),v,status));}
    private void check(boolean startup) {
        if(pending||!checking.compareAndSet(false,true))return;
        worker.execute(()->{
            var changes=new ArrayList<BatchPlan.Change>();Process helper=null;
            try {
                var client=MinecraftClient.getInstance();Path game=FabricLoader.getInstance().getGameDir().toRealPath();List<Installed> installed=installed(game);
                if(installed.isEmpty())return;
                Path dir=game.resolve(".spidiboost-updates");Files.createDirectories(dir);if(!dir.toRealPath().startsWith(game)||Files.isSymbolicLink(dir))throw new IOException("Foreign update directory");
                if(startup)client.execute(()->client.setScreen(new UpdateScreen(this::snapshot,"Проверяем установленные моды…","SpidiBoost",false,client.currentScreen)));
                // Per-mod failures do not suppress healthy updates for the other installed mods.
                var futures=installed.stream().map(m->CompletableFuture.supplyAsync(()->prepare(m,game,dir),downloads)).toList();
                for(var future:futures){var c=future.join();if(c!=null)changes.add(c);}
                if(changes.isEmpty()) {
                    if(startup)client.execute(()->{if(client.currentScreen instanceof UpdateScreen screen){client.setScreen(new UpdateScreen(this::snapshot,"Проверка завершена","Установленные моды проверены",false,null));CompletableFuture.delayedExecutor(2,TimeUnit.SECONDS).execute(()->client.execute(()->{if(client.currentScreen instanceof UpdateScreen current)current.close();}));}});
                    return;
                }
                List<String> detectedRestart=RestartCommand.detect(game);
                // An explicitly listed old mod JAR on the replayed classpath becomes invalid after a rename.
                List<String> restart=changes.stream().anyMatch(c->detectedRestart.stream().anyMatch(a->a.contains(c.target().toString())))?List.of():detectedRestart;
                Path agent=extractAgent(dir),java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java");
                helper=new ProcessBuilder(java.toString(),"-jar",agent.getFileName().toString()).directory(dir.toFile()).redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("agent.log").toFile())).start();
                boolean legacy=installed.stream().anyMatch(m->m.mod().id().equals("spidicard")&&new Release(m.mod(),"1.5.0","","").newerThan(m.version()));
                var removals=new ArrayList<BatchPlan.Removal>();
                if(changes.stream().anyMatch(c->c.destination().getFileName().toString().startsWith("SpidiBan-"))) {
                    var addon=FabricLoader.getInstance().getModContainer("spidiban_shist");
                    if(addon.isPresent()){var paths=addon.get().getOrigin().getPaths();if(paths.size()!=1||Files.isSymbolicLink(paths.getFirst()))throw new IOException("Legacy addon outside own instance");Path oldAddon=paths.getFirst().toRealPath();if(!oldAddon.getParent().equals(game.resolve("mods").toRealPath()))throw new IOException("External legacy addon");removals.add(new BatchPlan.Removal(oldAddon,BatchInstall.hash(oldAddon)));}
                }
                var plan=new BatchPlan(ProcessHandle.current().pid(),game,changes,removals,Path.of(System.getProperty("user.dir")).toAbsolutePath(),restart,legacy);
                try(var pipe=helper.getOutputStream()){plan.write(pipe);}Process waiting=helper;
                var ready=CompletableFuture.supplyAsync(()->{try{return new BufferedReader(new InputStreamReader(waiting.getInputStream(),StandardCharsets.UTF_8)).readLine();}catch(IOException e){throw new CompletionException(e);}});
                if(!"READY".equals(ready.get(15,TimeUnit.SECONDS)))throw new IOException("Updater helper did not become ready");
                pending=true;boolean auto=!restart.isEmpty();
                client.execute(()-> {
                    if(auto)for(var spec:ModCatalog.ALL){Object hook=FabricLoader.getInstance().getObjectShare().get("spidiboost:update-save-"+spec.id());if(hook instanceof Runnable r)try{r.run();}catch(RuntimeException e){LOG.warn("Could not preserve {} results ({})",spec.name(),e.getClass().getSimpleName());}}
                    client.setScreen(new UpdateScreen(this::snapshot,auto?"Обновления проверены. Перезапускаем Minecraft…":"Обновления готовы. Перезапустите Minecraft через лаунчер.",auto?"Результаты проверки сохранены":"Файлы установятся после обычного выхода из игры",auto,client.currentScreen));
                    if(auto)CompletableFuture.delayedExecutor(4,TimeUnit.SECONDS).execute(()->client.execute(client::scheduleStop));
                });
                changes.clear();helper=null;
            }catch(Exception e){LOG.debug("Update check failed ({})",e.getClass().getSimpleName());}
            finally{if(helper!=null)helper.destroyForcibly();for(var c:changes)try{Files.deleteIfExists(c.staged());}catch(IOException ignored){}checking.set(false);}
        });
    }
    private BatchPlan.Change prepare(Installed m,Path game,Path dir) {
        Path staged=null;
        try {
            var release=Release.parse(m.mod(),new String(http.get(m.mod().latest(),16384),StandardCharsets.UTF_8));
            if(!release.newerThan(m.version())){state(m,m.version(),"Установлена актуальная версия");return null;}
            state(m,m.version()+" → "+release.version(),"Загружаем обновление…");String old=BatchInstall.hash(m.jar());
            staged=Files.createTempFile(dir,"release-",".jar");Files.write(staged,http.get(release.download(),32*1024*1024));Artifact.validate(staged,release);
            Path destination=game.resolve("mods").resolve(release.artifact());
            var c=new BatchPlan.Change(m.jar(),destination,staged,old,release.sha256());state(m,release.version(),"Обновление проверено ✓");staged=null;return c;
        }catch(Exception e){state(m,m.version(),"Проверка недоступна. Текущая версия сохранена");LOG.debug("{} update failed ({})",m.mod().name(),e.getClass().getSimpleName());return null;}
        finally{if(staged!=null)try{Files.deleteIfExists(staged);}catch(IOException ignored){}}
    }
    private static Path extractAgent(Path directory)throws IOException {
        try(var in=SharedUpdater.class.getResourceAsStream("/spidiban-shared-update-agent.jar")) {
            if(in==null)throw new IOException("Missing updater helper");byte[] data=in.readNBytes(1048577);if(data.length>1048576)throw new IOException("Helper too large");
            Path p=Files.createTempFile(directory,"agent-",".jar");Files.write(p,data);return p;
        }
    }
}
