package net.spidiboost.spidiban.updates;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

public final class BatchAgent {
    public static void main(String[] args) {
        Path log=null;FileChannel legacy=null;FileLock legacyLock=null;
        try {
            BatchPlan plan=BatchPlan.read(System.in);BatchInstall.validate(plan);Path dir=plan.game().resolve(".spidiboost-updates");log=dir.resolve("update.log");
            try(var channel=FileChannel.open(dir.resolve("update.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);var lock=channel.tryLock()) {
                if(lock==null)throw new IllegalStateException("Update already pending");
                // Coordinate with an installed SpidiCard 1.4.x updater during the migration.
                if(plan.legacyCard()) {Path oldDir=plan.game().resolve(".spidicard");Files.createDirectories(oldDir);if(!oldDir.toRealPath().startsWith(plan.game().toRealPath()))throw new IllegalStateException("Foreign legacy lock");legacy=FileChannel.open(oldDir.resolve("update.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);legacyLock=legacy.tryLock();if(legacyLock==null)throw new IllegalStateException("Legacy update pending");}
                ProcessHandle parent=ProcessHandle.of(plan.pid()).orElseThrow();System.out.println("READY");System.out.flush();parent.onExit().get(7,TimeUnit.DAYS);
                BatchInstall.install(plan);Files.writeString(log,"SpidiBoost: all prepared updates installed successfully.\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);
                if(!plan.command().isEmpty()){Thread.sleep(1500);new ProcessBuilder(plan.command()).directory(plan.cwd().toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();}
            }
        }catch(Exception e){if(log!=null)try{Files.writeString(log,"SpidiBoost: update failed ("+e.getClass().getSimpleName()+"). Backups preserved.\n",StandardOpenOption.CREATE,StandardOpenOption.APPEND);}catch(Exception ignored){}System.out.println("ERROR");System.out.flush();System.exit(1);}
        finally{try{if(legacyLock!=null)legacyLock.close();if(legacy!=null)legacy.close();}catch(Exception ignored){}}
    }
}
