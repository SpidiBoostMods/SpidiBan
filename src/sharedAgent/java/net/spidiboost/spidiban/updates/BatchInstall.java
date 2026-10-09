package net.spidiboost.spidiban.updates;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Instance-scoped transaction. A failed member rolls back all committed members. */
public final class BatchInstall {
    public static String hash(Path p)throws IOException {
        try {var d=MessageDigest.getInstance("SHA-256");try(var in=Files.newInputStream(p)){byte[] b=new byte[65536];int n;while((n=in.read(b))>=0)d.update(b,0,n);}return HexFormat.of().formatHex(d.digest());}
        catch(NoSuchAlgorithmException e){throw new AssertionError(e);}
    }
    public static void validate(BatchPlan p)throws IOException {
        Path game=p.game().toRealPath(),mods=game.resolve("mods").toRealPath(),dir=game.resolve(".spidiboost-updates").toRealPath();
        if(!mods.startsWith(game)||!dir.startsWith(game)||Files.isSymbolicLink(p.game().resolve(".spidiboost-updates")))throw new IOException("Foreign update directory");
        Set<Path> paths=new HashSet<>(),destinations=new HashSet<>();
        for(var c:p.changes()) {
            Path t=c.target().toRealPath(),dest=c.destination().getParent().toRealPath().resolve(c.destination().getFileName());
            if(!t.getParent().equals(mods)||!dest.getParent().equals(mods)||!paths.add(t)||!destinations.add(dest)
                ||Files.isSymbolicLink(t)||Files.isSymbolicLink(dest)||!Files.isRegularFile(t)||!t.getFileName().toString().endsWith(".jar")
                ||!dest.getFileName().toString().matches("[A-Za-z0-9]+-1\\.21\\.4-[0-9]+\\.[0-9]+\\.[0-9]+\\.jar")
                ||(!dest.equals(t)&&Files.exists(dest))||Files.isSymbolicLink(c.staged())||!c.staged().toRealPath().getParent().equals(dir)
                ||!c.oldHash().matches("[a-f0-9]{64}")||!c.newHash().matches("[a-f0-9]{64}")
                ||!hash(t).equals(c.oldHash())||!hash(c.staged()).equals(c.newHash()))throw new IOException("Invalid or changed batch member");
        }
        for(var r:p.removals()) {
            if(Files.isSymbolicLink(r.target())||!r.target().toRealPath().getParent().equals(mods)||!paths.add(r.target().toRealPath())||!r.hash().matches("[a-f0-9]{64}")||!hash(r.target()).equals(r.hash()))throw new IOException("Invalid legacy addon removal");
            try(var zip=new java.util.zip.ZipFile(r.target().toFile())) {var e=zip.getEntry("fabric.mod.json");if(e==null)throw new IOException("Legacy metadata missing");byte[] data;try(var in=zip.getInputStream(e)){data=in.readNBytes(65537);}if(data.length>65536||!java.util.regex.Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"spidiban_shist\\\"").matcher(new String(data,java.nio.charset.StandardCharsets.UTF_8)).find())throw new IOException("Only the obsolete Hist addon may be removed");}
        }
        for(var c:p.changes())if(!c.target().equals(c.destination())&&paths.contains(c.destination().getParent().toRealPath().resolve(c.destination().getFileName())))throw new IOException("Overlapping update paths");
    }
    public static void install(BatchPlan p)throws IOException {
        install(p,c->{});
    }
    static void install(BatchPlan p, java.util.function.Consumer<BatchPlan.Change> beforeCommit)throws IOException {
        validate(p);Path backupDir=p.game().resolve(".spidiboost-updates/backups");Files.createDirectories(backupDir);
        if(!backupDir.toRealPath().startsWith(p.game().toRealPath())||Files.isSymbolicLink(backupDir))throw new IOException("Foreign backup directory");
        Map<BatchPlan.Change,Path> backups=new LinkedHashMap<>(),temps=new LinkedHashMap<>();List<BatchPlan.Change> committed=new ArrayList<>();Map<BatchPlan.Removal,Path> removedBackups=new LinkedHashMap<>();List<BatchPlan.Removal> removed=new ArrayList<>();
        try {
            // Prepare and fsync every replacement before touching any installed mod.
            for(var c:p.changes()) {
                Path b=backupDir.resolve(c.oldHash()+".jar.bak");if(Files.isSymbolicLink(b))throw new IOException("Unsafe backup");
                if(!Files.exists(b))Files.copy(c.target(),b);if(!hash(b).equals(c.oldHash()))throw new IOException("Invalid backup");backups.put(c,b);
                Path temp=Files.createTempFile(c.target().getParent(),".spidiboost-",".tmp");temps.put(c,temp);Files.copy(c.staged(),temp,StandardCopyOption.REPLACE_EXISTING);
                try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){channel.force(true);}if(!hash(temp).equals(c.newHash()))throw new IOException("Invalid copied update");
            }
            for(var r:p.removals()){Path b=backupDir.resolve(r.hash()+".jar.bak");if(Files.isSymbolicLink(b))throw new IOException("Unsafe addon backup");if(!Files.exists(b))Files.copy(r.target(),b);if(!hash(b).equals(r.hash()))throw new IOException("Invalid addon backup");removedBackups.put(r,b);}
            validate(p);
            for(var c:p.changes()) {
                beforeCommit.accept(c);
                if(!hash(c.target()).equals(c.oldHash()))throw new IOException("Mod changed before commit");
                if(c.destination().equals(c.target())) move(temps.get(c),c.destination(),true);else move(temps.get(c),c.destination(),false);
                committed.add(c);
                if(!hash(c.destination()).equals(c.newHash()))throw new IOException("Installed checksum mismatch");
                if(!c.destination().equals(c.target()))Files.delete(c.target());
            }
        for(var r:p.removals()){if(!hash(r.target()).equals(r.hash()))throw new IOException("Legacy addon changed before removal");Files.delete(r.target());removed.add(r);}
        } catch(IOException | RuntimeException e) {
            for(var r:removed)try{Files.copy(removedBackups.get(r),r.target(),StandardCopyOption.REPLACE_EXISTING);}catch(IOException rollback){e.addSuppressed(rollback);}
            Collections.reverse(committed);
            for(var c:committed)try {
                Files.copy(backups.get(c),c.target(),StandardCopyOption.REPLACE_EXISTING);
                if(!c.destination().equals(c.target())&&Files.exists(c.destination())&&hash(c.destination()).equals(c.newHash()))Files.delete(c.destination());
            } catch(IOException rollback){e.addSuppressed(rollback);}
            throw e;
        } finally { for(Path t:temps.values())Files.deleteIfExists(t); }
        // Cleanup cannot invalidate an already successful transaction.
        for(var c:p.changes())try{Files.deleteIfExists(c.staged());}catch(IOException ignored){}
    }
    private static void move(Path from,Path to,boolean replace)throws IOException {
        // ATOMIC_MOVE may overwrite an existing destination even without REPLACE_EXISTING.
        if(!replace){Files.move(from,to);return;}
        try{Files.move(from,to,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
        catch(AtomicMoveNotSupportedException e){Files.move(from,to,StandardCopyOption.REPLACE_EXISTING);}
    }
}
