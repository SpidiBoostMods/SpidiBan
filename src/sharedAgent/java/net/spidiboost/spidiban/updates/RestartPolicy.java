package net.spidiboost.spidiban.updates;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Properties;

/** Shared instance preference. Download/install and auto-restart are deliberately independent. */
public final class RestartPolicy {
    private RestartPolicy(){}
    public static Path file(Path game){return game.toAbsolutePath().normalize().resolve(".spidiboost-updates/settings.properties");}
    public static boolean enabled(Path game){
        Path file=file(game);if(!Files.exists(file))return true;
        if(Files.isSymbolicLink(file))return false;
        try(var in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){
            var p=new Properties();p.load(in);return "true".equalsIgnoreCase(p.getProperty("autoRestart","true").strip());
        }catch(IOException|IllegalArgumentException e){return false;} // Do not unexpectedly close a game on broken preferences.
    }
    public static void set(Path game,boolean enabled)throws IOException {
        Path file=file(game);Files.createDirectories(file.getParent());
        if(Files.isSymbolicLink(file)||!file.getParent().toRealPath().startsWith(game.toRealPath()))throw new IOException("Foreign preference path");
        var p=new Properties();if(Files.exists(file))try(var in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(in);}
        p.setProperty("autoRestart",Boolean.toString(enabled));Path temp=Files.createTempFile(file.getParent(),"settings-",".tmp");
        try{
            try(var out=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)){p.store(out,"SpidiBoost updater: downloads remain enabled");}
            try{Files.move(temp,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
    }
}
